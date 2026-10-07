package io.github.libreroute.ui

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageButton
import android.view.Gravity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import io.github.libreroute.R

/** Native shared rows used by the user and administrator screens. */
object CalmViews {
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun header(context: Context, title: String, back: () -> Unit) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(context, 56)
        addView(ImageButton(context).apply {
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text_primary))
            contentDescription = context.getString(R.string.calm_back)
            setBackgroundResource(android.R.color.transparent)
            setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
            setOnClickListener { back() }
        }, LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)))
        addView(TextView(context).apply {
            text = title
            textSize = 24f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setPadding(dp(context, 8), dp(context, 12), dp(context, 16), dp(context, 12))
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    fun action(context: Context, label: String, secondary: Boolean = false, destructive: Boolean = false,
               block: () -> Unit) = MaterialButton(context, null,
        if (secondary || destructive) com.google.android.material.R.attr.materialButtonOutlinedStyle
        else com.google.android.material.R.attr.materialButtonStyle).apply {
        text = label
        textSize = 15f
        isAllCaps = false
        minimumHeight = dp(context, 56)
        maxLines = Int.MAX_VALUE
        cornerRadius = dp(context, 12)
        layoutParams = LinearLayout.LayoutParams(-1, -2)
        if (destructive) {
            val tint = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.calm_error))
            setTextColor(tint)
            strokeColor = tint
        }
        setOnClickListener { block() }
    }

    fun badges(context: Context, labels: List<String>) = ChipGroup(context).apply {
        isSingleLine = false
        labels.distinct().forEach { label ->
            addView(Chip(context).apply {
                text = label
                isCheckable = false
                isClickable = false
                setTextColor(ContextCompat.getColor(context, R.color.calm_text_secondary))
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.calm_surface_elevated))
                chipStrokeColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.calm_outline))
                chipStrokeWidth = dp(context, 1).toFloat()
            })
        }
    }

    fun row(context: Context, title: String, subtitle: String, selected: Boolean = false,
            action: (() -> Unit)? = null): MaterialCardView {
        return MaterialCardView(context).apply {
            radius = dp(context, 16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(context, 1)
            strokeColor = ContextCompat.getColor(context, if (selected) R.color.m3_primary else R.color.m3_outline)
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.bg_card))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(context, 12) }
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                minimumHeight = dp(context, 64)
                setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
                addView(TextView(context).apply {
                    text = title
                    textSize = 16f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                })
                if (subtitle.isNotEmpty()) addView(TextView(context).apply {
                    text = subtitle
                    textSize = 13f
                    setPadding(0, dp(context, 4), 0, 0)
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                })
            }
            addView(column)
            if (action != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
        }
    }
}
