package com.looker.droidify.installer.installers

import com.looker.droidify.data.model.PackageName
import com.looker.droidify.installer.model.InstallItem
import com.looker.droidify.installer.model.InstallState

interface Installer : AutoCloseable {

    /**
     * Cheap pre-flight check: whether this installer can be used right now (server present, awake,
     * permission granted, ...). Callers probe this BEFORE [install]/[uninstall] so they can route to
     * a fallback without attempting a doomed operation. The built-in installers (Session/Legacy/Root)
     * have nothing to probe, so this defaults to always-available.
     */
    suspend fun isAvailable(): Boolean = true

    /**
     * When true, the installer holds state (e.g. a privileged binding) that must survive across a
     * whole install queue, so callers must NOT close it per item. Defaults to false (stateless).
     */
    val keepAliveAcrossQueue: Boolean get() = false

    suspend fun install(installItem: InstallItem): InstallState

    suspend fun uninstall(packageName: PackageName)
}
