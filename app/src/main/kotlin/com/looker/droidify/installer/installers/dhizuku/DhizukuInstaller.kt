package com.looker.droidify.installer.installers.dhizuku

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.looker.droidify.R
import com.looker.droidify.data.model.PackageName
import com.looker.droidify.installer.installers.Installer
import com.looker.droidify.installer.installers.session.SessionInstaller
import com.looker.droidify.installer.model.InstallItem
import com.looker.droidify.installer.model.InstallState
import com.looker.droidify.utility.common.cache.Cache
import com.looker.droidify.utility.common.extension.getPackageInfoCompat
import com.looker.droidify.utility.common.extension.size
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

class DhizukuInstaller(private val context: Context) : Installer {

    private val installManager = DhizukuInstallManager(context)

    // Fallback for a non-compliant / unreachable Dhizuku server. Created eagerly (cheap, no side
    // effects) so close() can release it deterministically.
    private val sessionInstaller = SessionInstaller(context)

    override suspend fun install(installItem: InstallItem): InstallState {
        val file = Cache.getReleaseFile(context, installItem.installFileName)
        if (file.length() == 0L) {
            error("File is not valid: Size ${file.size}")
        }
        if (!ensureDhizukuInstallerReady(context)) {
            Log.w(
                TAG,
                "Dhizuku not ready for ${installItem.packageName.name}; " +
                    "falling back to session installer",
            )
            return fallbackInstall(installItem)
        }
        return try {
            installManager.installApk(file.absolutePath)
            awaitPackageVisible(installItem.packageName.name)
            InstallState.Installed
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Dhizuku install failed: ${installItem.packageName.name}; " +
                    "falling back to session installer",
                e,
            )
            fallbackInstall(installItem)
        }
    }

    private suspend fun awaitPackageVisible(packageName: String) {
        repeat(PACKAGE_VISIBLE_ATTEMPTS) {
            if (context.packageManager.getPackageInfoCompat(packageName) != null) return
            delay(PACKAGE_VISIBLE_DELAY_MS)
        }
    }

    override suspend fun uninstall(packageName: PackageName) {
        if (!ensureDhizukuInstallerReady(context)) {
            Log.w(
                TAG,
                "Dhizuku not ready to uninstall ${packageName.name}; " +
                    "falling back to session installer",
            )
            warnFallback()
            sessionInstaller.uninstall(packageName)
            return
        }
        try {
            installManager.uninstallPackage(packageName.name)
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Dhizuku uninstall failed: ${packageName.name}; " +
                    "falling back to session installer",
                e,
            )
            warnFallback()
            sessionInstaller.uninstall(packageName)
        }
    }

    private suspend fun fallbackInstall(installItem: InstallItem): InstallState {
        warnFallback()
        return sessionInstaller.install(installItem)
    }

    /**
     * Warns the user, once per process, that the privileged Dhizuku path was unavailable and the
     * default (session) installer is being used instead — which will prompt for confirmation.
     */
    private fun warnFallback() {
        if (fallbackWarned.getAndSet(true)) return
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, R.string.dhizuku_fallback_warning, Toast.LENGTH_LONG).show()
        }
    }

    override fun close() = sessionInstaller.close()

    companion object {
        private const val TAG = "DhizukuInstaller"
        private const val PACKAGE_VISIBLE_ATTEMPTS = 20
        private const val PACKAGE_VISIBLE_DELAY_MS = 250L

        // Process-wide so a batch (e.g. update-all) shows the warning at most once.
        private val fallbackWarned = AtomicBoolean(false)
    }
}
