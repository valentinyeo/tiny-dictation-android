# /send - Send APK to Telegram

## IMPORTANT: Automatic Testing Workflow

**Every build MUST be sent to Telegram for testing.** The user cannot test APKs locally - they need to be sent to Telegram to install on their phone. After completing any code changes and building an APK, ALWAYS send it to Telegram immediately. Do not ask "do you want me to send it?" - just send it.

## ⚠️ CRITICAL: COMMIT AFTER SENDING ⚠️

**After sending to Telegram, you MUST commit immediately:**
```bash
git add -A && git commit -m "v{VERSION}: {Brief description}"
```

**Never skip this step.** Previous sessions lost 8 versions of work by not committing.

## What This Skill Does

Sends the latest built APK to Telegram for testing on the user's device.

## Prerequisites

- APK must be built first (run `nice -n 19 ./gradlew assembleDebug --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx512m"`)
- APK must be copied to `/tmp/TinyDictation-v{VERSION}-arm64.apk`
- Telegram credentials in `~/.telegram-deploy` (TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID)

## Steps to Execute

1. **Get the current version** from `app/build.gradle.kts` (look for `versionName`)

2. **Get the changelog** from `app/src/main/java/com/tinydictation/android/SettingsActivity.kt` (the latest entry in CHANGELOG)

3. **Verify APK exists** at `/tmp/TinyDictation-v{VERSION}-arm64.apk`
   - If not, check if it exists in `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` and copy it

4. **Send to Telegram** using:
```bash
source ~/.telegram-deploy && curl -s -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendDocument" \
  -F chat_id="${TELEGRAM_CHAT_ID}" \
  -F document=@"/tmp/TinyDictation-v{VERSION}-arm64.apk" \
  -F caption="v{VERSION}
{CHANGELOG_BULLETS}"
```

5. **Confirm success** - the curl response should contain `"ok":true`

## Example

For version 1.11.51 with changelog:
```
• UI: Mic icon now red when hot (standby or transcribing)
• UI: Subtle pulse animation when actively transcribing (incurs cost)
```

The command would be:
```bash
source ~/.telegram-deploy && curl -s -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendDocument" \
  -F chat_id="${TELEGRAM_CHAT_ID}" \
  -F document=@"/tmp/TinyDictation-v1.11.51-arm64.apk" \
  -F caption="v1.11.51
• UI: Mic icon now red when hot (standby or transcribing)
• UI: Subtle pulse animation when actively transcribing (incurs cost)"
```
