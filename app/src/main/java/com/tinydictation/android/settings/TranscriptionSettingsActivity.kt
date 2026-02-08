package com.tinydictation.android.settings

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import com.tinydictation.android.R
import com.tinydictation.android.transcription.models.ModelManager
import com.tinydictation.android.util.DebugLog
import com.tinydictation.android.util.PreferenceManager

/**
 * Activity for configuring transcription settings including provider, model,
 * API keys, language detection, and offline fallback.
 */
class TranscriptionSettingsActivity : AppCompatActivity() {

    private lateinit var preferenceManager: PreferenceManager
    private lateinit var modelManager: ModelManager

    // Provider/model views
    private lateinit var providerDropdown: AutoCompleteTextView
    private lateinit var modelDropdown: AutoCompleteTextView
    private lateinit var tilModel: TextInputLayout

    // Language views
    private lateinit var layoutAutoDetect: View
    private lateinit var autoDetectSwitch: MaterialSwitch

    // Fallback views
    private lateinit var cardFallback: MaterialCardView
    private lateinit var autoFallbackSwitch: MaterialSwitch
    private lateinit var layoutFallbackModel: View
    private lateinit var spinnerFallbackModel: Spinner
    private lateinit var tvFallbackModelNote: TextView

    private lateinit var saveButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transcription_settings)

        preferenceManager = PreferenceManager(this)
        modelManager = ModelManager(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        initViews()
        loadSettings()
        setupClickListeners()
    }

    private fun initViews() {
        providerDropdown = findViewById(R.id.actvProvider)
        modelDropdown = findViewById(R.id.actvModel)
        tilModel = findViewById(R.id.tilModel)

        layoutAutoDetect = findViewById(R.id.layoutAutoDetect)
        autoDetectSwitch = findViewById(R.id.switchAutoDetect)

        cardFallback = findViewById(R.id.cardFallback)
        autoFallbackSwitch = findViewById(R.id.switchAutoFallback)
        layoutFallbackModel = findViewById(R.id.layoutFallbackModel)
        spinnerFallbackModel = findViewById(R.id.spinnerFallbackModel)
        tvFallbackModelNote = findViewById(R.id.tvFallbackModelNote)

        saveButton = findViewById(R.id.btnSave)

        setupProviderDropdown()
    }

    private fun loadSettings() {
        // Load auto-detect
        autoDetectSwitch.isChecked = preferenceManager.getAutoDetectLanguage()

        // Load auto-fallback
        autoFallbackSwitch.isChecked = preferenceManager.getAutoFallback()
    }

    private fun setupClickListeners() {
        autoDetectSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setAutoDetectLanguage(isChecked)
            DebugLog.i("Auto-detect language: $isChecked")
        }

        autoFallbackSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setAutoFallback(isChecked)
            updateFallbackModelUI()
            DebugLog.i("Auto-fallback: $isChecked")
        }

        saveButton.setOnClickListener {
            saveSettings()
        }
    }

    private fun setupProviderDropdown() {
        val providers = buildProviderList()
        val displayNames = providers.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, displayNames)
        providerDropdown.setAdapter(adapter)

        // Set current selection
        val currentProvider = preferenceManager.getUnifiedProvider()
        val currentIndex = providers.indexOfFirst { it.second == currentProvider }
        if (currentIndex >= 0) {
            providerDropdown.setText(providers[currentIndex].first, false)
        } else {
            providerDropdown.setText("Deepgram", false)
        }

        updateProviderUI(currentProvider)

        providerDropdown.setOnItemClickListener { _, _, position, _ ->
            val selected = providers[position]
            if (selected.second.startsWith("header:")) {
                providerDropdown.setText(providers.find { it.second == preferenceManager.getUnifiedProvider() }?.first ?: "Deepgram", false)
                return@setOnItemClickListener
            }
            preferenceManager.setUnifiedProvider(selected.second)
            updateProviderUI(selected.second)
            DebugLog.i("Provider changed to: ${selected.second}")
        }
    }

    private fun buildProviderList(): List<Pair<String, String>> {
        val providers = mutableListOf<Pair<String, String>>()

        providers.add("--- Cloud ---" to "header:cloud")
        providers.add("Deepgram" to PreferenceManager.UNIFIED_DEEPGRAM)

        val hasVoskModel = modelManager.isModelAvailable("vosk", "en") || modelManager.isModelAvailable("vosk", "de")
        val hasWhisperModel = modelManager.isModelAvailable("sherpa-onnx", "whisper-tiny") ||
                modelManager.isModelAvailable("sherpa-onnx", "whisper-base") ||
                modelManager.isModelAvailable("sherpa-onnx", "whisper-small")

        if (hasVoskModel || hasWhisperModel) {
            providers.add("--- Offline ---" to "header:offline")
        }
        if (hasVoskModel) {
            providers.add("Vosk (Fast Offline)" to PreferenceManager.UNIFIED_VOSK)
        }
        if (hasWhisperModel) {
            providers.add("Whisper (High Quality)" to PreferenceManager.UNIFIED_WHISPER)
        }

        return providers
    }

    private fun updateProviderUI(provider: String) {
        val isCloud = provider.startsWith("cloud:")

        updateModelDropdown(provider)
        updateAutoDetectVisibility()

        cardFallback.visibility = if (isCloud) View.VISIBLE else View.GONE
        if (isCloud) {
            updateFallbackModelUI()
        }
    }

    private fun updateModelDropdown(provider: String) {
        val models = when (provider) {
            PreferenceManager.UNIFIED_DEEPGRAM -> listOf(
                "nova-3 (Best)" to PreferenceManager.DEEPGRAM_NOVA_3,
                "nova-2 (Default)" to PreferenceManager.DEEPGRAM_NOVA_2
            )
            PreferenceManager.UNIFIED_VOSK -> buildVoskModelList()
            PreferenceManager.UNIFIED_WHISPER -> buildWhisperModelList()
            else -> emptyList()
        }

        if (models.isEmpty()) {
            tilModel.visibility = View.GONE
            return
        }
        tilModel.visibility = View.VISIBLE

        val displayNames = models.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, displayNames)
        modelDropdown.setAdapter(adapter)

        val currentModel = getCurrentModelForProvider(provider)
        val currentIndex = models.indexOfFirst { it.second == currentModel }
        if (currentIndex >= 0) {
            modelDropdown.setText(displayNames[currentIndex], false)
        } else if (models.isNotEmpty()) {
            modelDropdown.setText(displayNames[0], false)
        }

        modelDropdown.setOnItemClickListener { _, _, position, _ ->
            val selectedModel = models[position].second
            saveModelForProvider(provider, selectedModel)
            updateAutoDetectVisibility()
            DebugLog.i("Model changed to: $selectedModel")
        }
    }

    private fun buildVoskModelList(): List<Pair<String, String>> {
        val models = mutableListOf<Pair<String, String>>()
        if (modelManager.isModelAvailable("vosk", "en")) {
            models.add("English ✓" to "en")
        }
        if (modelManager.isModelAvailable("vosk", "de")) {
            models.add("German ✓" to "de")
        }
        return models
    }

    private fun buildWhisperModelList(): List<Pair<String, String>> {
        val models = mutableListOf<Pair<String, String>>()
        if (modelManager.isModelAvailable("sherpa-onnx", "whisper-tiny")) {
            models.add("Tiny ✓" to PreferenceManager.WHISPER_TINY)
        }
        if (modelManager.isModelAvailable("sherpa-onnx", "whisper-base")) {
            models.add("Base ✓" to PreferenceManager.WHISPER_BASE)
        }
        if (modelManager.isModelAvailable("sherpa-onnx", "whisper-small")) {
            models.add("Small ✓" to PreferenceManager.WHISPER_SMALL)
        }
        return models
    }

    private fun getCurrentModelForProvider(provider: String): String {
        return when (provider) {
            PreferenceManager.UNIFIED_DEEPGRAM -> preferenceManager.getDeepgramModel()
            PreferenceManager.UNIFIED_VOSK -> preferenceManager.getLanguage()
            PreferenceManager.UNIFIED_WHISPER -> preferenceManager.getWhisperModel()
            else -> ""
        }
    }

    private fun saveModelForProvider(provider: String, model: String) {
        when (provider) {
            PreferenceManager.UNIFIED_DEEPGRAM -> preferenceManager.setDeepgramModel(model)
            PreferenceManager.UNIFIED_VOSK -> preferenceManager.setLanguage(model)
            PreferenceManager.UNIFIED_WHISPER -> preferenceManager.setWhisperModel(model)
        }
    }

    private fun updateAutoDetectVisibility() {
        val supportsAutoDetect = preferenceManager.currentProviderSupportsAutoDetect()
        layoutAutoDetect.visibility = if (supportsAutoDetect) View.VISIBLE else View.GONE
    }

    private data class FallbackModelItem(
        val id: String,
        val displayName: String,
        val isWhisper: Boolean
    )

    private fun updateFallbackModelUI() {
        if (!autoFallbackSwitch.isChecked) {
            layoutFallbackModel.visibility = View.GONE
            return
        }

        val availableModels = mutableListOf<FallbackModelItem>()

        if (modelManager.isModelAvailable("vosk", "en")) {
            availableModels.add(FallbackModelItem(PreferenceManager.FALLBACK_VOSK_EN, "Vosk English", false))
        }
        if (modelManager.isModelAvailable("vosk", "de")) {
            availableModels.add(FallbackModelItem(PreferenceManager.FALLBACK_VOSK_DE, "Vosk German", false))
        }
        if (modelManager.isModelAvailable("sherpa-onnx", "whisper-tiny")) {
            availableModels.add(FallbackModelItem(PreferenceManager.FALLBACK_WHISPER_TINY, "Whisper Tiny", true))
        }
        if (modelManager.isModelAvailable("sherpa-onnx", "whisper-base")) {
            availableModels.add(FallbackModelItem(PreferenceManager.FALLBACK_WHISPER_BASE, "Whisper Base", true))
        }
        if (modelManager.isModelAvailable("sherpa-onnx", "whisper-small")) {
            availableModels.add(FallbackModelItem(PreferenceManager.FALLBACK_WHISPER_SMALL, "Whisper Small", true))
        }

        if (availableModels.isEmpty()) {
            layoutFallbackModel.visibility = View.VISIBLE
            spinnerFallbackModel.visibility = View.GONE
            tvFallbackModelNote.text = "No offline models downloaded"
            tvFallbackModelNote.visibility = View.VISIBLE
            return
        }

        layoutFallbackModel.visibility = View.VISIBLE
        spinnerFallbackModel.visibility = View.VISIBLE

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, availableModels.map { it.displayName })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerFallbackModel.adapter = adapter

        val currentFallback = preferenceManager.getFallbackModel()
        val currentIndex = availableModels.indexOfFirst { it.id == currentFallback }
        if (currentIndex >= 0) {
            spinnerFallbackModel.setSelection(currentIndex)
        } else {
            spinnerFallbackModel.setSelection(0)
            preferenceManager.setFallbackModel(availableModels[0].id)
        }

        val selectedModel = if (currentIndex >= 0) availableModels[currentIndex] else availableModels[0]
        tvFallbackModelNote.visibility = if (selectedModel.isWhisper) View.VISIBLE else View.GONE

        spinnerFallbackModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = availableModels[position]
                preferenceManager.setFallbackModel(selected.id)
                tvFallbackModelNote.visibility = if (selected.isWhisper) View.VISIBLE else View.GONE
                DebugLog.i("Fallback model changed to: ${selected.displayName}")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun saveSettings() {
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
