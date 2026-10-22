package com.tinydictation.android.settings

import android.os.Bundle
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.tinydictation.android.R
import com.tinydictation.android.util.DebugLog
import com.tinydictation.android.util.PreferenceManager

/**
 * Activity for configuring Deepgram-specific features:
 * keywords, find & replace rules, numerals, and dictation mode.
 */
class DeepgramFeaturesSettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DeepgramFeaturesSettings"
    }

    private lateinit var preferenceManager: PreferenceManager

    // UI Elements
    private lateinit var tvKeywordsHeader: TextView
    private lateinit var tvReplacementsHeader: TextView
    private lateinit var etKeyword: TextInputEditText
    private lateinit var btnAddKeyword: MaterialButton
    private lateinit var layoutKeywordsList: LinearLayout
    private lateinit var etReplacementFind: TextInputEditText
    private lateinit var etReplacementReplace: TextInputEditText
    private lateinit var btnAddReplacement: MaterialButton
    private lateinit var layoutReplacementsList: LinearLayout
    private lateinit var switchSmartFormat: MaterialSwitch
    private lateinit var switchPunctuate: MaterialSwitch
    private lateinit var switchNumerals: MaterialSwitch
    private lateinit var switchDictation: MaterialSwitch
    private lateinit var switchAutoFix: MaterialSwitch
    private lateinit var switchAutocorrect: MaterialSwitch
    private lateinit var switchPredictionStrip: MaterialSwitch
    private lateinit var switchAiNextWord: MaterialSwitch
    private lateinit var switchAiWordCompletion: MaterialSwitch

    // Data
    private val keywords = mutableListOf<String>()
    private val replacements = mutableListOf<Pair<String, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_deepgram_features_settings)

        preferenceManager = PreferenceManager(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        initViews()
        loadSettings()
        setupClickListeners()
    }

    private fun initViews() {
        tvKeywordsHeader = findViewById(R.id.tvKeywordsHeader)
        tvReplacementsHeader = findViewById(R.id.tvReplacementsHeader)
        etKeyword = findViewById(R.id.etKeyword)
        btnAddKeyword = findViewById(R.id.btnAddKeyword)
        layoutKeywordsList = findViewById(R.id.layoutKeywordsList)
        etReplacementFind = findViewById(R.id.etReplacementFind)
        etReplacementReplace = findViewById(R.id.etReplacementReplace)
        btnAddReplacement = findViewById(R.id.btnAddReplacement)
        layoutReplacementsList = findViewById(R.id.layoutReplacementsList)
        switchSmartFormat = findViewById(R.id.switchSmartFormat)
        switchPunctuate = findViewById(R.id.switchPunctuate)
        switchNumerals = findViewById(R.id.switchNumerals)
        switchDictation = findViewById(R.id.switchDictation)
        switchAutoFix = findViewById(R.id.switchAutoFix)
        switchAutocorrect = findViewById(R.id.switchAutocorrect)
        switchPredictionStrip = findViewById(R.id.switchPredictionStrip)
        switchAiNextWord = findViewById(R.id.switchAiNextWord)
        switchAiWordCompletion = findViewById(R.id.switchAiWordCompletion)
    }

    private fun loadSettings() {
        val language = preferenceManager.getLanguage()
        val languageLabel = getLanguageLabel(language)

        tvKeywordsHeader.text = "Keywords ($languageLabel)"
        tvReplacementsHeader.text = "Find & Replace ($languageLabel)"

        // Load keywords
        keywords.clear()
        keywords.addAll(preferenceManager.getDeepgramKeywords(language))
        refreshKeywordsList()

        // Load replacements
        replacements.clear()
        replacements.addAll(preferenceManager.getDeepgramReplacements(language))
        refreshReplacementsList()

        // Load toggles
        switchSmartFormat.isChecked = preferenceManager.getSmartFormat()
        switchPunctuate.isChecked = preferenceManager.getAutoPunctuation()
        switchNumerals.isChecked = preferenceManager.getDeepgramNumerals()
        switchDictation.isChecked = preferenceManager.getDeepgramDictation()
        switchAutoFix.isChecked = preferenceManager.getAutoFixEnabled()
        switchAutocorrect.isChecked = preferenceManager.getAutocorrectEnabled()
        switchPredictionStrip.isChecked = preferenceManager.getPredictionStripEnabled()
        switchAiNextWord.isChecked = preferenceManager.getAiNextWordEnabled()
        switchAiWordCompletion.isChecked = preferenceManager.getAiWordCompletionEnabled()
    }

    private fun setupClickListeners() {
        btnAddKeyword.setOnClickListener {
            val keyword = etKeyword.text?.toString()?.trim() ?: ""
            if (keyword.isEmpty()) return@setOnClickListener
            if (keywords.size >= 100) {
                DebugLog.i("$TAG: Max 100 keywords reached")
                return@setOnClickListener
            }
            if (keywords.contains(keyword)) {
                DebugLog.i("$TAG: Keyword '$keyword' already exists")
                return@setOnClickListener
            }

            keywords.add(keyword)
            saveKeywords()
            refreshKeywordsList()
            etKeyword.text?.clear()
            DebugLog.i("$TAG: Added keyword: $keyword")
        }

        etKeyword.setOnEditorActionListener { _, _, _ ->
            btnAddKeyword.performClick()
            true
        }

        btnAddReplacement.setOnClickListener {
            val find = etReplacementFind.text?.toString()?.trim() ?: ""
            val replace = etReplacementReplace.text?.toString()?.trim() ?: ""
            if (find.isEmpty()) return@setOnClickListener
            if (replacements.size >= 200) {
                DebugLog.i("$TAG: Max 200 replacement rules reached")
                return@setOnClickListener
            }
            if (replacements.any { it.first == find }) {
                DebugLog.i("$TAG: Replacement for '$find' already exists")
                return@setOnClickListener
            }

            replacements.add(Pair(find, replace))
            saveReplacements()
            refreshReplacementsList()
            etReplacementFind.text?.clear()
            etReplacementReplace.text?.clear()
            DebugLog.i("$TAG: Added replacement: $find → $replace")
        }

        switchSmartFormat.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setSmartFormat(isChecked)
            DebugLog.i("$TAG: Smart format changed to: $isChecked")
        }

        switchPunctuate.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setAutoPunctuation(isChecked)
            DebugLog.i("$TAG: Auto punctuation changed to: $isChecked")
        }

        switchNumerals.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setDeepgramNumerals(isChecked)
            DebugLog.i("$TAG: Numerals changed to: $isChecked")
        }

        switchDictation.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setDeepgramDictation(isChecked)
            DebugLog.i("$TAG: Dictation changed to: $isChecked")
        }

        switchAutoFix.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setAutoFixEnabled(isChecked)
            DebugLog.i("$TAG: Auto-fix changed to: $isChecked")
        }

        switchAutocorrect.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setAutocorrectEnabled(isChecked)
            DebugLog.i("$TAG: Autocorrect changed to: $isChecked")
        }

        switchPredictionStrip.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setPredictionStripEnabled(isChecked)
            DebugLog.i("$TAG: Prediction strip changed to: $isChecked")
        }

        switchAiNextWord.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setAiNextWordEnabled(isChecked)
            DebugLog.i("$TAG: AI next-word changed to: $isChecked")
        }

        switchAiWordCompletion.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setAiWordCompletionEnabled(isChecked)
            DebugLog.i("$TAG: AI word completion changed to: $isChecked")
        }
    }

    private fun saveKeywords() {
        val language = preferenceManager.getLanguage()
        preferenceManager.setDeepgramKeywords(language, keywords)
    }

    private fun saveReplacements() {
        val language = preferenceManager.getLanguage()
        preferenceManager.setDeepgramReplacements(language, replacements)
    }

    private fun refreshKeywordsList() {
        layoutKeywordsList.removeAllViews()
        for (keyword in keywords) {
            val row = createKeywordRow(keyword)
            layoutKeywordsList.addView(row)
        }
    }

    private fun refreshReplacementsList() {
        layoutReplacementsList.removeAllViews()
        for ((find, replace) in replacements) {
            val row = createReplacementRow(find, replace)
            layoutReplacementsList.addView(row)
        }
    }

    private fun createKeywordRow(keyword: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }

        val label = TextView(this).apply {
            text = keyword
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val deleteBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_close)
            setBackgroundResource(android.R.color.transparent)
            contentDescription = "Remove $keyword"
            setOnClickListener {
                keywords.remove(keyword)
                saveKeywords()
                refreshKeywordsList()
                DebugLog.i("$TAG: Removed keyword: $keyword")
            }
        }

        row.addView(label)
        row.addView(deleteBtn)
        return row
    }

    private fun createReplacementRow(find: String, replace: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }

        val label = TextView(this).apply {
            text = "$find → $replace"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val deleteBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_close)
            setBackgroundResource(android.R.color.transparent)
            contentDescription = "Remove $find → $replace"
            setOnClickListener {
                replacements.removeAll { it.first == find }
                saveReplacements()
                refreshReplacementsList()
                DebugLog.i("$TAG: Removed replacement: $find → $replace")
            }
        }

        row.addView(label)
        row.addView(deleteBtn)
        return row
    }

    private fun getLanguageLabel(language: String): String {
        return when (language) {
            "de" -> "German"
            "en" -> "English"
            else -> language.uppercase()
        }
    }
}
