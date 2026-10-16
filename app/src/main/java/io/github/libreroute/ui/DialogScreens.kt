package io.github.libreroute.ui

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner

/**
 * Lifecycle owner for native dialogs. Only route arguments and Android view state
 * cross recreation; dialogs, views and action callbacks always belong to the new
 * Activity/Fragment. Restoring a route must only build UI, never submit an action.
 * Call restore() after binding the screen, before restored DialogFragments start.
 */
class DialogScreens(
    private val owner: SavedStateRegistryOwner,
    private val reopen: (String, Bundle) -> Unit,
) : DefaultLifecycleObserver {
    private data class Screen(val key: String, val args: Bundle, val dialog: Dialog)
    private val screens = mutableListOf<Screen>()
    private var pending = owner.savedStateRegistry.consumeRestoredStateForKey(REGISTRY_KEY)
        ?.getParcelableArrayList<Bundle>("screens").orEmpty().toMutableList()
    private var restoring: Bundle? = null
    private var destroyed = false

    init {
        owner.lifecycle.addObserver(this)
        owner.savedStateRegistry.registerSavedStateProvider(REGISTRY_KEY) {
            Bundle().apply {
                putParcelableArrayList("screens", ArrayList(pending + screens.filter { it.dialog.isShowing }.map {
                    Bundle().apply {
                        putString("key", it.key)
                        putBundle("args", it.args)
                        putBundle("view", it.dialog.onSaveInstanceState())
                    }
                }))
            }
        }
    }

    fun show(dialog: Dialog, key: String, args: Bundle = Bundle(),
             progressViewIds: Set<Int>? = null, pendingLabel: () -> String? = { null },
             onDismiss: () -> Unit = {}) {
        if (destroyed) return
        dialog.create()
        dialog.window?.decorView?.let(::assignStateIds)
        val screen = Screen(key, Bundle(args), dialog)
        screens.add(screen)
        var clearFeedback: (() -> Unit)? = null
        dialog.setOnDismissListener { clearFeedback?.invoke(); screens.remove(screen); onDismiss() }
        dialog.show()
        if (owner.javaClass.name.startsWith("io.github.libreroute.admin.")) {
            dialog.window?.decorView?.let { root ->
                clearFeedback = io.github.libreroute.admin.AdminCommandFeedback.bind(owner, root,
                    io.github.libreroute.admin.AdminRepository.getInstance(dialog.context).commandControl,
                    progressViewIds = progressViewIds, pendingLabel = pendingLabel)
            }
        }
        restoring?.takeIf { it.getString("key") == key }?.getBundle("view")?.let {
            dialog.onRestoreInstanceState(it)
        }
    }

    fun restore() {
        val routes = pending.toList()
        pending.clear()
        routes.forEach { route ->
            restoring = route
            reopen(route.getString("key").orEmpty(), route.getBundle("args") ?: Bundle())
            restoring = null
        }
    }

    fun dismiss(key: String) {
        screens.toList().asReversed().filter { it.key == key }.forEach { it.dialog.dismiss() }
    }

    /** A Fragment may lose its view while the Fragment itself stays on Back stack. */
    fun detach() {
        pending.addAll(screens.filter { it.dialog.isShowing }.map {
            Bundle().apply {
                putString("key", it.key); putBundle("args", it.args)
                putBundle("view", it.dialog.onSaveInstanceState())
            }
        })
        screens.toList().asReversed().forEach { it.dialog.dismiss() }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        destroyed = true
        screens.toList().asReversed().forEach { it.dialog.dismiss() }
        screens.clear()
    }

    companion object {
        private const val REGISTRY_KEY = "dialog_screens"

        /** Stable semantic IDs for controls repeated in a dynamic server list. */
        fun stateId(key: String): Int = 0x00d00000 or (key.hashCode() and 0x000fffff)

        private fun assignStateIds(root: View) {
            var index = 0
            fun visit(view: View) {
                val selected = (view as? android.widget.RadioGroup)?.let { it.findViewById<View>(it.checkedRadioButtonId) }
                // XML resource IDs stay intact. Generated IDs change on rotation.
                if (view.id == View.NO_ID || view.id ushr 24 == 0) {
                    if (view.id ushr 20 != 0xd) view.id = 0x00e00000 + index
                }
                index++
                if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
                if (view is android.widget.RadioGroup && selected != null) view.check(selected.id)
            }
            visit(root)
        }
    }
}
