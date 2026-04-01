package com.tinydictation.android.settings

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.tinydictation.android.R
import com.tinydictation.android.service.DictationKeyboardService
import com.tinydictation.android.util.DebugLog
import com.tinydictation.android.util.PreferenceManager

/**
 * Activity for configuring appearance settings including keyboard theme and GIF settings.
 */
class AppearanceSettingsActivity : AppCompatActivity() {

    private lateinit var preferenceManager: PreferenceManager

    private lateinit var keyboardThemeDropdown: AutoCompleteTextView
    private lateinit var numberRowSwitch: MaterialSwitch
    private lateinit var predictionStripSwitch: MaterialSwitch
    private lateinit var autocorrectSwitch: MaterialSwitch
    private lateinit var aiNextWordSwitch: MaterialSwitch
    private lateinit var aiWordCompletionSwitch: MaterialSwitch
    private lateinit var autoFixSwitch: MaterialSwitch
    private lateinit var compactBarSwitch: MaterialSwitch
    private lateinit var panelClaudeSwitch: MaterialSwitch
    private lateinit var panelShellSwitch: MaterialSwitch
    private lateinit var panelSnippetsSwitch: MaterialSwitch
    private lateinit var panelClipboardSwitch: MaterialSwitch
    private lateinit var panelHistorySwitch: MaterialSwitch

