package com.tinydictation.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tinydictation.android.settings.AboutActivity
import com.tinydictation.android.settings.ApiKeysSettingsActivity
import com.tinydictation.android.settings.AppearanceSettingsActivity
import com.tinydictation.android.settings.AudioSettingsActivity
import com.tinydictation.android.settings.DeepgramFeaturesSettingsActivity
import com.tinydictation.android.settings.OfflineModelsActivity
import com.tinydictation.android.settings.PermissionsActivity
import com.tinydictation.android.settings.TranscriptionSettingsActivity
import com.tinydictation.android.settings.UpdateActivity
import com.tinydictation.android.settings.VoiceCommandsActivity

class SettingsActivity : AppCompatActivity() {

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) (application as TinyDictationApp).updateManager.check(force = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_main)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerSettings)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = SettingsCategoryAdapter(getSettingsCategories()) { category ->
            navigateToCategory(category)
        }

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
                id = "api_keys",
                icon = R.drawable.ic_key,
                title = "API Keys",
                subtitle = "Manage cloud service keys"
            ),
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
                id = "deepgram_features",
                icon = R.drawable.ic_mic,
                title = "Corrections",
                subtitle = "Dictation, typing, and AI corrections"
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
                id = "update",
                icon = R.drawable.ic_download,
                title = "Download and update",
                subtitle = "Check for new versions"
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
            "api_keys" -> Intent(this, ApiKeysSettingsActivity::class.java)
            "transcription" -> Intent(this, TranscriptionSettingsActivity::class.java)
            "voice_commands" -> Intent(this, VoiceCommandsActivity::class.java)
            "audio" -> Intent(this, AudioSettingsActivity::class.java)
            "offline_models" -> Intent(this, OfflineModelsActivity::class.java)
            "appearance" -> Intent(this, AppearanceSettingsActivity::class.java)
            "deepgram_features" -> Intent(this, DeepgramFeaturesSettingsActivity::class.java)
            "permissions" -> Intent(this, PermissionsActivity::class.java)
            "history" -> Intent(this, HistoryActivity::class.java)
            "update" -> Intent(this, UpdateActivity::class.java)
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
