package com.looker.droidify.installer.installers.session

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.looker.droidify.R
import com.looker.droidify.data.model.toPackageName
import com.looker.droidify.installer.InstallManager
import com.looker.droidify.installer.model.InstallState
import com.looker.droidify.utility.common.Constants.NOTIFICATION_CHANNEL_INSTALL
import com.looker.droidify.utility.common.createNotificationChannel
import com.looker.droidify.utility.common.extension.getPackageName
import com.looker.droidify.utility.common.extension.notificationManager
import com.looker.droidify.utility.notifications.createInstallNotification
import com.looker.droidify.utility.notifications.installNotification
import com.looker.droidify.utility.notifications.removeInstallNotification
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SessionInstallerReceiver : BroadcastReceiver() {

    // This is a cyclic dependency injection, I know but this is the best option for now
    @Inject
    lateinit var installManager: InstallManager

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // prompts user to enable unknown source
            val promptIntent: Intent? = intent.getParcelableExtra(Intent.EXTRA_INTENT)

            promptIntent?.let {
                it.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                it.putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, "com.android.vending")
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                promptUserAction(context, intent, it)
            }
        } else {
            notifyStatus(intent, context)
        }
    }

    /**
     * Show the system install-confirmation. A direct [Context.startActivity] only succeeds while the
     * app is in the foreground; backgrounded it is silently BAL-blocked and the install would stall.
     * So also post a tap-to-confirm notification (replacing the per-package install notification) —
     * a notification tap is a user gesture the system allows to launch the confirmation.
     */
    private fun promptUserAction(context: Context, statusIntent: Intent, promptIntent: Intent) {
        runCatching { context.startActivity(promptIntent) }

        val packageName = statusIntent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: return
        val appName = context.packageManager.getPackageName(packageName)?.toString() ?: packageName

        context.createNotificationChannel(
            id = NOTIFICATION_CHANNEL_INSTALL,
            name = context.getString(R.string.install),
        )
        val confirmIntent = PendingIntent.getActivity(
            context,
            packageName.hashCode(),
            promptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = context.createInstallNotification(
            appName = appName,
            state = InstallState.Pending,
        ) {
            setContentIntent(confirmIntent)
            setAutoCancel(true)
        }
        context.notificationManager?.installNotification(packageName, notification)
    }

    private fun notifyStatus(intent: Intent, context: Context) {
        val packageManager = context.packageManager
        val notificationManager = context.notificationManager

        context.createNotificationChannel(
            id = NOTIFICATION_CHANNEL_INSTALL,
            name = context.getString(R.string.install),
        )

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val isUninstall = intent.getBooleanExtra(ACTION_UNINSTALL, false)

        val appName = packageManager.getPackageName(packageName)

        if (packageName != null) {
            when (status) {
                PackageInstaller.STATUS_SUCCESS -> {
                    notificationManager?.removeInstallNotification(packageName)
                    val notification = context.createInstallNotification(
                        appName = (appName ?: packageName.substringAfterLast('.')).toString(),
                        state = InstallState.Installed,
                        isUninstall = isUninstall,
                    ) {
                        setTimeoutAfter(SUCCESS_TIMEOUT)
                    }
                    notificationManager?.installNotification(
                        packageName = packageName,
                        notification = notification,
                    )
                }

                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    notificationManager?.removeInstallNotification(packageName)
                    installManager.setFailed(packageName.toPackageName())
                }

                else -> {
                    installManager.remove(packageName.toPackageName())
                    val notification = context.createInstallNotification(
                        appName = appName.toString(),
                        state = InstallState.Failed,
                        isUninstall = isUninstall,
                    ) {
                        setContentText(message)
                    }
                    notificationManager?.installNotification(
                        packageName = packageName,
                        notification = notification,
                    )
                }
            }
        }
    }

    companion object {
        const val ACTION_UNINSTALL = "action_uninstall"

        private const val SUCCESS_TIMEOUT = 5_000L
    }
}
