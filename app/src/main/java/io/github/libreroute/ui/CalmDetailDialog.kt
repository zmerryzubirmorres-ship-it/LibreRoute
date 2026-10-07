package io.github.libreroute.ui

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import io.github.libreroute.R

/** A scrollable native detail screen, with a back action and no shell navigation. */
class CalmDetailDialog(context: Context, title: String) : Dialog(context, R.style.Theme_SoxMax) {
    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(CalmViews.dp(context, 16), 0, CalmViews.dp(context, 16), CalmViews.dp(context, 24))
    }
    init {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.bg_deep))
        }
        root.fitsSystemWindows = true
        root.addView(CalmViews.header(context, title) { dismiss() })
        root.addView(ScrollView(context).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }
    fun text(value: String) {
        content.addView(TextView(context).apply {
            text = value
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, CalmViews.dp(context, 12), 0, CalmViews.dp(context, 12))
        })
    }
    fun action(label: String, destructive: Boolean = false, secondary: Boolean = false, block: () -> Unit) {
        content.addView(CalmViews.action(context, label, secondary, destructive, block))
    }
    override fun show() {
        super.show()
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}
