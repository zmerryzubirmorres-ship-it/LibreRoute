package io.github.libreroute.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import io.github.libreroute.R
import io.github.libreroute.data.ZapretProfile
import io.github.libreroute.util.ThemePreferences
import io.github.libreroute.util.ZapretProfilePreferences
import java.util.UUID

class ZapretProfileEditActivity : AppCompatActivity() {
    private lateinit var profileId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemePreferences(this).applyTheme()
        setContentView(R.layout.activity_zapret_profile_edit)
        findViewById<android.view.View>(R.id.btn_back).setOnClickListener { finish() }
        val name = findViewById<TextInputEditText>(R.id.zapret_name)
        val split = findViewById<Spinner>(R.id.zapret_split)
        split.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resources.getStringArray(R.array.zapret_split_choices))
        val sourceId = intent.getStringExtra("profile_id")
        val copy = intent.getBooleanExtra("copy", false)
        findViewById<android.view.View>(R.id.zapret_name_youtube).setOnClickListener { name.setText("YouTube") }
        findViewById<android.view.View>(R.id.zapret_name_instagram).setOnClickListener { name.setText("Instagram") }
        profileId = savedInstanceState?.getString("profile_id") ?: if (sourceId == null || copy) UUID.randomUUID().toString() else sourceId
        if (savedInstanceState == null) {
            val source = runCatching {
                if (sourceId == null) ZapretProfile.DEFAULT
                else ZapretProfilePreferences(this).load().profiles.first { it.id == sourceId }
            }.getOrElse { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show(); finish(); return }
            name.setText(if (sourceId == null) "" else if (copy) getString(R.string.zapret_copy_name, source.name).take(80) else source.name)
            split.setSelection(ZapretProfile.SPLITS.indexOf(source.split))
            switch(R.id.zapret_tls).isChecked = source.tlsRecord
            switch(R.id.zapret_method).isChecked = source.methodEol
            switch(R.id.zapret_host).isChecked = source.hostCase
            switch(R.id.zapret_disorder).isChecked = source.disorder
            switch(R.id.zapret_quic).isChecked = source.blockQuic
        }
        findViewById<android.view.View>(R.id.zapret_save).setOnClickListener {
            val profile = ZapretProfile(profileId, name.text.toString().trim(), ZapretProfile.SPLITS[split.selectedItemPosition],
                switch(R.id.zapret_tls).isChecked, switch(R.id.zapret_method).isChecked,
                switch(R.id.zapret_host).isChecked, switch(R.id.zapret_disorder).isChecked, switch(R.id.zapret_quic).isChecked)
            runCatching {
                val prefs = ZapretProfilePreferences(this)
                prefs.store(prefs.load().save(profile))
            }.fold(onSuccess = {
                setResult(RESULT_OK, android.content.Intent().putExtra("profile_id", profile.id))
                finish()
            }, onFailure = { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() })
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("profile_id", profileId)
        super.onSaveInstanceState(outState)
    }

    private fun switch(id: Int) = findViewById<MaterialSwitch>(id)
}
