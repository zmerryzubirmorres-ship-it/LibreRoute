package io.github.libreroute.ui

import androidx.lifecycle.ViewModel

/** Credential drafts remain in memory across rotation, outside saved view state. */
class HomeProxyDraftViewModel : ViewModel() {
    var username: String? = null
    var password: String? = null
    fun clear() { username = null; password = null }
    override fun onCleared() = clear()
}
