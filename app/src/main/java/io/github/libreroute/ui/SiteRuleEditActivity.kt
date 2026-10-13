package io.github.libreroute.ui

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import io.github.libreroute.R
import io.github.libreroute.data.SiteRule
import io.github.libreroute.util.SiteRoutingPreferences
import io.github.libreroute.util.ThemePreferences
import io.github.libreroute.util.ZapretProfilePreferences

class SiteRuleEditActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemePreferences(this).applyTheme()
        setContentView(R.layout.activity_site_rule_edit)
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        val previousDomain = intent.getStringExtra("domain")
        val profiles = runCatching { ZapretProfilePreferences(this).load() }.getOrElse {
            Toast.makeText(this, it.message, Toast.LENGTH_LONG).show(); finish(); return
        }
        val domain = findViewById<TextInputEditText>(R.id.site_domain)
        val action = findViewById<Spinner>(R.id.site_action)
        val profile = findViewById<Spinner>(R.id.site_profile)
        action.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resources.getStringArray(R.array.site_actions))
        profile.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, profiles.profiles.map { it.name })
        action.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                findViewById<View>(R.id.site_profile_container).visibility = if (SiteRule.ACTIONS[position] == "zapret") View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        if (savedInstanceState == null && previousDomain != null) {
            val rule = runCatching { SiteRoutingPreferences(this).load().rules.first { it.domain == previousDomain } }.getOrElse {
                Toast.makeText(this, it.message, Toast.LENGTH_LONG).show(); finish(); return
            }
            domain.setText(rule.domain)
            action.setSelection(SiteRule.ACTIONS.indexOf(rule.action))
            profile.setSelection(profiles.profiles.indexOfFirst { it.id == rule.profile }.coerceAtLeast(0))
        } else if (savedInstanceState == null) action.setSelection(SiteRule.ACTIONS.indexOf("tunnel"))
        findViewById<View>(R.id.site_save).setOnClickListener {
            val selectedAction = SiteRule.ACTIONS[action.selectedItemPosition]
            val rule = SiteRule(SiteRule.clean(domain.text.toString()), selectedAction,
                if (selectedAction == "zapret") profiles.profiles[profile.selectedItemPosition].id else "")
            runCatching {
                val prefs = SiteRoutingPreferences(this)
                val currentProfiles = ZapretProfilePreferences(this).load()
                prefs.store(prefs.load().save(rule, previousDomain, currentProfiles), currentProfiles)
            }.fold(onSuccess = { finish() }, onFailure = { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() })
        }
    }
}
