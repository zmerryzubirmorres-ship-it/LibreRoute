package io.github.libreroute.ui

import androidx.lifecycle.ViewModel
import io.github.libreroute.util.ConfigBackupManager

/** Secrets survive configuration changes in memory, never in an Android Bundle. */
class BackupDraftViewModel : ViewModel() {
    var request = ""
    var password = ""
    var prepared: ConfigBackupManager.LibreRouteBackup? = null
    var proxyUsername: String? = null
    var proxyPassword: String? = null
    override fun onCleared() { password = ""; prepared = null; proxyUsername = null; proxyPassword = null }
}
