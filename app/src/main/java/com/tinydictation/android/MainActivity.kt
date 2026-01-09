package com.tinydictation.android

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Legacy MainActivity - redirects to SettingsActivity.
 * Kept for backward compatibility (shortcuts, deep links, etc.)
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Redirect to consolidated SettingsActivity
        startActivity(Intent(this, SettingsActivity::class.java))
        finish()
    }
}
