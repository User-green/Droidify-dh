package com.looker.droidify.installer

import android.content.Context
import com.looker.droidify.data.model.PackageName
import com.looker.droidify.database.Database
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.get
import com.looker.droidify.datastore.model.InstallerType
import com.looker.droidify.installer.installers.FallbackInstaller
import com.looker.droidify.installer.installers.Installer
import com.looker.droidify.installer.installers.LegacyInstaller
import com.looker.droidify.installer.installers.dhizuku.DhizukuInstaller
import com.looker.droidify.installer.installers.root.RootInstaller
import com.looker.droidify.installer.installers.session.SessionInstaller
import com.looker.droidify.installer.installers.shizuku.ShizukuInstaller
import com.looker.droidify.installer.model.InstallItem
import com.looker.droidify.installer.model.InstallState
import com.looker.droidify.service.SyncService
import com.looker.droidify.utility.common.Constants
import com.looker.droidify.utility.common.cache.Cache
import com.looker.droidify.utility.common.extension.addAndCompute
import com.looker.droidify.utility.common.extension.filter
import com.looker.droidify.utility.common.extension.notificationManager
import com.looker.droidify.utility.common.extension.updateAsMutable
import com.looker.droidify.utility.notifications.createInstallNotification
import com.looker.droidify.utility.notifications.installNotification
import com.looker.droidify.utility.notifications.removeInstallNotification
import com.looker.droidify.utility.notifications.updatesAvailableNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InstallManager(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) {

    private val installItems = Channel<InstallItem>()
    private val uninstallItems = Channel<PackageName>()

    val state = MutableStateFlow<Map<PackageName, InstallState>>(emptyMap())

    private var _installer: Installer? = null
        set(value) {
            field?.close()
            field = value
        }
    private val installer: Installer get() = _installer!!

    private val lock = Mutex()

    // The single install currently being processed (installs run one at a time), and a flag set by
    // [restartInstall] to re-run that same item in place instead of advancing to the next one.
    @Volatile
    private var activeInstall: Pair<String, Job>? = null

    @Volatile
    private var restartRequested = false

    private val skipSignature = settingsRepository.get { ignoreSignature }
    private val installerPreference = settingsRepository.get { installerType }
    private val deleteApkPreference = settingsRepository.get { deleteApkOnInstall }
    private val notificationManager by lazy { context.notificationManager }

    suspend operator fun invoke() = coroutineScope {
        setupInstaller()
        installer()
        uninstaller()
    }

    fun close() {
        _installer = null
        uninstallItems.close()
        installItems.close()
    }

    suspend infix fun install(installItem: InstallItem) {
        installItems.send(installItem)
    }

    suspend infix fun uninstall(packageName: PackageName) {
        uninstallItems.send(packageName)
    }

    infix fun remove(packageName: PackageName) {
        updateState { remove(packageName) }
    }

    infix fun setFailed(packageName: PackageName) {
        updateState { put(packageName, InstallState.Failed) }
    }

    /**
     * Kills the in-flight install for [packageName] (if it is the one running) and re-runs it in
     * place — used by the UI "restart installer" action for interactive installers whose
     * confirmation popup was missed or abandoned. Never advances the queue to the next app.
     */
    fun restartInstall(packageName: PackageName) {
        val active = activeInstall ?: return
        if (active.first == packageName.name) {
            restartRequested = true
            active.second.cancel()
        }
    }

    private fun CoroutineScope.setupInstaller() = launch {
        installerPreference.collectLatest(::setInstaller)
    }

    private fun CoroutineScope.installer() = launch {
        val currentQueue = mutableSetOf<String>()
        installItems.filter { item ->
            currentQueue.addAndCompute(item.packageName.name) { isAdded ->
                if (isAdded) {
                    updateState { put(item.packageName, InstallState.Pending) }
                }
            }
        }.consumeEach { item ->
            if (state.value.containsKey(item.packageName)) {
                notificationManager?.installNotification(
                    packageName = item.packageName.name,
                    notification = context.createInstallNotification(
                        appName = item.packageName.name,
                        state = InstallState.Installing,
                    ),
                )
                // restartInstall() cancels the in-flight attempt and we re-run THIS item in place, so
                // a restart never advances the queue to the next app.
                var result: InstallState
                do {
                    restartRequested = false
                    updateState { put(item.packageName, InstallState.Installing) }
                    var attempt: InstallState = InstallState.Failed
                    // A persistent installer (Dhizuku) holds one privileged binding for the whole
                    // queue; closing it per-item (use{}) unbinds and races the server killing the
                    // service. Every other installer keeps the original close-per-item behaviour.
                    val job = launch {
                        attempt = if (installer.keepAliveAcrossQueue) {
                            installer.install(item)
                        } else {
                            installer.use { it.install(item) }
                        }
                    }
                    activeInstall = item.packageName.name to job
                    job.join()
                    activeInstall = null
                    result = if (job.isCancelled) InstallState.Failed else attempt
                } while (restartRequested)
                if (result == InstallState.Installed && installer !is LegacyInstaller) {
                    if (deleteApkPreference.first()) {
                        val apkFile = Cache.getReleaseFile(context, item.installFileName)
                        apkFile.delete()
                    }
                }
                if (result == InstallState.Installed && SyncService.autoUpdating) {
                    val updates = Database.ProductAdapter.getUpdates(skipSignature.first())
                    when {
                        updates.isEmpty() -> {
                            SyncService.autoUpdating = false
                            notificationManager?.cancel(Constants.NOTIFICATION_ID_UPDATES)
                        }
                        updates.map { it.packageName } != SyncService.autoUpdateStartedFor -> {
                            notificationManager?.notify(
                                Constants.NOTIFICATION_ID_UPDATES,
                                updatesAvailableNotification(context, updates),
                            )
                        }
                    }
                }
                notificationManager?.removeInstallNotification(item.packageName.name)
                updateState { put(item.packageName, result) }
                currentQueue.remove(item.packageName.name)
            }
        }
    }

    private fun CoroutineScope.uninstaller() = launch {
        uninstallItems.consumeEach {
            installer.uninstall(it)
        }
    }

    private suspend fun setInstaller(installerType: InstallerType) {
        lock.withLock {
            _installer = when (installerType) {
                InstallerType.LEGACY -> LegacyInstaller(context, settingsRepository)
                InstallerType.SESSION -> SessionInstaller(context)
                InstallerType.SHIZUKU ->
                    FallbackInstaller(context, ShizukuInstaller(context), SessionInstaller(context))
                InstallerType.ROOT -> RootInstaller(context)
                InstallerType.DHIZUKU ->
                    FallbackInstaller(context, DhizukuInstaller(context), SessionInstaller(context))
            }
        }
    }

    private inline fun updateState(block: MutableMap<PackageName, InstallState>.() -> Unit) {
        state.update { it.updateAsMutable(block) }
    }
}
