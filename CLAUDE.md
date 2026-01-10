# Tiny Dictation Android - Development Notes

## Versioning Requirements

**CRITICAL**: Before ANY code change that will be pushed, you MUST:
1. Increment `versionCode` by 1
2. Update `versionName` appropriately (patch/minor/major)
3. **Update the "What's New" changelog** in `SettingsActivity.kt` companion object

This ensures every APK has a unique version and users can see what changed.

1. **Version location**: `app/build.gradle.kts`
   - `versionCode` - Integer, increment by 1 for each build
   - `versionName` - Semantic version string (e.g., "1.2.0")

2. **APK naming**: The build workflow automatically names APKs as:
   - `TinyDictation-v{versionName}-{gitShortHash}.apk`
   - Example: `TinyDictation-v1.2.0-e6da897.apk`

3. **Version display**: Version is shown in:
   - Main screen (bottom): "v1.3.0"
   - Settings screen (About section): "Version 1.3.0 (6)"

4. **Changelog**: Update changelog in `SettingsActivity.kt` companion object
   - Add new version at the top with bullet points
   - Keep previous versions for history

5. **Telegram notifications**: Include version in build notifications

## When to bump version

- **Patch (1.2.x)**: Bug fixes, minor tweaks
- **Minor (1.x.0)**: New features, significant changes
- **Major (x.0.0)**: Breaking changes, major rewrites

## Dictation Mode

The app uses keyboard-based dictation only:

**Full Keyboard (IME)** (`DictationKeyboardService.kt`)
- Complete replacement keyboard with dictation and action buttons
- Button order: Chevron | Language | Mic | Enter | Backspace | Paste | SelectAll
- Chevron switches back to previous keyboard
- Long-press mic opens Settings, long-press paste opens History

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
- GitHub Actions CI/CD on `main`, `master`, and `claude/**` branches

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
