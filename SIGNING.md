# APK Signing Configuration

This document explains how to set up release signing for the Tiny Dictation Android app to ensure users can seamlessly update the app.

## Problem

Android requires apps to be signed with a consistent key. If different builds use different signing keys, users cannot install updates over previous versions - they must uninstall the old version first, losing all app data.

## Solution

We've configured the app to use a consistent release signing key across all builds (including debug builds in development). The signing configuration reads from environment variables, allowing:

- **GitHub Actions**: Uses secrets stored in repository settings
- **Local development**: Uses environment variables or falls back to debug keystore

## Setting Up Signing

### Step 1: Generate a Release Keystore

Run the provided script to generate a keystore:

```bash
./generate-keystore.sh
```

This will:
1. Prompt you for keystore details (passwords, alias, certificate info)
2. Generate a `release.keystore` file
3. Display the base64-encoded keystore and all secrets needed for GitHub

### Step 2: Configure GitHub Secrets

Add the following secrets to your GitHub repository:

1. Go to **Settings** > **Secrets and variables** > **Actions**
2. Click **New repository secret**
3. Add these four secrets (values provided by the script):
   - `KEYSTORE_BASE64` - Base64-encoded keystore file
   - `KEYSTORE_PASSWORD` - Keystore password
   - `KEY_ALIAS` - Key alias (e.g., "tinydictation-key")
   - `KEY_PASSWORD` - Key password

### Step 3: Secure Your Keystore

**CRITICAL**:

- Store `release.keystore` in a **secure, backed-up location**
- **NEVER** commit the keystore file to git (it's in `.gitignore`)
- **NEVER** share your passwords publicly
- If you lose the keystore, you **cannot update your app** - users must uninstall and reinstall

## Building Locally with Release Signing

To build locally with the same signing key used in CI:

```bash
export KEYSTORE_BASE64="<your-base64-keystore>"
export KEYSTORE_PASSWORD="<your-keystore-password>"
export KEY_ALIAS="<your-key-alias>"
export KEY_PASSWORD="<your-key-password>"

./gradlew assembleDebug
```

## Building Without Release Signing

If you don't set the environment variables, the build will fall back to the Android debug keystore (`~/.android/debug.keystore`). This is fine for local testing but should not be used for releases.

## How It Works

The `app/build.gradle.kts` file includes a `signingConfigs` block that:

1. Checks for signing environment variables
2. If found, decodes `KEYSTORE_BASE64` to a temporary file and uses it
3. If not found, falls back to the Android debug keystore
4. Both debug and release build types use this same signing config

This ensures all builds (CI and local) use consistent signing, allowing seamless app updates.

## Verifying Signing

To verify your APK is properly signed:

```bash
# Extract signing certificate fingerprint
keytool -printcert -jarfile app/build/outputs/apk/debug/app-debug.apk

# Compare with your keystore
keytool -list -v -keystore release.keystore -alias <your-alias>
```

The SHA-256 fingerprints should match.

## Troubleshooting

### "App not installed" error
- The signing key doesn't match the installed version
- Uninstall the old version first, then install the new APK

### Build fails with keystore error
- Verify environment variables are set correctly
- Check that `KEYSTORE_BASE64` is valid base64
- Ensure passwords and alias match the keystore

### Lost keystore file
- Check your secure backup location
- If truly lost, you must create a new keystore and release a new app (users cannot update, must reinstall)
