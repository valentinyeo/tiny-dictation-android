# Changelog

All notable changes to Tiny Dictation will be documented in this file.

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
