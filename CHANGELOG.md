# Changelog

All notable changes to Tiny Dictation will be documented in this file.

## [1.38.1]

### Fixed
- Clear message when Deepgram rejects the API key, runs out of credit or the connection drops; stops retrying on a dead key.

## [1.14.0] - 2026-01-16

### Added
- **Redesigned Settings screen** - Big headline with clean list of 9 settings categories
- **Voice Commands screen** - New screen showing all available voice commands organized by category
- **Dedicated sub-screens** - Each settings category now opens its own dedicated screen:
  - Transcription (Engine, language, model, API keys)
  - Voice Commands (All 30+ commands with descriptions)
  - Audio (Microphone, silence detection, auto-punctuation)
  - Offline Models (Download/delete Vosk and Whisper models)
  - Appearance (Keyboard theme, Giphy API)
  - Permissions (Microphone, accessibility, keyboard setup)
  - History (existing)
  - Word Dictionary (existing)
  - About (Version, changelog, debug logs)

### Changed
- Settings navigation now uses SEAL-downloader style with icon + title + subtitle
- Each settings item shows chevron to indicate it opens a sub-screen
- Better organization makes it easier to find and change settings

---

## [1.1.2] - 2026-01-08

### Fixed
- **APK signing** - Configured release signing with consistent keystore for seamless updates

---

## [1.1.1] - 2026-01-08

### Fixed
- **APK signing for seamless updates** - Fixed signing key inconsistency that prevented users from updating the app over previous versions. All builds now use a consistent release signing configuration.

### Added
- **Keystore generation script** - Added `generate-keystore.sh` to help developers create and configure release signing keys

---

## [1.1.0] - 2026-01-08

### Fixed
- **Crash on dictation start** - Added null safety checks and error handling throughout the app
- **Dark mode readability** - Text is now properly visible with high contrast colors
- **Icons appearance** - Fixed icon tinting issues in both light and dark modes

### Added
- **Comprehensive logging** - Added debug logging for troubleshooting (filter by "TinyDictation" in Logcat)
- **Better error messages** - App now shows helpful error messages instead of crashing
- **Telegram notifications** - Get notified when new releases are available
- **Direct APK downloads** - Releases now provide direct APK download links

### Changed
- Improved AudioRecorder initialization with proper exception handling
- TranscriptionManager now verifies permissions before starting
- DeepgramClient handles WebSocket errors gracefully
- All icons now use theme-aware colors

---

## [1.0.0] - 2026-01-08

### Initial Release
- Floating bubble dictation interface
- Real-time voice transcription via Deepgram API
- Voice Activity Detection (VAD) using TensorFlow Lite
- Accessibility service for text injection into any app
- Secure API key storage
- Dark mode support

