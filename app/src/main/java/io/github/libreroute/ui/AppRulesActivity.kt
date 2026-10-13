package io.github.libreroute.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class AppRulesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) startActivity(Intent(this, TrafficRulesActivity::class.java).putExtra("section", "apps"))
        finish()
    }
}
