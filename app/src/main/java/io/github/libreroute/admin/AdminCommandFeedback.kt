package io.github.libreroute.admin

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Spinner
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import java.util.WeakHashMap

/** View-only feedback; command authority and duplicate protection stay in the repository. */
object AdminCommandFeedback {
    fun bind(owner: LifecycleOwner, root: View, control: AdminCommandControl,
             independentViewIds: Set<Int> = emptySet(), onIdle: () -> Unit = {},
             progressViewIds: Set<Int>? = null, pendingLabel: () -> String? = { null }): () -> Unit {
        data class Original(val enabled: Boolean, val text: CharSequence?)
        val originals = WeakHashMap<View, Original>()
        fun render() {
            val actions = control.actions.value.filter { it.blocksUi }
            val action = actions.firstOrNull { it.operation != "get-cluster-snapshot" } ?: actions.firstOrNull()
            val label = action?.let { it.detail ?: it.phase.label } ?: pendingLabel()
            if (label == null) {
                val restored = originals.isNotEmpty()
                originals.forEach { (view, original) ->
                    view.isEnabled = original.enabled
                    if (view is Button && original.text != null) view.text = original.text
                }
                originals.clear()
                if (restored) onIdle()
                return
            }
            fun visit(view: View) {
                // Local actions and navigation do not require access to the queried server.
                if (view.id in independentViewIds) return
                if (view is Button || view is EditText || view is Spinner || view.isClickable) {
                    if (!originals.containsKey(view)) {
                        originals[view] = Original(view.isEnabled,
                            (view as? Button)?.takeUnless { it is CompoundButton }?.text)
                    }
                    val original = originals[view]!!
                    view.isEnabled = false
                    if (view is Button && original.enabled && original.text != null &&
                        (progressViewIds == null || view.id in progressViewIds)) {
                        if (view.text.toString() != label) view.text = label
                    }
                }
                if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
            visit(root)
        }
        val listener = ViewTreeObserver.OnPreDrawListener { render(); true }
        root.viewTreeObserver.addOnPreDrawListener(listener)
        val observer = object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(listener)
                originals.clear()
            }
        }
        owner.lifecycle.addObserver(observer)
        val job = owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                control.actions.collect { render() }
            }
        }
        return {
            job.cancel()
            owner.lifecycle.removeObserver(observer)
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(listener)
            originals.clear()
        }
    }
}
