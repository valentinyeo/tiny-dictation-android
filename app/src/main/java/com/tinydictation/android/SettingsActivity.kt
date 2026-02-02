package com.tinydictation.android

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tinydictation.android.settings.AboutActivity
import com.tinydictation.android.settings.AppearanceSettingsActivity
import com.tinydictation.android.settings.AudioSettingsActivity
import com.tinydictation.android.settings.OfflineModelsActivity
import com.tinydictation.android.settings.PermissionsActivity
import com.tinydictation.android.settings.TextCorrectionSettingsActivity
import com.tinydictation.android.settings.TranscriptionSettingsActivity
import com.tinydictation.android.settings.VoiceCommandsActivity

/**
 * Main Settings screen displaying a list of settings categories.
 * Each category opens a dedicated sub-screen.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_main)

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerSettings)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = SettingsCategoryAdapter(getSettingsCategories()) { category ->
            navigateToCategory(category)
        }

        // Set version text
        val versionText = findViewById<TextView>(R.id.versionText)
        try {
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName
            versionText.text = "v$versionName"
        } catch (e: Exception) {
            versionText.visibility = View.GONE
        }
    }

    private fun getSettingsCategories(): List<SettingsCategory> {
        return listOf(
            SettingsCategory(
                id = "transcription",
                icon = R.drawable.ic_mic,
                title = "Transcription",
                subtitle = "Engine, language, model"
            ),
            SettingsCategory(
                id = "voice_commands",
                icon = R.drawable.ic_voice_command,
                title = "Voice Commands",
                subtitle = "Hands-free text control"
            ),
            SettingsCategory(
                id = "audio",
                icon = R.drawable.ic_audio,
                title = "Audio",
                subtitle = "Microphone, silence detection"
            ),
            SettingsCategory(
                id = "offline_models",
                icon = R.drawable.ic_download,
                title = "Offline Models",
                subtitle = "Download speech models"
            ),
            SettingsCategory(
                id = "appearance",
                icon = R.drawable.ic_palette,
                title = "Appearance",
                subtitle = "Keyboard theme"
            ),
            SettingsCategory(
                id = "text_correction",
                icon = R.drawable.ic_spellcheck,
                title = "Text Correction",
                subtitle = "Auto-fix typos and spacing"
            ),
            SettingsCategory(
                id = "permissions",
                icon = R.drawable.ic_shield,
                title = "Permissions",
                subtitle = "Microphone, accessibility"
            ),
            SettingsCategory(
                id = "history",
                icon = R.drawable.ic_history,
                title = "History",
                subtitle = "View past transcriptions"
            ),
            SettingsCategory(
                id = "dictionary",
                icon = R.drawable.ic_book,
                title = "Word Dictionary",
                subtitle = "Custom word replacements"
            ),
            SettingsCategory(
                id = "about",
                icon = R.drawable.ic_info,
                title = "About",
                subtitle = "Version, changelog, debug"
            )
        )
    }

    private fun navigateToCategory(category: SettingsCategory) {
        val intent = when (category.id) {
            "transcription" -> Intent(this, TranscriptionSettingsActivity::class.java)
            "voice_commands" -> Intent(this, VoiceCommandsActivity::class.java)
            "audio" -> Intent(this, AudioSettingsActivity::class.java)
            "offline_models" -> Intent(this, OfflineModelsActivity::class.java)
            "appearance" -> Intent(this, AppearanceSettingsActivity::class.java)
            "text_correction" -> Intent(this, TextCorrectionSettingsActivity::class.java)
            "permissions" -> Intent(this, PermissionsActivity::class.java)
            "history" -> Intent(this, HistoryActivity::class.java)
            "dictionary" -> Intent(this, DictionaryActivity::class.java)
            "about" -> Intent(this, AboutActivity::class.java)
            else -> return
        }
        startActivity(intent)
    }

    data class SettingsCategory(
        val id: String,
        val icon: Int,
        val title: String,
        val subtitle: String
    )

    class SettingsCategoryAdapter(
        private val categories: List<SettingsCategory>,
        private val onItemClick: (SettingsCategory) -> Unit
    ) : RecyclerView.Adapter<SettingsCategoryAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.ivIcon)
            val title: TextView = view.findViewById(R.id.tvTitle)
            val subtitle: TextView = view.findViewById(R.id.tvSubtitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_settings_category, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val category = categories[position]
            holder.icon.setImageResource(category.icon)
            holder.title.text = category.title
            holder.subtitle.text = category.subtitle
            holder.itemView.setOnClickListener { onItemClick(category) }
        }

        override fun getItemCount() = categories.size
    }
}
