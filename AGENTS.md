# AGENTS.md - Tiny Dictation Android

## Overview
This is an Android keyboard app built with Kotlin that provides voice dictation capabilities. The app uses Android's Input Method Framework to replace the system keyboard.

## Build System & Commands

### Build Commands
```bash
# Standard debug build (recommended)
nice -n 19 ./gradlew assembleDebug --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx512m"

# Release build
./gradlew assembleRelease

# Clean and rebuild
./gradlew clean assembleDebug
```

### Output Locations
- Debug APK: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
- Debug APK (armeabi-v7a): `app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk`
- Release APK: `app/build/outputs/apk/release/`

### Gradle Configuration
- **Gradle version**: 8.2
- **Android SDK**: API 34 (compileSdk)
- **Min SDK**: API 26
- **Build tools**: Latest available
- **Kotlin version**: Latest configured in gradle.build.kts

### Testing Commands
```bash
# Run all instrumented tests
./gradlew connectedDebugAndroidTest

# Run unit tests
./gradlew testDebugUnitTest

# Run single test (replace with actual test class)
./gradlew testDebugUnitTest --tests "com.tinydictation.android.AudioRecorderTest"

# Lint checks
./gradlew lintDebug

# Type checking (via KAPT if configured)
./gradlew compileDebugKotlin
```

## Mandatory Workflow (NO EXCEPTIONS)
Every code change MUST follow this sequence:

1. **Make code changes**
2. **Update version in app/build.gradle.kts** (increment versionCode + versionName)
3. **Update changelog in SettingsActivity.kt** companion object
4. **Build**: `nice -n 19 ./gradlew assembleDebug --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx512m"`
5. **Copy APK**: `cp app/build/outputs/apk/debug/app-arm64-v8a-debug.apk /tmp/TinyDictation-v{VERSION}-arm64.apk`
6. **Send to Telegram** (see CLAUDE.md for credentials)
7. **COMMIT IMMEDIATELY**: `git add -A && git commit -m "v{VERSION}: {description}"`
8. **PUSH IMMEDIATELY**: `git push origin master`

## Code Style Guidelines

### Language
- **Primary language**: Kotlin
- **Target**: JVM 1.8+ (Android)

### Naming Conventions
- **Classes/Objects**: PascalCase (e.g., `DictationKeyboardService`, `AudioRecorder`)
- **Functions/Methods**: camelCase (e.g., `startDictation()`, `toggleMic()`)
- **Constants**: UPPER_SNAKE_CASE (e.g., `MAX_RETRY_ATTEMPTS`)
- **Variables**: camelCase (e.g., `isRecording`, `audioBuffer`)
- **Properties**: camelCase, nullable types use `?` (e.g., `audioRecorder: AudioRecorder?`)

### Imports Organization
```kotlin
// Android system imports (first)
import android.content.Context
import android.media.AudioRecord
import android.Manifest

// Androidx imports (second)
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModel

// Third-party libraries (third, alphabetical)
import com.deepgram.api.DeepgramClient
import kotlinx.coroutines.*

// Project imports (last, full path)
import com.tinydictation.android.AudioRecorder
import com.tinydictation.android.ui.DictationBar
```

### Formatting & Structure
- **Indentation**: 4 spaces (Kotlin standard)
- **Line length**: 100 characters max
- **Braces**: K&R style
```kotlin
class DictationManager(private val context: Context) {

    fun startRecording() {
        try {
            audioRecorder?.startRecording()
            isRecording = true
        } catch (e: Exception) {
            handleRecordingError(e)
        }
    }
}
```
- **Colon spacing**: No space before colon (`: `), space after
```kotlin
class AudioRecorder(private val context: Context) : Thread() {
    // Correct
}
```

### Type Annotations & Nullability
- **Nullable types**: Use `?` operator explicitly
- **Avoid `!!`**: Use safe calls `?.` and elvis operator `?:`
- **Late initialization**: Use `lateinit var` for non-null properties set after init
```kotlin
private lateinit var audioRecord: AudioRecord
private var currentTranscription: String? = null

fun initialize() {
    audioRecord = AudioRecord(...)
}

fun getCurrentTranscription(): String {
    return currentTranscription ?: ""
}
```

### Error Handling
- **Exceptions**: Catch specific exceptions, not generic Exception
- **Logging**: Use Android Log utility or custom DebugLog
- **Resources**: Properly close/closeable resources in finally blocks or use `use{}` 
```kotlin
// Preferred: use() extension
audioRecord.use { recorder ->
    recorder.startRecording()
    // Process audio
}

// Manual cleanup if needed
try {
    audioRecord.startRecording()
    isRecording = true
} catch (e: IllegalStateException) {
    Log.e(TAG, "Failed to start recording", e)
    handleRecordingError(e)
} catch (e: SecurityException) {
    requestPermissions()
} finally {
    audioRecord.release()
}
```

### Android Specific Guidelines

#### Permissions
- Check permissions before using features requiring them
- Request permissions at appropriate times
- Handle permission denial gracefully

#### Coroutines & Threading
- Use Kotlin coroutines for async operations
- Use appropriate dispatchers (IO for network/DB, Main for UI)
- Avoid blocking operations on main thread

#### Resources & Context
- Use application context when possible
- Properly handle activity/fragment lifecycle
- Follow Android memory management best practices

#### UI Components (Compose)
- Use `@Composable` functions for UI
- Follow Jetpack Compose best practices
- Use `remember{}` for state that survives recomposition
- Handle side effects with `LaunchedEffect`

#### Services & Lifecycle
- Properly manage Android service lifecycle
- Use `onCreate()`, `onDestroy()` appropriately
- Handle foreground/background transitions

### File Organization
- **Package structure**: `com.tinydictation.android.*`
- **Subpackages**:
  - `.ui.*` - UI related classes
  - `.service.*` - Android services
  - `.transcription.*` - Transcription engines
  - `.utils.*` - Utility classes

### Testing Guidelines
- Use JUnit for unit tests
- Use Espresso for UI tests
- Mock dependencies using Mockito
- Place tests in `app/src/test/` (unit) or `app/src/androidTest/` (instrumented)

### Security Practices
- No secrets in code (API keys should be in secure config)
- Validate input data
- Use HTTPS for network calls
- Handle sensitive data appropriately

### Documentation
- Use KDoc for public API docs
- Inline comments for complex logic
- Clear, descriptive names over comments

### Version Management
- Update `versionCode` and `versionName` in `app/build.gradle.kts`
- Document changes in `SettingsActivity.kt` CHANGELOG
- Semantic versioning: patch.minor.major

### CI/CD Integration
- GitHub Actions workflows in `.github/workflows/`
- Release artifacts via GitHub Releases
- APK signing via keystore (see `SINGING.md`)

## Project-Specific Rules

### Dictation Keyboard Architecture
- **Dictation Bar**: Always visible bottom bar with MIC button
- **Tabbed Panel**: Slide-out panels above dictation bar
- **MIC Button**: Centered for thumb access, auto-starts dictation
- **Scroll behavior**: MIC stays centered on screen

### Audio Configuration
- Sample rate: 16kHz
- Format: PCM 16-bit mono  
- Chunk size: 20ms
- Audio source: VOICE_RECOGNITION

### Transcription Engines
- Deepgram (cloud) - requires API key
- Vosk (offline) - ~45MB models
- Sherpa-ONNX/Whisper (offline) - high quality, ~75MB

### Accessibility Setup
- Enable accessibility service in Settings > Accessibility
- Requires Android 13+ manifest permissions

This document serves as a quick reference for agents working in this codebase. Always verify current practices by examining existing code in the repo.