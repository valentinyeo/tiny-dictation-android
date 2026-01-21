# /send - Build, Send APK to Telegram, and Commit

## ⚠️ COMPLETE WORKFLOW - ALL STEPS REQUIRED ⚠️

**Every version change MUST follow this exact sequence:**

### Step 1: Update Version Files

Update these files for EVERY version (current: versionCode=217, versionName=1.17.6):

1. **`app/build.gradle.kts`** - Increment both:
   - `versionCode` (integer, +1 from current)
   - `versionName` (e.g., "1.17.6" → "1.17.7")

2. **`app/src/main/java/com/tinydictation/android/settings/AboutActivity.kt`** - Add changelog entry at top of CHANGELOG companion object (line ~40):
   ```kotlin
   "v1.17.7 - 2025-01-21",
   "• Your change 1",
   "• Your change 2",
   ```

Note: Version display on keyboard and in Settings is automatic (reads from build.gradle.kts).

### Step 2: Build

```bash
nice -n 19 ./gradlew assembleDebug --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx512m"
```

### Step 3: Copy and Send to Telegram

```bash
VERSION=$(grep 'versionName = ' app/build.gradle.kts | grep -oE '"[^"]*"' | tr -d '"')
cp app/build/outputs/apk/debug/app-arm64-v8a-debug.apk /tmp/TinyDictation-v${VERSION}-arm64.apk

source ~/.telegram-deploy && curl -s -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendDocument" \
  -F chat_id="${TELEGRAM_CHAT_ID}" \
  -F document=@"/tmp/TinyDictation-v${VERSION}-arm64.apk" \
  -F caption="v${VERSION}
• Change 1
• Change 2"
```

### Step 4: COMMIT IMMEDIATELY

```bash
VERSION=$(grep 'versionName = ' app/build.gradle.kts | grep -oE '"[^"]*"' | tr -d '"')
git add -A && git commit -m "v${VERSION}: Brief description of changes"
git push origin master
```

**Never skip this step.** Previous sessions lost 8 versions of work by not committing.

---

## Why Send to Telegram?

The user cannot test APKs locally - they must be sent to Telegram to install on their phone. After ANY code change and build, ALWAYS send to Telegram immediately. Do not ask "do you want me to send it?" - just send it.

## Telegram Credentials

Stored in `~/.telegram-deploy`:
- TELEGRAM_BOT_TOKEN
- TELEGRAM_CHAT_ID
