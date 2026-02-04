# Language Switching & Indicator System Research

## Overview
The Tiny Dictation Android app has a language switching system that toggles between English (EN) and German (DE). Currently, the language indicator is a simple TextView button that can be scrolled out of view.

## Key Findings

### 1. Layout Structure (`view_keyboard.xml`)
- **File**: `/home/valentin/projects/tiny-dictation-android/app/src/main/res/layout/view_keyboard.xml`
- **Language Button ID**: `btnLanguage` (FrameLayout wrapper)
- **Language Text ID**: `tvLanguage` (TextView inside btnLanguage)
- **Position in Dictation Bar**: 5th button (after Space button, before Backspace)
- **Button Order**:
  ```
  Chevron | SelectAll | Emoji | Space | [Language] | Backspace | MIC | Enter | Keyboard | History | Improve
  └────── scroll left to see ──────┘   └─ visible, MIC centered ─────────────────────────────────────┘
  ```
- **Current Implementation**:
  - Language button contains a TextView with text "EN" or "DE"
  - Text size: 14sp, bold, color: `@color/keyboard_key_text`
  - Button size: 48dp x 48dp
  - **PROBLEM**: Language button is to the LEFT of MIC button, so it gets hidden when scrolling centers on MIC

### 2. Language Management

#### PreferenceManager (`util/PreferenceManager.kt`)
- **Language Storage Key**: `KEY_LANGUAGE = "language"`
- **Default Language**: `DEFAULT_LANGUAGE = "en"`
- **Methods**:
  - `getLanguage()`: Returns language code (default "en")
  - `setLanguage(language: String)`: Saves language preference
  - `currentProviderNeedsLanguageToggle()`: Always returns `true` - language button always shown
  - `getBtnLanguageEnabled()`: Check if button is visible in settings
  - `setBtnLanguageEnabled()`: Toggle button visibility

#### TranscriptionManager (`transcription/TranscriptionManager.kt`)
- **Current Language State**: `private var currentLanguage = "en"`
- **Methods**:
  - `setLanguage(language: String)`: Updates engine's language and state
  - Language is passed to transcription engines during initialization
- **Language Persistence**: Language is loaded from preferences when manager initializes

#### DictationKeyboardService (`service/DictationKeyboardService.kt`)
- **Views**:
  - Line 108: `private var languageText: TextView? = null`
  - Line 682: Cached during keyboard inflation
  - Line 1408: Text updated when language changes

- **Key Functions**:
  - `updateLanguageButton()` (line 1395): Updates button visibility and text
    ```kotlin
    private fun updateLanguageButton() {
        val prefs = PreferenceManager(this)
        val languageButton = keyboardView?.findViewById<FrameLayout>(R.id.btnLanguage)
        
        val needsLanguageToggle = prefs.currentProviderNeedsLanguageToggle()
        if (!needsLanguageToggle) {
            languageButton?.visibility = View.GONE
        } else {
            languageButton?.visibility = View.VISIBLE
            val lang = prefs.getLanguage().uppercase()  // Converts to uppercase
            languageText?.text = lang
        }
    }
    ```
  
  - `toggleLanguage()` (line 1250): Handles language switching
    ```kotlin
    private fun toggleLanguage() {
        val prefs = PreferenceManager(this)
        val currentLang = prefs.getLanguage()
        
        // Toggle between en and de (or extend for more languages)
        val newLang = if (currentLang == "en") "de" else "en"
        prefs.setLanguage(newLang)
        
        // If recording, restart mic with new language
        if (isRecording || isProcessing) {
            transcriptionManager?.stopListening()
            // ... brief delay ...
            transcriptionManager?.startListening()  // Restarts with new language
        } else {
            transcriptionManager?.setLanguage(newLang)
        }
        
        updateLanguageButton()
    }
    ```
  
  - Button click handler (line 1075): Calls `toggleLanguage()` when clicked
  
  - `scrollDictationBarToDefault()` (line 1437): Centers scroll on MIC button

