# APK Signing Configuration

## Setup

The private release keystore lives only in the 1Password vault **Yeo UX Agents**,
item **Multiprompt Android signing**. Do not generate a replacement or commit it.

Repository Actions secrets supply the signing identity:

- `KEYSTORE_BASE64`: base64-encoded keystore from that item
- `KEYSTORE_PASSWORD`: keystore password
- `KEY_ALIAS`: `multiprompt-companion`
- `KEY_PASSWORD`: key password

The release and CI workflows decode the keystore into
`$RUNNER_TEMP/release.keystore` with private permissions. They export
`RELEASE_KEYSTORE_FILE`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and
`RELEASE_KEY_PASSWORD` through `GITHUB_ENV` for `./gradlew assembleRelease`, then
immediately delete the temporary keystore and clear the variables, even on failure.
Missing release credentials fail the build; there is no debug fallback.
Debug builds retain their separate development key.

Before publication, `tools/check_release_apk_signer.py` checks every release APK
against private certificate SHA-256
`25FD78F3BE97F357E7689FBF738C6670BF10F352EED4403DC93A72E03476C0F`.
The manual **Check APK signing identity** workflow can check a historical APK URL.
This checker reads the embedded certificate; it does not validate APK integrity.

Previously debug-signed installations cannot update in place to this private
identity. They must uninstall and reinstall, which can remove local app data.
