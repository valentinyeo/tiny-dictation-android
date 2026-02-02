package com.tinydictation.android.settings

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.tinydictation.android.R
import com.tinydictation.android.correction.CorrectionManager
import com.tinydictation.android.correction.SentenceProvider
import com.tinydictation.android.util.DebugLog
import com.tinydictation.android.util.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Activity for configuring the two-stage text correction system.
 */
class TextCorrectionSettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TextCorrectionSettings"
    }

    private lateinit var preferenceManager: PreferenceManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // UI Elements
    private lateinit var switchCorrectionEnabled: MaterialSwitch
    private lateinit var switchWordLevel: MaterialSwitch
    private lateinit var switchSplitStuck: MaterialSwitch
    private lateinit var switchSentenceLevel: MaterialSwitch
    private lateinit var actvProvider: AutoCompleteTextView
    private lateinit var switchTriggerPunctuation: MaterialSwitch
    private lateinit var switchTriggerPause: MaterialSwitch
    private lateinit var tvPauseDuration: TextView
    private lateinit var switchTriggerDoubleSpace: MaterialSwitch
    private lateinit var switchHighlight: MaterialSwitch
    private lateinit var switchImportContacts: MaterialSwitch
    private lateinit var switchLearn: MaterialSwitch
    private lateinit var btnResetLearned: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_correction_settings)

        preferenceManager = PreferenceManager(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        initViews()
        loadSettings()
        setupClickListeners()
    }

    private fun initViews() {
        switchCorrectionEnabled = findViewById(R.id.switchCorrectionEnabled)
        switchWordLevel = findViewById(R.id.switchWordLevel)
        switchSplitStuck = findViewById(R.id.switchSplitStuck)
        switchSentenceLevel = findViewById(R.id.switchSentenceLevel)
        actvProvider = findViewById(R.id.actvProvider)
        switchTriggerPunctuation = findViewById(R.id.switchTriggerPunctuation)
        switchTriggerPause = findViewById(R.id.switchTriggerPause)
        tvPauseDuration = findViewById(R.id.tvPauseDuration)
        switchTriggerDoubleSpace = findViewById(R.id.switchTriggerDoubleSpace)
        switchHighlight = findViewById(R.id.switchHighlight)
        switchImportContacts = findViewById(R.id.switchImportContacts)
        switchLearn = findViewById(R.id.switchLearn)
        btnResetLearned = findViewById(R.id.btnResetLearned)

        setupProviderDropdown()
    }

    private fun loadSettings() {
        switchCorrectionEnabled.isChecked = preferenceManager.getCorrectionEnabled()
        switchWordLevel.isChecked = preferenceManager.getCorrectionWordLevelEnabled()
        switchSplitStuck.isChecked = preferenceManager.getCorrectionSplitStuckWords()
        switchSentenceLevel.isChecked = preferenceManager.getCorrectionSentenceLevelEnabled()
        switchTriggerPunctuation.isChecked = preferenceManager.getCorrectionTriggerOnPunctuation()
        switchTriggerPause.isChecked = preferenceManager.getCorrectionTriggerOnPause()
        switchTriggerDoubleSpace.isChecked = preferenceManager.getCorrectionTriggerOnDoubleSpace()
        switchHighlight.isChecked = preferenceManager.getCorrectionHighlightEnabled()
        switchImportContacts.isChecked = preferenceManager.getCorrectionImportContacts()
        switchLearn.isChecked = preferenceManager.getCorrectionLearnEnabled()

        // Update pause duration text
        val pauseDuration = preferenceManager.getCorrectionPauseDuration()
        tvPauseDuration.text = "Trigger after ${pauseDuration / 1000} seconds of no typing"

        // Update enabled states based on master toggle
        updateEnabledStates()
    }

    private fun setupClickListeners() {
        switchCorrectionEnabled.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setCorrectionEnabled(isChecked)
            DebugLog.i("$TAG: Correction enabled changed to: $isChecked")
            updateEnabledStates()
        }

        switchWordLevel.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setCorrectionWordLevelEnabled(isChecked)
            DebugLog.i("$TAG: Word-level correction changed to: $isChecked")
        }

        switchSplitStuck.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setCorrectionSplitStuckWords(isChecked)
            DebugLog.i("$TAG: Split stuck words changed to: $isChecked")
        }

        switchSentenceLevel.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setCorrectionSentenceLevelEnabled(isChecked)
            DebugLog.i("$TAG: Sentence-level correction changed to: $isChecked")
        }

        switchTriggerPunctuation.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setCorrectionTriggerOnPunctuation(isChecked)
            DebugLog.i("$TAG: Trigger on punctuation changed to: $isChecked")
        }

        switchTriggerPause.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setCorrectionTriggerOnPause(isChecked)
            DebugLog.i("$TAG: Trigger on pause changed to: $isChecked")
        }

        switchTriggerDoubleSpace.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setCorrectionTriggerOnDoubleSpace(isChecked)
            DebugLog.i("$TAG: Trigger on double-space changed to: $isChecked")
        }

        switchHighlight.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setCorrectionHighlightEnabled(isChecked)
            DebugLog.i("$TAG: Highlight corrections changed to: $isChecked")
        }

        switchImportContacts.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setCorrectionImportContacts(isChecked)
            DebugLog.i("$TAG: Import contacts changed to: $isChecked")
        }

        switchLearn.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setCorrectionLearnEnabled(isChecked)
            DebugLog.i("$TAG: Learn from corrections changed to: $isChecked")
        }

        btnResetLearned.setOnClickListener {
            showResetLearnedDialog()
        }
    }

    private fun setupProviderDropdown() {
        val providers = listOf(
            "Groq Fast (llama-3.1-8b)" to PreferenceManager.CORRECTION_PROVIDER_GROQ_FAST,
            "Groq Smart (llama-3.3-70b)" to PreferenceManager.CORRECTION_PROVIDER_GROQ_SMART,
            "Claude Haiku" to PreferenceManager.CORRECTION_PROVIDER_CLAUDE_HAIKU
        )

        val displayNames = providers.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, displayNames)
        actvProvider.setAdapter(adapter)

        val currentProvider = preferenceManager.getCorrectionProvider()
        val currentIndex = providers.indexOfFirst { it.second == currentProvider }
        if (currentIndex >= 0) {
            actvProvider.setText(displayNames[currentIndex], false)
        } else {
            actvProvider.setText(displayNames[0], false)
        }

        actvProvider.setOnItemClickListener { _, _, position, _ ->
            val selectedValue = providers[position].second
            preferenceManager.setCorrectionProvider(selectedValue)
            DebugLog.i("$TAG: Provider changed to: $selectedValue")
        }
    }

    private fun updateEnabledStates() {
        val isEnabled = switchCorrectionEnabled.isChecked

        switchWordLevel.isEnabled = isEnabled
        switchSplitStuck.isEnabled = isEnabled && switchWordLevel.isChecked
        switchSentenceLevel.isEnabled = isEnabled
        actvProvider.isEnabled = isEnabled && switchSentenceLevel.isChecked
        switchTriggerPunctuation.isEnabled = isEnabled && switchSentenceLevel.isChecked
        switchTriggerPause.isEnabled = isEnabled && switchSentenceLevel.isChecked
        switchTriggerDoubleSpace.isEnabled = isEnabled && switchSentenceLevel.isChecked
        switchHighlight.isEnabled = isEnabled
        switchImportContacts.isEnabled = isEnabled
        switchLearn.isEnabled = isEnabled
        btnResetLearned.isEnabled = isEnabled
    }

    private fun showResetLearnedDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Reset Learned Words")
            .setMessage("This will clear all words you've added to the dictionary and all learned corrections. This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Reset") { _, _ ->
                resetLearnedData()
            }
            .show()
    }

    private fun resetLearnedData() {
        scope.launch {
            try {
                val correctionManager = CorrectionManager(this@TextCorrectionSettingsActivity)
                correctionManager.initialize()
                correctionManager.resetLearnedData()
                correctionManager.release()

                DebugLog.i("$TAG: Learned data reset successfully")

                // Show confirmation
                MaterialAlertDialogBuilder(this@TextCorrectionSettingsActivity)
                    .setTitle("Reset Complete")
                    .setMessage("All learned words have been cleared.")
                    .setPositiveButton("OK", null)
                    .show()
            } catch (e: Exception) {
                DebugLog.e("$TAG: Failed to reset learned data - ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
