package io.github.p1neapplexpress.openflux.util

import android.util.Log
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.event.EventBus

object Logx {
    private var verbose = false

    fun init(isDebug: Boolean) { verbose = isDebug }
    fun setVerbose(isDebug: Boolean) { verbose = isDebug }
    val isVerbose: Boolean get() = verbose

    fun d(tag: String, msg: String) {
        if (!verbose) return
        Log.d(tag, msg)
        EventBus.dispatch(AppEvent.LogMessage("[D/$tag] $msg"))
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        EventBus.dispatch(AppEvent.LogMessage("[I/$tag] $msg"))
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        Log.w(tag, msg, tr)
        EventBus.dispatch(AppEvent.LogMessage("[W/$tag] $msg" + (tr?.let { "\n${it.stackTraceToString()}" } ?: "")))
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        Log.e(tag, msg, tr)
        EventBus.dispatch(AppEvent.LogMessage("[E/$tag] $msg" + (tr?.let { "\n${it.stackTraceToString()}" } ?: "")))
    }

    /** Never logs value — only key presence. */
    fun secret(tag: String, key: String) {
        if (!verbose) return
        Log.d(tag, "secret present: $key")
    }
}
