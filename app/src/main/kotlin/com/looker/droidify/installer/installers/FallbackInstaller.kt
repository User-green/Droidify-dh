package com.looker.droidify.installer.installers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.looker.droidify.R
import com.looker.droidify.data.model.PackageName
import com.looker.droidify.installer.model.InstallItem
import com.looker.droidify.installer.model.InstallState
import kotlinx.coroutines.CancellationException

/**
 * Pairs a privileged [primary] installer (Dhizuku, Shizuku) with a [fallback] (the normal Session
 * installer). The privileged path is used only when [Installer.isAvailable] reports ready and the
 * install actually succeeds; on either an unavailable server or a failed privileged install the work
 * is routed to [fallback] (warning the user once). This keeps each privileged installer "pure" — it
 * never embeds the normal installer itself, so the two paths stay cleanly separated.
 */
class FallbackInstaller(
    private val context: Context,
    private val primary: Installer,
    private val fallback: Installer,
) : Installer {

    @Volatile
    private var warned = false

    // Mirror the primary so a persistent privileged binding (Dhizuku) is not closed per install.
    override val keepAliveAcrossQueue: Boolean get() = primary.keepAliveAcrossQueue

    override suspend fun install(installItem: InstallItem): InstallState {
        if (!primary.isAvailable()) return warnAndInstall(installItem)
        val result = try {
            primary.install(installItem)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            InstallState.Failed
        }
        return if (result == InstallState.Installed) result else warnAndInstall(installItem)
    }

    override suspend fun uninstall(packageName: PackageName) {
        if (!primary.isAvailable()) {
            fallback.uninstall(packageName)
            return
        }
        try {
            primary.uninstall(packageName)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fallback.uninstall(packageName)
        }
    }

    override fun close() {
        primary.close()
        fallback.close()
    }

    private suspend fun warnAndInstall(installItem: InstallItem): InstallState {
        warnOnce()
        return fallback.install(installItem)
    }

    /** Warns the user once per installer lifetime that the privileged path fell back. */
    private fun warnOnce() {
        if (warned) return
        warned = true
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, R.string.installer_fallback_warning, Toast.LENGTH_LONG).show()
        }
    }
}
