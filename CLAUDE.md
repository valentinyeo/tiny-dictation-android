# Tiny Dictation Android - Development Notes

## ⚠️ MANDATORY WORKFLOW - READ FIRST ⚠️

**Every code change MUST follow this exact sequence. NO EXCEPTIONS.**

### The Build-Send-Commit Cycle

```
1. Make code changes
2. Update version in app/build.gradle.kts (increment versionCode + versionName)
3. Update changelog in SettingsActivity.kt
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
3. Update changelog in `SettingsActivity.kt` companion object
4. **COMMIT after every version** - Never leave versions uncommitted!

1. **Version location**: `app/build.gradle.kts`
   - `versionCode` - Integer, increment by 1 for each build
   - `versionName` - Semantic version string (e.g., "1.2.0")

2. **Version display**: Version is shown in:
   - Main screen (bottom): "v1.3.0"
   - Settings screen (About section): "Version 1.3.0 (6)"

3. **Changelog**: Update changelog in `SettingsActivity.kt` companion object
   - Add new version at the top with bullet points
   - Keep previous versions for history

## When to bump version

- **Patch (1.2.x)**: Bug fixes, minor tweaks
- **Minor (1.x.0)**: New features, significant changes
- **Major (x.0.0)**: Breaking changes, major rewrites

## Dictation Mode

The app uses keyboard-based dictation only:

**Full Keyboard (IME)** (`DictationKeyboardService.kt`)
- Complete replacement keyboard with dictation and action buttons
- Chevron switches back to previous keyboard
- Long-press mic opens Settings
- Clipboard button opens transcription history (select & paste past transcriptions)

### ⚠️ CANONICAL BUTTON ORDER - DO NOT CHANGE WITHOUT UPDATING THIS FILE ⚠️

```
Chevron | Emoji | Keyboard | SelectAll | Space | Backspace | MIC | Enter | Language | Clipboard
         └──── scroll left to see ─────┘       └───── visible, MIC centered ────────────────┘
```

**Button functions:**
1. **Chevron** - Switch to previous keyboard
2. **Emoji** - Toggle emoji picker panel
3. **Keyboard** - Toggle tabbed keyboard (QWERTY + Cursor/Numpad)
4. **SelectAll** - Select all text
5. **Space** - Insert space (useful during dictation)
6. **Backspace** - Delete character
7. **MIC** - Toggle dictation (CENTERED for thumb access, auto-starts on keyboard open)
8. **Enter** - Send enter key
9. **Language** - Switch transcription language
10. **Clipboard** - Open transcription history (icon: ic_history)

**Layout file:** `app/src/main/res/layout/view_keyboard.xml`

**Scroll behavior:**
- The HorizontalScrollView MUST center on the MIC button when keyboard opens
- MIC button MUST remain visually centered on screen for thumb access
- `scrollDictationBarToDefault()` in DictationKeyboardService.kt handles this
- Buttons to the left of MIC (Chevron, Emoji, Keyboard, Clipboard, Space, Backspace) are hidden by scroll
- Buttons to the right of MIC (Enter, Language, SelectAll) are visible by default

### Archived Modes (in `archived/` directory)
The following experimental modes have been archived but preserved for reference:
- **Vertical Floating Bar** (`archived/java/service/BubbleService.kt`) - Draggable overlay
- **Horizontal Companion Bar** (`archived/java/service/KeyboardCompanionService.kt`) - Bar above keyboard

## Transcription Engines

- **Deepgram** (cloud) - Default, requires API key and internet
- **Vosk** (offline) - Fast streaming recognition, ~45MB models
- **Sherpa-ONNX/Whisper** (offline) - High quality, ~75MB models

Engine interface: `app/src/main/java/com/tinydictation/android/transcription/TranscriptionEngine.kt`

Model management: `ModelManager.kt` handles downloading/storing offline models

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
Example: `TinyDictation-v1.11.27-arm64.apk`

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

**Important**: Always list the actual changes from the changelog in SettingsActivity.kt, not just a summary.

Use `/send` to manually trigger sending the latest APK if needed.

## Key Files

- `MainActivity.kt` - Main entry point, permission handling
- `DictationKeyboardService.kt` - Full IME keyboard replacement (main dictation interface)
- `TranscriptionManager.kt` - Orchestrates audio recording, VAD, and transcription
- `AudioRecorder.kt` - Audio capture (uses VOICE_RECOGNITION source for phone mic)
- `VoiceActivityDetector.kt` - Simple RMS-based VAD (thresholds: speech=100, silence=50)
- `DeepgramClient.kt` - WebSocket client for Deepgram API
- `DictationAccessibilityService.kt` - Text injection via accessibility

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

## Pending Work

- Settings screen reorganization (plan exists but not implemented)
- See plan file at `~/.claude/plans/pure-nibbling-phoenix.md` for offline transcription details
