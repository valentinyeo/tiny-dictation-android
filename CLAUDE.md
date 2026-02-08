# Tiny Dictation Android - Development Notes

## 📋 Hypertask Board

**Board:** Tiny Dictation Android
**Project ID:** 1425
**URL:** https://app.hypertask.ai/project?id=1425

⚠️ This is the ANDROID app board. Do NOT put tickets in the Windows "Tiny Dictation" board (ID 1409).

## ⚠️ MANDATORY WORKFLOW - READ FIRST ⚠️

**Every code change MUST follow this exact sequence. NO EXCEPTIONS.**

### The Build-Send-Commit Cycle

```
1. Make code changes
2. Update version in app/build.gradle.kts (increment versionCode + versionName)
3. Update changelog in settings/AboutActivity.kt
4. Build: nice -n 19 ./gradlew assembleDebug --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx512m"
5. Copy APK: cp app/build/outputs/apk/debug/app-arm64-v8a-debug.apk /tmp/TinyDictation-v{VERSION}-arm64.apk
6. Send to Telegram (see command below)
7. COMMIT IMMEDIATELY: git add -A && git commit -m "v{VERSION}: {description}"
8. PUSH IMMEDIATELY: git push origin master
```

**Steps 7 and 8 are NOT optional. Uncommitted/unpushed versions WILL BE LOST.**

Previous sessions skipped commits and lost 8 versions of work. Another session committed but forgot to push 13 versions. Don't repeat these mistakes.

---

## Versioning Requirements

**CRITICAL**: For ANY code change, you MUST:
1. Increment `versionCode` by 1
2. Update `versionName` appropriately (patch/minor/major)
3. Update changelog in `AboutActivity.kt` companion object
4. **COMMIT after every version** - Never leave versions uncommitted!

1. **Version location**: `app/build.gradle.kts`
   - `versionCode` - Integer, increment by 1 for each build
   - `versionName` - Semantic version string (e.g., "1.2.0")

2. **Version display**: Version is shown in:
   - Main screen (bottom): "v1.3.0"
   - Settings screen (About section): "Version 1.3.0 (6)"

3. **Changelog**: Update changelog in `AboutActivity.kt` companion object
   - Add new version at the top with bullet points
   - Keep previous versions for history

## When to bump version

- **Patch (1.2.x)**: Bug fixes, minor tweaks
- **Minor (1.x.0)**: New features, significant changes
- **Major (x.0.0)**: Breaking changes, major rewrites

## UI Architecture - IMPORTANT TERMINOLOGY

⚠️ **Read this section carefully to avoid confusion when discussing UI changes.**

The keyboard has TWO distinct UI components:

### 1. DICTATION BAR (always visible)
- **File:** `app/src/main/res/layout/view_keyboard.xml`
- **Description:** The horizontal bar at the bottom with action buttons
- **Key element:** The MIC button (centered for thumb access)
- **Contains:** Chevron, SelectAll, Emoji, Space, Language, Backspace, **MIC**, Enter, Keyboard, History, Improve buttons
- **Behavior:** Always visible when keyboard is open; scrollable horizontally

### 2. TABBED PANEL (slide-out, hidden by default)
- **File:** `app/src/main/res/layout/keyboard_tabbed_panel.xml`
- **Description:** The expandable panel that appears ABOVE the dictation bar
- **Triggered by:** Tapping the Keyboard button in the dictation bar
- **Container height:** 250dp (fixed in `keyboardPagesContainer`)
- **Contains 6 tabs/sub-panels:**

| Tab | Panel Name | Layout File | Description |
|-----|------------|-------------|-------------|
| ABC | QWERTY Panel | `keyboard_qwerty_panel.xml` | Standard QWERTY keyboard + optional number row |
| 123 | Cursor/Numpad Panel | `keyboard_cursor_numpad_panel.xml` | Arrow keys + numeric keypad |
| Claude | Claude Panel | `keyboard_claude_panel.xml` | Claude Code slash commands |
| Shell | Shell Panel | `keyboard_shell_panel.xml` | Shell/terminal commands |
| Clip | Clipboard Panel | `panel_clipboard.xml` | Clipboard history (copied items) |
| Hist | History Panel | `panel_history.xml` | Transcription history (dictated text) |