### 3. Supported Languages
- **Currently Toggled**: "en" (English) ↔ "de" (German) only
- **Deepgram Support**: Supports many languages via API parameter
- **Code Reference**: `DeepgramClient.kt` shows language codes like "en", "de", "auto" (for Nova-3 model)

### 4. Scrolling Behavior
- **Horizontal Scroll View**: `dictationBarScrollView` (line 38 in layout)
- **Scroll Centering**: `scrollDictationBarToDefault()` centers on MIC button
- **MIC Button Position**: After Language button in the button order
- **Issue**: When scroll centers on MIC, Language button (to the left) is hidden off-screen

### 5. Version Display (Reference Implementation)
- **File**: `/home/valentin/projects/tiny-dictation-android/app/src/main/res/layout/view_keyboard.xml` (line 259-269)
- **ID**: `tvKeyboardVersion`
- **Position**: Bottom-right corner overlay on dictation bar (FrameLayout)
- **Text Size**: 7sp
- **Color**: `#AAFFFFFF` (semi-transparent white)
- **Updates Dynamically**: Read from `PackageInfo.versionName` in `setupVersionDisplay()` (DictationKeyboardService line 1416)

### 6. Button Visibility Management
- **Controlled in**: `applyButtonVisibility()` (line 1040 in DictationKeyboardService)
- All buttons have enable/disable preferences:
  - `getBtnLanguageEnabled()` / `setBtnLanguageEnabled()`
  - `getBtnSwitchBackEnabled()`, `getBtnSelectAllEnabled()`, etc.
- Language button can be hidden from user settings even when provider needs toggle

## Problem Statement
The language indicator is currently:
1. **Hidden by scrolling** - Located to the left of MIC button, scrolled out of view when bar centers on MIC
2. **Small and hard to see** - 14sp text in 48dp button
3. **Easy to miss changes** - User may not notice if language was accidentally toggled

## Potential Solutions for More Visible Indicator
1. **Near MIC button (Recommended)** - Add fixed position indicator next to or above MIC button (like version display approach)
2. **Larger text** - Current: 14sp bold → Could be 16-18sp
3. **Always visible** - Add to fixed area (like version display in corner)
4. **Visual indicator** - Icon or color coding in addition to text
5. **Toast notification** - Brief notification when language changes
6. **Floating badge** - Like the version number overlay, add language badge above/below MIC button

## Key Files to Modify (if implementing solution)
- `/app/src/main/res/layout/view_keyboard.xml` - Layout changes (add new overlay element or relocate language button)
- `/app/src/main/java/com/tinydictation/android/service/DictationKeyboardService.kt` - Logic updates (add new function to update language indicator position)
- Potentially `/app/src/main/java/com/tinydictation/android/transcription/TranscriptionManager.kt` - If expanding languages beyond en/de

## Related Settings
- **Location**: TranscriptionSettingsActivity
- **Auto-detect**: Can be enabled/disabled per provider
- **Manual Toggle**: Always available via language button click
- **Visible by Default**: Button visibility controlled by `getBtnLanguageEnabled()` preference

## Code Structure Insights

### Language Button Structure
```xml
<!-- In view_keyboard.xml, lines 127-145 -->
<FrameLayout
    android:id="@+id/btnLanguage"
    android:layout_width="48dp"
    android:layout_height="48dp"
    android:layout_marginStart="6dp"
    android:background="@drawable/keyboard_button_background"
    android:contentDescription="Switch language">

    <TextView
        android:id="@+id/tvLanguage"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:text="EN"
        android:textSize="14sp"
        android:textColor="@color/keyboard_key_text"
        android:textStyle="bold" />
</FrameLayout>
```

### Version Display Structure (Good Reference)
```xml
<!-- In view_keyboard.xml, lines 259-269 -->
<TextView
    android:id="@+id/tvKeyboardVersion"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_gravity="bottom|end"
    android:text="v1.11.13"
    android:textSize="7sp"
    android:textColor="#AAFFFFFF"
    android:letterSpacing="0.1"
    android:paddingEnd="4dp"
    android:paddingBottom="2dp" />
```

This uses a FrameLayout parent (line 32-271) to position overlays absolutely, which is the pattern to follow for adding a visible language indicator overlay.
