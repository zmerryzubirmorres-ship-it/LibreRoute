package io.github.p1neapplexpress.openflux.data

import android.graphics.drawable.Drawable

data class AppItem(
    val name: String,
    val packageName: String,
    val icon: Drawable?,
    var isSelected: Boolean = false,
    val isSystem: Boolean = false,
    val isRussianPreset: Boolean = false
)
