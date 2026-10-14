package io.github.libreroute.ui

import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner

/** Restore only after an asynchronously rebuilt page has laid out its content. */
class ScreenScrollPosition(owner: SavedStateRegistryOwner, private val scroll: ScrollView) : DefaultLifecycleObserver {
    private var pending = owner.savedStateRegistry.consumeRestoredStateForKey("screen_scroll")?.getInt("y")
    private var listener: View.OnLayoutChangeListener? = null
    init {
        owner.lifecycle.addObserver(this)
        owner.savedStateRegistry.registerSavedStateProvider("screen_scroll") {
            Bundle().apply { putInt("y", pending ?: scroll.scrollY) }
        }
    }

    fun beforeRebuild() {
        val position = pending ?: scroll.scrollY
        pending = position
        listener?.let(scroll::removeOnLayoutChangeListener)
        listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            listener?.let(scroll::removeOnLayoutChangeListener)
            listener = null
            scroll.scrollTo(0, position)
            pending = null
        }.also(scroll::addOnLayoutChangeListener)
    }

    override fun onDestroy(owner: LifecycleOwner) { listener?.let(scroll::removeOnLayoutChangeListener); listener = null }
}
