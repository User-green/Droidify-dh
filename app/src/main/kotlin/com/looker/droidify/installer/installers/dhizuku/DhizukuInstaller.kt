package com.looker.droidify.installer.installers.dhizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageInstaller
import android.os.DeadObjectException
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.looker.droidify.BuildConfig
import com.looker.droidify.data.model.PackageName
import com.looker.droidify.installer.installers.Installer
import com.looker.droidify.installer.installers.awaitDhizukuAlive
import com.looker.droidify.installer.installers.isDhizukuGranted
import com.looker.droidify.installer.installers.wakeDhizukuServer
import com.looker.droidify.installer.model.InstallItem
import com.looker.droidify.installer.model.InstallState
import com.looker.droidify.utility.common.SdkCheck
import com.looker.droidify.utility.common.cache.Cache
import com.looker.droidify.utility.common.log
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuUserServiceArgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Installs apps through [Dhizuku], which holds device-owner privileges.
 *
 * Dhizuku loads [DhizukuInstallerService] inside its own device-owner process; this class binds to
 * it and forwards the APK (as a [ParcelFileDescriptor]) so the privileged side can drive
 * PackageInstaller silently.
 *
 * This is a *pure* privileged installer: it never installs by any other means. When the privileged
 * path is unusable it reports false from [isAvailable] (or [InstallState.Failed] from [install]) and
 * lets [com.looker.droidify.installer.installers.FallbackInstaller] route to the normal installer.
 * The available server is not always the standalone Dhizuku app — it may be a third-party server
 * (e.g. OwnDroid's built-in one) — so binding is defensive and retried.
 */
class DhizukuInstaller(private val context: Context) : Installer {

    // The privileged binding must survive a whole install queue, so the caller must not close us
    // per item; closing per item would unbind and race the server tearing the service down.
    override val keepAliveAcrossQueue: Boolean get() = true

    private val lock = Mutex()
    private var connection: ServiceConnection? = null
    private var service: IDhizukuInstallerService? = null

    private val userServiceArgs by lazy {
        DhizukuUserServiceArgs(ComponentName(context, DhizukuInstallerService::class.java))
    }

    /** Pre-flight: a Dhizuku-compatible server is present, awake, and has granted permission. */
    override suspend fun isAvailable(): Boolean =
        awaitDhizukuAlive(context) && isDhizukuGranted()

    override suspend fun install(installItem: InstallItem): InstallState {
        val file = Cache.getReleaseFile(context, installItem.installFileName)
        if (!file.exists() || file.length() == 0L) {
            logD("install: cache file missing/empty (${file.absolutePath})", Log.ERROR)
            return InstallState.Failed
        }
        // The binding is kept alive across a batch, but the server can still kill the UserService
        // between installs. A stale binder throws DeadObjectException; drop it and re-bind a fresh
        // service before giving up.
        repeat(INSTALL_ATTEMPTS) { attempt ->
            val remote = bind() ?: return InstallState.Failed
            try {
                val status = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    remote.install(pfd, file.length(), context.packageName)
                }
                return if (status == PackageInstaller.STATUS_SUCCESS) {
                    logD("install: SUCCESS via Dhizuku (${installItem.packageName.name})")
                    InstallState.Installed
                } else {
                    logD("install: Dhizuku returned status=$status", Log.WARN)
                    InstallState.Failed
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DeadObjectException) {
                logD("install: dead binder on attempt ${attempt + 1}, re-binding", Log.WARN)
                invalidate()
                if (attempt < INSTALL_ATTEMPTS - 1) delay(BIND_RETRY_DELAY_MS)
            } catch (e: Exception) {
                logD("install: remote.install threw\n${Log.getStackTraceString(e)}", Log.ERROR)
                invalidate()
                return InstallState.Failed
            }
        }
        return InstallState.Failed
    }

    override suspend fun uninstall(packageName: PackageName) {
        val remote = bind() ?: error("Dhizuku unavailable for uninstall")
        try {
            val status = remote.uninstall(packageName.name)
            logD("uninstall: Dhizuku returned status=$status (${packageName.name})")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            invalidate()
            throw e
        }
    }

    override fun close() = invalidate()

    /**
     * Lazily binds (and caches) the privileged Dhizuku service, or null if it can't be used.
     *
     * Retries a few times: a third-party server (e.g. OwnDroid) is often a *frozen/cached* process,
     * and the synchronous bind transaction makes Android kill it ("Sync transaction while in frozen
     * state") instead of connecting — but it restarts immediately afterward, so the next attempt
     * lands on the live process. Only after the attempts are exhausted does the caller fall back.
     */
    private suspend fun bind(): IDhizukuInstallerService? = lock.withLock {
        service?.let { return@withLock it }
        // Dhizuku-API requires API 26+; below that the privileged path is unavailable.
        if (!SdkCheck.isOreo) {
            logD("bind: pre-API-26, unavailable")
            return@withLock null
        }
        repeat(BIND_ATTEMPTS) { attempt ->
            wakeDhizukuServer(context)
            val ready = runCatching {
                Dhizuku.init(context) && Dhizuku.isPermissionGranted()
            }.getOrDefault(false)
            if (ready) {
                val bound = bindOnce()
                if (bound != null) {
                    if (attempt > 0) logD("bind: connected on attempt ${attempt + 1}")
                    return@withLock bound
                }
                // Bind was accepted but never connected. The usual cause is the server holding a
                // stale package context for us (our APK path changed after an update), so it can't
                // load our class. stopUserService() makes the server tear down its UserService
                // process (System.exit) so the next attempt spawns a fresh one that re-resolves our
                // current APK.
                runCatching { Dhizuku.stopUserService(userServiceArgs) }
                    .onFailure { logD("bind: stopUserService failed: ${it.message}", Log.WARN) }
            } else {
                logD("bind: not ready on attempt ${attempt + 1} (init/permission false)", Log.WARN)
            }
            if (attempt < BIND_ATTEMPTS - 1) delay(BIND_RETRY_DELAY_MS)
        }
        logD("bind: exhausted $BIND_ATTEMPTS attempts", Log.ERROR)
        null
    }

    /** A single bind attempt; resolves to the connected service or null on failure/timeout. */
    private suspend fun bindOnce(): IDhizukuInstallerService? {
        val bound = withTimeoutOrNull(BIND_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        logD("bind: onServiceConnected (binder=${binder != null})")
                        val service = IDhizukuInstallerService.Stub.asInterface(binder)
                        this@DhizukuInstaller.service = service
                        connection = this
                        if (cont.isActive) cont.resume(service)
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        logD("bind: onServiceDisconnected", Log.WARN)
                        service = null
                        connection = null
                    }
                }
                val started = runCatching { Dhizuku.bindUserService(userServiceArgs, conn) }
                    .onFailure { logD("bind: bindUserService threw\n${Log.getStackTraceString(it)}", Log.ERROR) }
                    .getOrDefault(false)
                logD("bind: bindUserService started=$started")
                if (!started && cont.isActive) cont.resume(null)
                cont.invokeOnCancellation { runCatching { Dhizuku.unbindUserService(conn) } }
            }
        }
        if (bound == null) logD("bind: attempt timed out after ${BIND_TIMEOUT_MS}ms", Log.ERROR)
        return bound
    }

    /** Drops the cached binding so the next operation re-binds. */
    private fun invalidate() {
        connection?.let { runCatching { Dhizuku.unbindUserService(it) } }
        connection = null
        service = null
    }

    // Debug-only: stripped (no-op) in release builds.
    private fun logD(message: String, type: Int = Log.INFO) {
        if (BuildConfig.DEBUG) log(message, TAG, type)
    }

    private companion object {
        const val BIND_ATTEMPTS = 2
        const val INSTALL_ATTEMPTS = 2
        // Generous enough for a cold UserService spawn on a slow device (imageless ART fallback) to
        // load our APK and instantiate the service before the attempt is abandoned.
        const val BIND_TIMEOUT_MS = 10_000L
        const val BIND_RETRY_DELAY_MS = 1_000L
        const val TAG = "DroidifyDhizuku"
    }
}