```
┌─────────────────────────────────────┐
│  ┌─────────────────────────────────┐│
│  │      TABBED PANEL               ││  ← Hidden by default
│  │  (QWERTY, Numpad, Claude, etc.) ││    Shows when Keyboard button tapped
│  │                                 ││
│  └─────────────────────────────────┘│
│  ┌─────────────────────────────────┐│
│  │       DICTATION BAR             ││  ← Always visible
│  │  ... [MIC] Enter Keyboard Hist  ││    MIC button centered
│  └─────────────────────────────────┘│
└─────────────────────────────────────┘
```

### Terminology Quick Reference:
| Term | What it means | Layout file |
|------|---------------|-------------|
| "Dictation bar" | Bottom button bar with MIC | `view_keyboard.xml` |
| "QWERTY panel/keyboard" | Typing keyboard in tabbed panel | `keyboard_qwerty_panel.xml` |
| "Tabbed panel" | Entire slide-out area above dictation bar | `keyboard_tabbed_panel.xml` |
| "Number row" | Optional 1-0 row above QWERTY letters | Inside `keyboard_qwerty_panel.xml` |

---

## Dictation Bar Details

### ⚠️ CANONICAL BUTTON ORDER - DO NOT CHANGE WITHOUT UPDATING THIS FILE ⚠️

```
Chevron | SelectAll | Emoji | Space | Language | Backspace | MIC | Enter | Keyboard | History | Improve
         └───── scroll left to see ──────┘        └───── visible, MIC centered ────────────────────────┘
```

**Button functions:**
1. **Chevron** - Switch to previous keyboard
2. **SelectAll** - Select all text
3. **Emoji** - Toggle emoji picker panel
4. **Space** - Insert space (useful during dictation)
5. **Language** - Switch transcription language
6. **Backspace** - Delete character
7. **MIC** - Toggle dictation (CENTERED for thumb access, auto-starts on keyboard open)
8. **Enter** - Send enter key
9. **Keyboard** - Toggle tabbed panel (QWERTY + Cursor/Numpad + other tabs)
10. **History** - Open transcription history (icon: ic_history)
11. **Improve** - AI-powered text improvement (icon: ic_improve, uses Anthropic API)

**Scroll behavior:**
- The HorizontalScrollView MUST center on the MIC button when keyboard opens
- MIC button MUST remain visually centered on screen for thumb access
- `scrollDictationBarToDefault()` in DictationKeyboardService.kt handles this
- Buttons to the left of MIC (Chevron, SelectAll, Emoji, Space, Backspace, Language) are hidden by scroll
- Buttons to the right of MIC (Enter, Keyboard, History, Improve) are visible by default

## Transcription Engines (v1.31.0+)

Only three engines remain after the v1.31.0 cleanup:

- **Deepgram** (cloud) - Default, requires API key and internet. Only cloud engine.
- **Vosk** (offline) - Fast streaming recognition, ~45MB models
- **Sherpa-ONNX/Whisper** (offline) - High quality, ~75MB models

Engine interface: `app/src/main/java/com/tinydictation/android/transcription/TranscriptionEngine.kt`

Model management: `ModelManager.kt` handles downloading/storing offline models

## AI Services (v1.31.0+)

- **Anthropic** (Claude API) - Powers the "Improve" button for text improvement. Only AI service.
- Configured via `TextImprover.kt`, requires Anthropic API key.

## API Keys

Only two API keys are needed (managed in Settings → API Keys):
- **Deepgram** - For cloud transcription
- **Anthropic** - For AI text improvement (Improve button)

## Build System

- Gradle 8.2 with Kotlin DSL
- Android SDK 34, min SDK 26
- **Builds are done locally** (no GitHub Actions - saves CI minutes)

### Local Build Commands

Local builds are fast (~45-60 seconds). Use resource-limited flags to keep system responsive:

```bash
# Standard build (recommended)
nice -n 19 ./gradlew assembleDebug --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx512m"
```

**Flags explained:**
- `nice -n 19` - Lowest CPU priority, won't freeze system
- `--no-daemon` - Don't keep Gradle daemon running (saves memory)
- `--max-workers=2` - Limit parallel tasks
- `-Xmx512m` - Limit JVM heap to 512MB

**Output APKs:**
- `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` (~46MB)
- `app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk` (~37MB)

### Sending APKs to Telegram

**CRITICAL: Every build MUST be sent to Telegram immediately after building.** The user cannot test APKs locally - they must be sent to Telegram to install on their phone. Do not ask "do you want me to send it?" - just send it automatically after every build.

