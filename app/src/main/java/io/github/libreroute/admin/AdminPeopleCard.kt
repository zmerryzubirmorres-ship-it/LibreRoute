package io.github.libreroute.admin

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import io.github.libreroute.R
import io.github.libreroute.ui.CalmViews

internal fun adminPeopleCard(context: Context, user: AdminUser, pendingDocument: Boolean,
                             routes: String, open: () -> Unit): MaterialCardView {
    fun dp(value: Int) = CalmViews.dp(context, value)
    fun color(value: Int) = ContextCompat.getColor(context, value)
    val status = when {
        user.isRevoked -> "Доступ отозван" to R.color.calm_error
        user.isSuspended -> "Доступ приостановлен" to R.color.calm_warning
        pendingDocument -> "Ожидается ключ устройства" to R.color.calm_warning
        else -> "Доступ разрешён" to R.color.calm_success
    }
    return MaterialCardView(context).apply {
        radius = dp(18).toFloat(); cardElevation = 0f; strokeWidth = dp(1)
        strokeColor = color(R.color.m3_outline_variant)
        setCardBackgroundColor(color(R.color.m3_surface_container_lowest))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        isClickable = true; isFocusable = true; setOnClickListener { open() }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(16), dp(12), dp(16))
            addView(TextView(context).apply {
                text = user.name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
                textSize = 20f; gravity = Gravity.CENTER; setTextColor(color(R.color.m3_on_primary_container))
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(R.color.m3_primary_container)) }
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = user.name; textSize = 17f; setTextColor(color(R.color.text_primary))
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
                addView(TextView(context).apply {
                    text = status.first; textSize = 13f; setTextColor(color(status.second)); setPadding(0, dp(4), 0, 0)
                })
                addView(TextView(context).apply {
                    text = routes; textSize = 13f; setTextColor(color(R.color.text_secondary))
                    maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12); marginEnd = dp(8) })
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_chevron_right)
                imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.text_secondary))
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(20), dp(20)))
        })
    }
}
