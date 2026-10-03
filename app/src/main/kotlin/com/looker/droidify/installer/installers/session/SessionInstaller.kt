package com.looker.droidify.installer.installers.session

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.looker.droidify.data.model.PackageName
import com.looker.droidify.installer.installers.Installer
import com.looker.droidify.installer.model.InstallItem
import com.looker.droidify.installer.model.InstallState
import com.looker.droidify.utility.common.SdkCheck
import com.looker.droidify.utility.common.cache.Cache
import com.looker.droidify.utility.common.log
import com.looker.droidify.utility.common.sdkAbove
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class SessionInstaller(private val context: Context) : Installer {

    private val installer = context.packageManager.packageInstaller
    private val intent = Intent(context, SessionInstallerReceiver::class.java)

    companion object {
        // Bounds the wait for the user to act on the install confirmation. Short so a
        // backgrounded/abandoned popup fails cleanly and frees the queue quickly; the user can
        // restart the install from the UI (or by reopening the app).
        private const val USER_ACTION_TIMEOUT_MS = 20_000L
        private val flags = if (SdkCheck.isSnowCake) PendingIntent.FLAG_MUTABLE else 0
    }

    override suspend fun install(
        installItem: InstallItem,
    ): InstallState = withTimeoutOrNull(USER_ACTION_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
        val cacheFile = Cache.getReleaseFile(context, installItem.installFileName)
        val id = installer.createSession(sessionParams(installItem))

        val callback = object : PackageInstaller.SessionCallback() {
            override fun onCreated(sessionId: Int) {}
            override fun onBadgingChanged(sessionId: Int) {}
            override fun onActiveChanged(sessionId: Int, active: Boolean) {}
            override fun onProgressChanged(sessionId: Int, progress: Float) {}
            override fun onFinished(sessionId: Int, success: Boolean) {
                if (sessionId != id || !cont.isActive) return
                installer.unregisterSessionCallback(this)
                cont.resume(if (success) InstallState.Installed else InstallState.Failed)
            }
        }

        cont.invokeOnCancellation {
            installer.unregisterSessionCallback(callback)
            try {
                installer.abandonSession(id)
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        }

        installer.registerSessionCallback(callback, Handler(Looper.getMainLooper()))

        val session = installer.openSession(id)

        session.use { activeSession ->
            val sizeBytes = cacheFile.length()
            cacheFile.inputStream().use { fileStream ->
                activeSession.openWrite(cacheFile.name, 0, sizeBytes).use { outputStream ->
                    if (cont.isActive) {
                        fileStream.copyTo(outputStream)
                        activeSession.fsync(outputStream)
                    }
                }
            }

            val pendingIntent = PendingIntent.getBroadcast(context, id, intent, flags)

            if (cont.isActive) activeSession.commit(pendingIntent.intentSender)
        }
        }
    } ?: run {
        // Backgrounding the app while the confirm popup is up leaves the session in
        // PENDING_USER_ACTION with no callback, so the wait would otherwise hang the whole queue.
        // Time out, abandon (via the coroutine's invokeOnCancellation) and fail so the queue moves on.
        log("Session install timed out waiting for user action", type = Log.WARN)
        InstallState.Failed
    }

    @SuppressLint("MissingPermission")
    override suspend fun uninstall(packageName: PackageName) =
        suspendCancellableCoroutine { cont ->
            intent.putExtra(SessionInstallerReceiver.ACTION_UNINSTALL, true)
            val pendingIntent = PendingIntent.getBroadcast(context, -1, intent, flags)

            installer.uninstall(packageName.name, pendingIntent.intentSender)
            cont.resume(Unit)
        }

    private fun sessionParams(installItem: InstallItem) =
        PackageInstaller.SessionParams(MODE_FULL_INSTALL).apply {
            sdkAbove(sdk = Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            sdkAbove(sdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setRequestUpdateOwnership(true)
            }
            sdkAbove(sdk = Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                val unarchiveId = installItem.unarchiveId
                if (unarchiveId != null) {
                    setUnarchiveId(unarchiveId)
                }
            }
        }
}