    // Dictation bar button switches
    private lateinit var btnSwitchBackSwitch: MaterialSwitch
    private lateinit var btnSelectAllSwitch: MaterialSwitch
    private lateinit var btnEmojiSwitch: MaterialSwitch
    private lateinit var btnSpaceSwitch: MaterialSwitch
    private lateinit var btnLanguageSwitch: MaterialSwitch
    private lateinit var btnBackspaceSwitch: MaterialSwitch
    private lateinit var btnEnterSwitch: MaterialSwitch
    private lateinit var btnKeyboardSwitch: MaterialSwitch
    private lateinit var btnHistorySwitch: MaterialSwitch
    private lateinit var btnImproveSwitch: MaterialSwitch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_appearance_settings)

        preferenceManager = PreferenceManager(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        initViews()
        loadSettings()
        setupClickListeners()
    }

    private fun initViews() {
        keyboardThemeDropdown = findViewById(R.id.actvKeyboardTheme)
        numberRowSwitch = findViewById(R.id.switchNumberRow)
        predictionStripSwitch = findViewById(R.id.switchPredictionStrip)
        autocorrectSwitch = findViewById(R.id.switchAutocorrect)
        aiNextWordSwitch = findViewById(R.id.switchAiNextWord)
        aiWordCompletionSwitch = findViewById(R.id.switchAiWordCompletion)
        autoFixSwitch = findViewById(R.id.switchAutoFix)
        compactBarSwitch = findViewById(R.id.switchCompactBar)
        panelClaudeSwitch = findViewById(R.id.switchPanelClaude)
        panelShellSwitch = findViewById(R.id.switchPanelShell)
        panelSnippetsSwitch = findViewById(R.id.switchPanelSnippets)
        panelClipboardSwitch = findViewById(R.id.switchPanelClipboard)
        panelHistorySwitch = findViewById(R.id.switchPanelHistory)

        // Dictation bar button switches
        btnSwitchBackSwitch = findViewById(R.id.switchBtnSwitchBack)
        btnSelectAllSwitch = findViewById(R.id.switchBtnSelectAll)
        btnEmojiSwitch = findViewById(R.id.switchBtnEmoji)
        btnSpaceSwitch = findViewById(R.id.switchBtnSpace)
        btnLanguageSwitch = findViewById(R.id.switchBtnLanguage)
        btnBackspaceSwitch = findViewById(R.id.switchBtnBackspace)
        btnEnterSwitch = findViewById(R.id.switchBtnEnter)
        btnKeyboardSwitch = findViewById(R.id.switchBtnKeyboard)
        btnHistorySwitch = findViewById(R.id.switchBtnHistory)
        btnImproveSwitch = findViewById(R.id.switchBtnImprove)

        setupKeyboardThemeDropdown()
    }

    private fun loadSettings() {
        numberRowSwitch.isChecked = preferenceManager.getShowNumberRow()
        predictionStripSwitch.isChecked = preferenceManager.getPredictionStripEnabled()
        autocorrectSwitch.isChecked = preferenceManager.getAutocorrectEnabled()
        aiNextWordSwitch.isChecked = preferenceManager.getAiNextWordEnabled()
        aiWordCompletionSwitch.isChecked = preferenceManager.getAiWordCompletionEnabled()
        autoFixSwitch.isChecked = preferenceManager.getAutoFixEnabled()
        compactBarSwitch.isChecked = preferenceManager.getCompactDictationBar()
        panelClaudeSwitch.isChecked = preferenceManager.getPanelClaudeEnabled()
        panelShellSwitch.isChecked = preferenceManager.getPanelShellEnabled()
        panelSnippetsSwitch.isChecked = preferenceManager.getPanelSnippetsEnabled()
        panelClipboardSwitch.isChecked = preferenceManager.getPanelClipboardEnabled()
        panelHistorySwitch.isChecked = preferenceManager.getPanelHistoryEnabled()

        // Dictation bar buttons
        btnSwitchBackSwitch.isChecked = preferenceManager.getBtnSwitchBackEnabled()
        btnSelectAllSwitch.isChecked = preferenceManager.getBtnSelectAllEnabled()
        btnEmojiSwitch.isChecked = preferenceManager.getBtnEmojiEnabled()
        btnSpaceSwitch.isChecked = preferenceManager.getBtnSpaceEnabled()
        btnLanguageSwitch.isChecked = preferenceManager.getBtnLanguageEnabled()
        btnBackspaceSwitch.isChecked = preferenceManager.getBtnBackspaceEnabled()
        btnEnterSwitch.isChecked = preferenceManager.getBtnEnterEnabled()
        btnKeyboardSwitch.isChecked = preferenceManager.getBtnKeyboardEnabled()
        btnHistorySwitch.isChecked = preferenceManager.getBtnHistoryEnabled()
        btnImproveSwitch.isChecked = preferenceManager.getBtnImproveEnabled()
    }

    private fun setupClickListeners() {
        // Number row toggle
        numberRowSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setShowNumberRow(isChecked)
            DebugLog.i("Number row setting changed to: $isChecked")
            // Note: Keyboard needs to be reopened to see the change
        }

        // Prediction strip toggle
        predictionStripSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setPredictionStripEnabled(isChecked)
            DebugLog.i("Prediction strip setting changed to: $isChecked")
        }

        // Autocorrect toggle
        autocorrectSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setAutocorrectEnabled(isChecked)
            DebugLog.i("Autocorrect setting changed to: $isChecked")
        }

        // AI Next-Word Prediction toggle
        aiNextWordSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setAiNextWordEnabled(isChecked)
            DebugLog.i("AI next-word prediction setting changed to: $isChecked")
        }

        // AI Word Completion toggle
        aiWordCompletionSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setAiWordCompletionEnabled(isChecked)
            DebugLog.i("AI word completion setting changed to: $isChecked")
        }

        // Auto-Fix toggle
        autoFixSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setAutoFixEnabled(isChecked)
            DebugLog.i("Auto-fix setting changed to: $isChecked")
        }

        // Compact dictation bar toggle
        compactBarSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setCompactDictationBar(isChecked)
            DebugLog.i("Compact dictation bar setting changed to: $isChecked")
            // Note: Keyboard needs to be reopened to see the change
        }

        // Panel visibility toggles
        panelClaudeSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setPanelClaudeEnabled(isChecked)
            DebugLog.i("Agent panel setting changed to: $isChecked")
        }
        panelShellSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setPanelShellEnabled(isChecked)
            DebugLog.i("Shell panel setting changed to: $isChecked")
        }
        panelSnippetsSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setPanelSnippetsEnabled(isChecked)
            DebugLog.i("Snippets panel setting changed to: $isChecked")
        }
        panelClipboardSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setPanelClipboardEnabled(isChecked)
            DebugLog.i("Clipboard panel setting changed to: $isChecked")
        }
        panelHistorySwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setPanelHistoryEnabled(isChecked)
            DebugLog.i("History panel setting changed to: $isChecked")
        }

        // Dictation bar button toggles
        btnSwitchBackSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setBtnSwitchBackEnabled(isChecked)
        }
        btnSelectAllSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setBtnSelectAllEnabled(isChecked)
        }
        btnEmojiSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setBtnEmojiEnabled(isChecked)
        }
        btnSpaceSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setBtnSpaceEnabled(isChecked)
        }
        btnLanguageSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setBtnLanguageEnabled(isChecked)
        }
        btnBackspaceSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setBtnBackspaceEnabled(isChecked)
        }
        btnEnterSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setBtnEnterEnabled(isChecked)
        }
        btnKeyboardSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setBtnKeyboardEnabled(isChecked)
        }
        btnHistorySwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setBtnHistoryEnabled(isChecked)
        }
        btnImproveSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setBtnImproveEnabled(isChecked)
        }

    }

    private fun setupKeyboardThemeDropdown() {
        val themes = listOf(
            getString(R.string.keyboard_theme_system) to PreferenceManager.THEME_SYSTEM,
            getString(R.string.keyboard_theme_light) to PreferenceManager.THEME_LIGHT,
            getString(R.string.keyboard_theme_dark) to PreferenceManager.THEME_DARK,
            getString(R.string.keyboard_theme_amoled) to PreferenceManager.THEME_AMOLED
        )

        val displayNames = themes.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, displayNames)
        keyboardThemeDropdown.setAdapter(adapter)

        val currentTheme = preferenceManager.getKeyboardTheme()
        val currentIndex = themes.indexOfFirst { it.second == currentTheme }
        if (currentIndex >= 0) {
            keyboardThemeDropdown.setText(displayNames[currentIndex], false)
        } else {
            keyboardThemeDropdown.setText(displayNames[0], false)
        }

        keyboardThemeDropdown.setOnItemClickListener { _, _, position, _ ->
            val selectedValue = themes[position].second
            preferenceManager.setKeyboardTheme(selectedValue)
            DebugLog.i("Keyboard theme changed to: $selectedValue")
            DictationKeyboardService.getInstance()?.refreshTheme()
        }
    }

}
