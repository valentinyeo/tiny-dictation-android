# Changelog

All notable changes to Tiny Dictation will be documented in this file.

## [1.43.0]

- The app can now update itself
- Checks for new versions and shows a notification
- Settings has Download and update

## [1.42.0]

- Brings back the v1.39.1 to v1.41.3 features together with clear Deepgram error messages and speech-only streaming
- Restores unified correction toggles, playback auto-pause/resume, swipe gestures, prediction-strip sizing, fat-finger correction and screen-awake button
- Restores keyboard/navigation-bar fixes, larger high-contrast tabs, headset mic routing and codebase health improvements
- Version code 368 is above all earlier releases, so this update installs over v1.41.x

## [1.41.3]

- Tab labels bumped up again: 13sp -> 16sp for easier reading

## [1.41.2]

- Tab labels (ABC/123/Agent/Shell/Snip/Clip/Hist): bigger (13sp) and higher contrast
- Active tab: light purple; inactive tabs: light gray (readable on black)
- Removed the thin selected-tab underline bar

## [1.41.1]

- Fix headset mic: use DEFAULT audio source instead of VOICE_RECOGNITION when headset selected
- VOICE_RECOGNITION ignores setPreferredDevice() on many Android devices, routing to built-in mic

## [1.41.0]

- Codebase health: extract QwertyDisplayHelper (130 lines out of god-class)
- Add 37 unit tests for VoiceCommandProcessor
- Add .editorconfig, Gradle dependency locking
- Add .claude/settings.json for AI agent permissions

## [1.40.3]

- Fix white bar: fitsSystemWindows + disable nav bar contrast enforcement

## [1.40.0]

- Fat finger correction: taps between keys now register on nearest key
- Screen awake button: keep screen on while keyboard is open (sun icon in dictation bar)

## [1.39.5]

- Fix white bar below keyboard in light/day mode

## [1.39.4]

- Fix: hide prediction strip AND shrink container so key sizes stay identical
- Keyboard is now 50dp shorter when predictions are off

## [1.39.3]

- Swipe up on dictation bar to open keyboard panel
- Swipe down on keyboard panel to close it

## [1.39.2]

- Auto-pause transcription when phone plays audio (WhatsApp voice messages, YouTube, music)
- Auto-resume when audio stops — no manual unmute needed
- Prevents mic from transcribing phone speaker output

## [1.39.1]

- Unified "Corrections" settings page — all correction features in one place
- Three sections: During Dictation (Deepgram), After Dictation (AI), While Typing
- Every correction layer is now independently toggleable
- Moved correction toggles out of Appearance into dedicated Corrections page

## [1.39.0]

- Sends audio to Deepgram only while you speak; pauses send a keep-alive instead of silence
- Closes the stream after 4 seconds of silence and never streams longer than 2 minutes in one go
- Stops listening at once when the keyboard hides or the screen turns off
- Fixed dictation stopping with a false connection error after a short pause
- Tags every Deepgram request tiny-dictation and logs streamed seconds per day
- Toggle every feature on/off: Smart Format, Auto Punctuation, Numerals, Dictation (Deepgram Features)
- Toggle AI features: Next-Word Prediction, Word Completion, Auto-Fix Sentences (Appearance)
- Disabling features reduces latency — fewer API calls = faster keyboard
- Smart Format and Auto Punctuation now respect user settings (were previously hardcoded on)

## [1.38.1]

- Clear message when Deepgram rejects the API key, runs out of credit or the connection drops; stops retrying on a dead key.
- Settings to disable word predictions and autocorrect (Appearance → toggles)
- Auto-mute mic when opening QWERTY keyboard panel for typing (grey mic)
- Manual unmute required to resume dictation after typing

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

