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
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.tinydictation.android.R
import com.tinydictation.android.transcription.models.ModelManager
import com.tinydictation.android.util.DebugLog
import com.tinydictation.android.util.PreferenceManager
import kotlinx.coroutines.launch

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

    // API key views
    private lateinit var cardApiKeys: MaterialCardView
    private lateinit var tilApiKey: TextInputLayout
    private lateinit var apiKeyInput: TextInputEditText
    private lateinit var tilGroqApiKey: TextInputLayout
    private lateinit var groqApiKeyInput: TextInputEditText
    private lateinit var tilOpenAIApiKey: TextInputLayout
    private lateinit var openAIApiKeyInput: TextInputEditText
    private lateinit var tilAssemblyAIApiKey: TextInputLayout
    private lateinit var assemblyAIApiKeyInput: TextInputEditText
    private lateinit var tilGeminiApiKey: TextInputLayout
    private lateinit var geminiApiKeyInput: TextInputEditText

    // Language views
    private lateinit var layoutAutoDetect: View
    private lateinit var autoDetectSwitch: MaterialSwitch

    // Fallback views
    private lateinit var cardFallback: MaterialCardView
    private lateinit var autoFallbackSwitch: MaterialSwitch
    private lateinit var layoutFallbackModel: View
    private lateinit var spinnerFallbackModel: Spinner
    private lateinit var tvFallbackModelNote: TextView

    // Prediction views
    private lateinit var predictionEngineDropdown: AutoCompleteTextView
    private lateinit var tilPredictionGroqApiKey: TextInputLayout
    private lateinit var predictionGroqApiKeyInput: TextInputEditText

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

        cardApiKeys = findViewById(R.id.cardApiKeys)
        tilApiKey = findViewById(R.id.tilApiKey)
        apiKeyInput = findViewById(R.id.etApiKey)
        tilGroqApiKey = findViewById(R.id.tilGroqApiKey)
        groqApiKeyInput = findViewById(R.id.etGroqApiKey)
        tilOpenAIApiKey = findViewById(R.id.tilOpenAIApiKey)
        openAIApiKeyInput = findViewById(R.id.etOpenAIApiKey)
        tilAssemblyAIApiKey = findViewById(R.id.tilAssemblyAIApiKey)
        assemblyAIApiKeyInput = findViewById(R.id.etAssemblyAIApiKey)
        tilGeminiApiKey = findViewById(R.id.tilGeminiApiKey)
        geminiApiKeyInput = findViewById(R.id.etGeminiApiKey)

        layoutAutoDetect = findViewById(R.id.layoutAutoDetect)
        autoDetectSwitch = findViewById(R.id.switchAutoDetect)

        cardFallback = findViewById(R.id.cardFallback)
        autoFallbackSwitch = findViewById(R.id.switchAutoFallback)
        layoutFallbackModel = findViewById(R.id.layoutFallbackModel)
        spinnerFallbackModel = findViewById(R.id.spinnerFallbackModel)
        tvFallbackModelNote = findViewById(R.id.tvFallbackModelNote)

        saveButton = findViewById(R.id.btnSave)

        // Prediction views
        predictionEngineDropdown = findViewById(R.id.actvPredictionEngine)
        tilPredictionGroqApiKey = findViewById(R.id.tilPredictionGroqApiKey)
        predictionGroqApiKeyInput = findViewById(R.id.etPredictionGroqApiKey)

        setupProviderDropdown()
        setupPredictionEngineDropdown()
    }

    private fun loadSettings() {
        // Load API keys
        preferenceManager.getApiKey()?.let { apiKeyInput.setText(it) }
        preferenceManager.getGroqApiKey()?.let { groqApiKeyInput.setText(it) }
        preferenceManager.getOpenAIApiKey()?.let { openAIApiKeyInput.setText(it) }
        preferenceManager.getAssemblyAIApiKey()?.let { assemblyAIApiKeyInput.setText(it) }
        preferenceManager.getGeminiApiKey()?.let { geminiApiKeyInput.setText(it) }

        // Load prediction Groq key (same as transcription Groq key)
        preferenceManager.getGroqApiKey()?.let { predictionGroqApiKeyInput.setText(it) }

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

    private fun setupPredictionEngineDropdown() {
        val engines = listOf(
            "N-gram (Offline)" to PreferenceManager.PREDICTION_ENGINE_NGRAM,
            "Groq (Cloud AI)" to PreferenceManager.PREDICTION_ENGINE_GROQ
        )
        val displayNames = engines.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, displayNames)
        predictionEngineDropdown.setAdapter(adapter)

        // Set current selection
        val currentEngine = preferenceManager.getPredictionEngine()
        val currentIndex = engines.indexOfFirst { it.second == currentEngine }
        if (currentIndex >= 0) {
            predictionEngineDropdown.setText(engines[currentIndex].first, false)
        } else {
            predictionEngineDropdown.setText(engines[0].first, false)
        }

        // Show/hide Groq API key based on current selection
        updatePredictionApiKeyVisibility(currentEngine)

        predictionEngineDropdown.setOnItemClickListener { _, _, position, _ ->
            val selected = engines[position]
            preferenceManager.setPredictionEngine(selected.second)
            updatePredictionApiKeyVisibility(selected.second)
            DebugLog.i("Prediction engine changed to: ${selected.second}")
        }
    }

    private fun updatePredictionApiKeyVisibility(engine: String) {
        tilPredictionGroqApiKey.visibility = if (engine == PreferenceManager.PREDICTION_ENGINE_GROQ) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun buildProviderList(): List<Pair<String, String>> {
        val providers = mutableListOf<Pair<String, String>>()

        providers.add("--- Cloud ---" to "header:cloud")
        providers.add("Deepgram" to PreferenceManager.UNIFIED_DEEPGRAM)
        providers.add("Groq (Whisper)" to PreferenceManager.UNIFIED_GROQ)
        providers.add("OpenAI" to PreferenceManager.UNIFIED_OPENAI)
        providers.add("AssemblyAI" to PreferenceManager.UNIFIED_ASSEMBLYAI)
        providers.add("Gemini (Google)" to PreferenceManager.UNIFIED_GEMINI)

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
        updateApiKeyVisibility(provider)
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
                "nova-2 (Default)" to PreferenceManager.DEEPGRAM_NOVA_2,
                "enhanced" to PreferenceManager.DEEPGRAM_ENHANCED,
                "base (Cheapest)" to PreferenceManager.DEEPGRAM_BASE
            )
            PreferenceManager.UNIFIED_GROQ -> listOf(
                "whisper-large-v3 (Best)" to PreferenceManager.GROQ_WHISPER_LARGE_V3,
                "whisper-large-v3-turbo (Faster)" to PreferenceManager.GROQ_WHISPER_LARGE_V3_TURBO,
                "distil-whisper (English only)" to PreferenceManager.GROQ_DISTIL_WHISPER
            )
            PreferenceManager.UNIFIED_OPENAI -> listOf(
                "gpt-4o-transcribe (Best)" to PreferenceManager.OPENAI_GPT4O_TRANSCRIBE,
                "gpt-4o-mini-transcribe (Cheaper)" to PreferenceManager.OPENAI_GPT4O_MINI,
                "whisper-1 (Original)" to PreferenceManager.OPENAI_WHISPER_1
            )
            PreferenceManager.UNIFIED_ASSEMBLYAI,
            PreferenceManager.UNIFIED_GEMINI -> emptyList()
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
            PreferenceManager.UNIFIED_GROQ -> preferenceManager.getGroqModel()
            PreferenceManager.UNIFIED_OPENAI -> preferenceManager.getOpenAIModel()
            PreferenceManager.UNIFIED_VOSK -> preferenceManager.getLanguage()
            PreferenceManager.UNIFIED_WHISPER -> preferenceManager.getWhisperModel()
            else -> ""
        }
    }

    private fun saveModelForProvider(provider: String, model: String) {
        when (provider) {
            PreferenceManager.UNIFIED_DEEPGRAM -> preferenceManager.setDeepgramModel(model)
            PreferenceManager.UNIFIED_GROQ -> preferenceManager.setGroqModel(model)
            PreferenceManager.UNIFIED_OPENAI -> preferenceManager.setOpenAIModel(model)
            PreferenceManager.UNIFIED_VOSK -> preferenceManager.setLanguage(model)
            PreferenceManager.UNIFIED_WHISPER -> preferenceManager.setWhisperModel(model)
        }
    }

    private fun updateApiKeyVisibility(provider: String) {
        tilApiKey.visibility = if (provider == PreferenceManager.UNIFIED_DEEPGRAM) View.VISIBLE else View.GONE
        tilGroqApiKey.visibility = if (provider == PreferenceManager.UNIFIED_GROQ) View.VISIBLE else View.GONE
        tilOpenAIApiKey.visibility = if (provider == PreferenceManager.UNIFIED_OPENAI) View.VISIBLE else View.GONE
        tilAssemblyAIApiKey.visibility = if (provider == PreferenceManager.UNIFIED_ASSEMBLYAI) View.VISIBLE else View.GONE
        tilGeminiApiKey.visibility = if (provider == PreferenceManager.UNIFIED_GEMINI) View.VISIBLE else View.GONE

        // Hide entire card if offline provider
        val isCloud = provider.startsWith("cloud:")
        cardApiKeys.visibility = if (isCloud) View.VISIBLE else View.GONE
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
        val apiKey = apiKeyInput.text?.toString()?.trim()
        val groqApiKey = groqApiKeyInput.text?.toString()?.trim()
        val openAIApiKey = openAIApiKeyInput.text?.toString()?.trim()
        val assemblyAIApiKey = assemblyAIApiKeyInput.text?.toString()?.trim()
        val geminiApiKey = geminiApiKeyInput.text?.toString()?.trim()
        val predictionGroqApiKey = predictionGroqApiKeyInput.text?.toString()?.trim()

        val unifiedProvider = preferenceManager.getUnifiedProvider()
        val predictionEngine = preferenceManager.getPredictionEngine()

        // Validate transcription API keys
        if (unifiedProvider.startsWith("cloud:")) {
            when (unifiedProvider) {
                PreferenceManager.UNIFIED_DEEPGRAM -> {
                    if (apiKey.isNullOrEmpty()) {
                        apiKeyInput.error = "API key is required for Deepgram"
                        return
                    }
                }
                PreferenceManager.UNIFIED_GROQ -> {
                    if (groqApiKey.isNullOrEmpty()) {
                        groqApiKeyInput.error = "API key is required for Groq"
                        return
                    }
                }
                PreferenceManager.UNIFIED_OPENAI -> {
                    if (openAIApiKey.isNullOrEmpty()) {
                        openAIApiKeyInput.error = "API key is required for OpenAI"
                        return
                    }
                }
                PreferenceManager.UNIFIED_ASSEMBLYAI -> {
                    if (assemblyAIApiKey.isNullOrEmpty()) {
                        assemblyAIApiKeyInput.error = "API key is required for AssemblyAI"
                        return
                    }
                }
                PreferenceManager.UNIFIED_GEMINI -> {
                    if (geminiApiKey.isNullOrEmpty()) {
                        geminiApiKeyInput.error = "API key is required for Gemini"
                        return
                    }
                }
            }
        }

        // Validate prediction API key if Groq predictions selected
        if (predictionEngine == PreferenceManager.PREDICTION_ENGINE_GROQ) {
            if (predictionGroqApiKey.isNullOrEmpty()) {
                predictionGroqApiKeyInput.error = "API key is required for Groq predictions"
                return
            }
        }

        // Save API keys
        if (!apiKey.isNullOrEmpty()) preferenceManager.setApiKey(apiKey)
        if (!openAIApiKey.isNullOrEmpty()) preferenceManager.setOpenAIApiKey(openAIApiKey)
        if (!assemblyAIApiKey.isNullOrEmpty()) preferenceManager.setAssemblyAIApiKey(assemblyAIApiKey)
        if (!geminiApiKey.isNullOrEmpty()) preferenceManager.setGeminiApiKey(geminiApiKey)

        // Save Groq API key - use prediction key if set, otherwise transcription key
        // Both fields share the same underlying preference
        val effectiveGroqKey = predictionGroqApiKey?.takeIf { it.isNotEmpty() } ?: groqApiKey
        if (!effectiveGroqKey.isNullOrEmpty()) {
            preferenceManager.setGroqApiKey(effectiveGroqKey)
        }

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
