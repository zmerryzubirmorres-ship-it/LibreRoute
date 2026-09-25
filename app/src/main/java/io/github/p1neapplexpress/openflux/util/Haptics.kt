package io.github.p1neapplexpress.openflux.util

import android.view.HapticFeedbackConstants
import android.view.View

fun View.performAppHaptics(feedbackConstant: Int = HapticFeedbackConstants.VIRTUAL_KEY): Boolean {
    val settings = AppSettings(context)
    return if (settings.hapticFeedback) {
        performHapticFeedback(feedbackConstant)
    } else false
}

