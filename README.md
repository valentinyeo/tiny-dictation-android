# Tiny Dictation for Android

A floating bubble dictation app for Android that provides seamless, low-latency voice-to-text input into any app.

## Features

- **Floating Bubble UI**: Draggable overlay that stays on top of all apps
- **Continuous Listening**: Voice Activity Detection (VAD) automatically detects when you start/stop speaking
- **Real-time Transcription**: Streams audio to Deepgram for ultra-low latency (~300ms)
- **Universal Text Injection**: Uses Accessibility Service to insert text into any focused text field
- **Privacy-focused**: No audio stored, only streams during active speech

## Setup

### 1. Get a Deepgram API Key
1. Sign up at [deepgram.com](https://deepgram.com)
2. Create an API key with "Usage" scope
3. Copy the key

### 2. Install the App
Download the latest APK from [GitHub Actions](../../actions) artifacts.

### 3. Grant Permissions
The app requires three permissions:
- **Microphone**: To record your voice
- **Display Over Other Apps**: To show the floating bubble
- **Accessibility Service**: To inject text into other apps

### 4. Configure
1. Open the app
2. Go to Settings
3. Enter your Deepgram API key
4. Adjust VAD sensitivity if needed

### 5. Use
1. Tap "Start Dictation" to show the floating bubble
2. Tap the bubble to activate listening mode
3. Speak naturally - text will appear wherever your cursor is
4. Tap the bubble again to deactivate

## Building

### Prerequisites
- Android Studio Hedgehog or later
- JDK 17
- Android SDK 34

### Build Debug APK
```bash
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`

## CI/CD

This project uses GitHub Actions for continuous builds:
- Every push to `main` triggers a build
- APKs are uploaded as artifacts
- Optional: Telegram notifications when builds complete

### Setting up Telegram Notifications
1. Create a bot with @BotFather on Telegram
2. Get your chat ID from @userinfobot
3. Add these secrets to your GitHub repo:
   - `TELEGRAM_BOT_TOKEN`: Your bot token
   - `TELEGRAM_CHAT_ID`: Your chat ID

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Android App                               │
├─────────────────────────────────────────────────────────────────┤
│  ┌─────────────────┐    ┌─────────────────┐    ┌──────────────┐ │
│  │ Floating Bubble │    │  Audio Pipeline  │    │ Accessibility│ │
│  │    Service      │◄──►│   (Recording)    │◄──►│   Service    │ │
│  │  (Foreground)   │    │                  │    │ (Text Input) │ │
│  └────────┬────────┘    └────────┬─────────┘    └──────────────┘ │
│           │                      │                               │
│           ▼                      ▼                               │
│  ┌─────────────────┐    ┌─────────────────┐                     │
│  │   Bubble UI     │    │       VAD       │                     │
│  │                 │    │  (Amplitude)    │                     │
│  └─────────────────┘    └────────┬────────┘                     │
│                                  │                               │
│                                  ▼                               │
│                         ┌─────────────────┐                     │
│                         │ Deepgram Client │                     │
│                         │  (WebSocket)    │                     │
│                         └─────────────────┘                     │
└─────────────────────────────────────────────────────────────────┘
```

## Tech Stack

- **Kotlin** with Coroutines
- **OkHttp** for WebSocket streaming
- **Material 3** design system
- **EncryptedSharedPreferences** for secure API key storage

## Future Enhancements

- [ ] Silero VAD for improved speech detection
- [ ] Local Whisper support (offline mode)
- [ ] German language support
- [ ] Text replacements/expansions
- [ ] Voice commands ("new line", "period", etc.)

## License

MIT License - See LICENSE file for details
# Tiny Dictation v1.1.2
