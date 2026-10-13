package io.github.libreroute.ui

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.libreroute.R
import io.github.libreroute.data.ZapretProfile
import io.github.libreroute.util.ThemePreferences
import io.github.libreroute.util.ZapretProfilePreferences

class ZapretProfilesActivity : AppCompatActivity() {
    private val dialogs by lazy { DialogScreens(this) { key, args ->
        if (key == "delete") ZapretProfilePreferences(this).load().profiles.firstOrNull { it.id == args.getString("id") }?.let(::confirmDelete)
    } }
    private val scrollPosition by lazy { ScreenScrollPosition(this, generateSequence(findViewById<android.view.View>(R.id.zapret_profiles)) { it.parent as? android.view.View }.filterIsInstance<android.widget.ScrollView>().first()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemePreferences(this).applyTheme()
        setContentView(R.layout.activity_zapret_profiles)
        findViewById<android.view.View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.zapret_add).setOnClickListener {
            startActivity(Intent(this, ZapretProfileEditActivity::class.java))
        }
        findViewById<android.view.View>(R.id.zapret_rollback).setOnClickListener {
            change { prefs -> prefs.store(prefs.load().rollback()) }
        }
        dialogs.restore()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun change(action: (ZapretProfilePreferences) -> Unit) {
        runCatching { action(ZapretProfilePreferences(this)) }.fold(
            onSuccess = { render() },
            onFailure = { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
        )
    }

    private fun confirmDelete(profile: ZapretProfile) {
        val state = ZapretProfilePreferences(this).load()
        if (profile.id == state.activeId || profile.id == ZapretProfile.DEFAULT.id) return
        MaterialAlertDialogBuilder(this).setTitle(R.string.zapret_delete)
                        .setMessage(profile.name).setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.zapret_delete) { _, _ ->
                            change { prefs ->
                                require(io.github.libreroute.util.SiteRoutingPreferences(this).load().rules.none { it.profile == profile.id }) {
                                    getString(R.string.site_profile_in_use)
                                }
                                require(io.github.libreroute.util.AppRoutingPreferences(this).load().rules.none { it.profile == profile.id }) {
                                    getString(R.string.app_profile_in_use)
                                }
                                prefs.store(prefs.load().delete(profile.id))
                            }
                        }.create().let { dialogs.show(it, "delete", Bundle().apply { putString("id", profile.id) }) }
    }

    private fun render() {
        val container = findViewById<LinearLayout>(R.id.zapret_profiles)
        scrollPosition.beforeRebuild()
        container.removeAllViews()
        val state = runCatching { ZapretProfilePreferences(this).load() }.getOrElse {
            Toast.makeText(this, it.message, Toast.LENGTH_LONG).show()
            return
        }
        findViewById<TextView>(R.id.zapret_selected).text = getString(R.string.zapret_selected, state.active.name)
        findViewById<MaterialButton>(R.id.zapret_rollback).isEnabled = state.previous != null
        findViewById<MaterialButton>(R.id.zapret_add).isEnabled = state.profiles.size < 32
        state.profiles.forEach { profile ->
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
                radius = dp(16).toFloat()
                cardElevation = 0f
            }
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            column.addView(TextView(this).apply {
                text = profile.name
                textSize = 18f
                setTextColor(androidx.core.content.ContextCompat.getColor(this@ZapretProfilesActivity, R.color.text_primary))
            })
            fun button(label: Int, enabled: Boolean = true, action: () -> Unit) {
                column.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    setText(label)
                    isEnabled = enabled
                    setOnClickListener { action() }
                })
            }
            button(if (profile.id == state.activeId) R.string.zapret_is_selected else R.string.zapret_select, profile.id != state.activeId) {
                change { prefs -> prefs.store(prefs.load().select(profile.id)) }
            }
            button(R.string.zapret_copy, state.profiles.size < 32) {
                startActivity(Intent(this, ZapretProfileEditActivity::class.java).putExtra("profile_id", profile.id).putExtra("copy", true))
            }
            if (profile.id != ZapretProfile.DEFAULT.id) {
                button(R.string.zapret_edit) {
                    startActivity(Intent(this, ZapretProfileEditActivity::class.java).putExtra("profile_id", profile.id))
                }
                button(R.string.zapret_delete, profile.id != state.activeId) {
                    confirmDelete(profile)
                }
            }
            card.addView(column)
            container.addView(card)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