**APK naming convention**: Copy and rename with version before sending:
```bash
cp app/build/outputs/apk/debug/app-arm64-v8a-debug.apk /tmp/TinyDictation-v{VERSION}-arm64.apk
```
Example: `TinyDictation-v1.31.0-arm64.apk`

Telegram credentials are in `~/.telegram-deploy`. Send with **actual changelog** in caption:
```bash
source ~/.telegram-deploy && curl -s -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendDocument" \
  -F chat_id="${TELEGRAM_CHAT_ID}" \
  -F document=@"/tmp/TinyDictation-v{VERSION}-arm64.apk" \
  -F caption="v{VERSION}
• Change 1
• Change 2
• Change 3"
```

**Important**: Always list the actual changes from the changelog in AboutActivity.kt, not just a summary.

Use `/send` to manually trigger sending the latest APK if needed.

## Key Files

- `MainActivity.kt` - Main entry point, permission handling
- `DictationKeyboardService.kt` - Full IME keyboard replacement (main dictation interface)
- `TranscriptionManager.kt` - Orchestrates audio recording, VAD, and transcription
- `AudioRecorder.kt` - Audio capture (uses VOICE_RECOGNITION source for phone mic)
- `VoiceActivityDetector.kt` - Simple RMS-based VAD (thresholds: speech=100, silence=50)
- `DeepgramClient.kt` - WebSocket client for Deepgram API (keywords, replace, numerals, dictation params)
- `DeepgramEngine.kt` - Engine wrapper for DeepgramClient (in `transcription/engines/`)
- `TextImprover.kt` - Anthropic-powered text improvement (Improve button)
- `DictationAccessibilityService.kt` - Text injection via accessibility
- `DeepgramFeaturesSettingsActivity.kt` - Settings UI for Deepgram keywords, replacements, numerals, dictation
- `PreferenceManager.kt` - All settings via EncryptedSharedPreferences

## Settings Screens

| Screen | Activity | Description |
|--------|----------|-------------|
| API Keys | `ApiKeysSettingsActivity` | Deepgram + Anthropic API keys |
| Transcription | `TranscriptionSettingsActivity` | Engine, model, language, fallback |
| Voice Commands | `VoiceCommandsActivity` | Hands-free text control |
| Audio | `AudioSettingsActivity` | Microphone, silence detection |
| Offline Models | `OfflineModelsActivity` | Download Vosk/Whisper models |
| Appearance | `AppearanceSettingsActivity` | Theme, number row, panel/button visibility |
| Deepgram Features | `DeepgramFeaturesSettingsActivity` | Keywords, replacements, numerals, dictation |
| Permissions | `PermissionsActivity` | Microphone, accessibility |
| History | `HistoryActivity` | Past transcriptions |
| About | `AboutActivity` | Version, changelog, debug logs |

## Debugging

- App uses `DebugLog` utility for in-app logging (copy from Settings)
- All logs use tag "TinyDictation"
- VAD logs RMS values every ~1 second

## Audio Configuration

- Sample rate: 16kHz
- Format: PCM 16-bit mono
- Chunk size: 320 samples (20ms)
- Audio source: VOICE_RECOGNITION (prefers phone mic over headphones)

## User Setup Notes

On Android 13+, after installing the APK, users need to:
1. Go to Settings > Apps > Tiny Dictation
2. Tap three-dot menu > "Allow restricted settings"
3. Then enable Accessibility service in Settings > Accessibility

## Deepgram Features (v1.30.0)

Per-language Deepgram API enhancements accessible via Settings → Deepgram Features:
- **Keywords** (`&keywords=word`): Boost recognition of specific words (names, technical terms). Up to 100/language.
- **Find & Replace** (`&replace=find:replace`): Fix recurring transcription errors. Up to 200 rules/language.
- **Numerals** (`&numerals=true`): Convert spoken numbers to digits. Default on.
- **Dictation** (`&dictation=true`): Say "period"/"comma" to insert punctuation. English only, default off.

Data stored per-language in PreferenceManager. Flow: PreferenceManager → TranscriptionManager.selectEngine() → DeepgramEngine → DeepgramClient.buildParams()

## MIC Button Behavior (v1.29.3)

- **Normal mode**: Tap = toggleMute (~300ms delay), Double-tap = enter PTT
- **PTT mode**: Hold = record, Double-tap = exit PTT, Single tap = no-op
- Colors: Blue (#4169E1) = PTT idle, Orange (#FF8C00) = buffering, Red (#ED2939)+pulse = streaming, Grey (#808080) = muted
