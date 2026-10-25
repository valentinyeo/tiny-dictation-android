#!/usr/bin/env bash
set +x
set -euo pipefail

fail() {
    printf '%s\n' "$*" >&2
    exit 1
}

[[ $# == 1 || ( $# == 3 && ${2:-} == --dry-run && -n ${3:-} ) ]] ||
    fail "Usage: $0 <apk-path> [--dry-run <out-dir>]"
[[ -f $1 ]] || fail "APK not found: $1"
export -n CLOUDFLARE_API_TOKEN
unset CLOUDFLARE_ACCOUNT_ID

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
sdk=${ANDROID_HOME:-/home/valentin/tools/android-sdk}
aapt2=$(find "$sdk/build-tools" -mindepth 2 -maxdepth 2 -type f -name aapt2 \
    -executable | sort -V | tail -n 1)
[[ -n $aapt2 ]] || fail "aapt2 not found under $sdk/build-tools"
apksigner="$(dirname -- "$aapt2")/apksigner"
[[ -x $apksigner ]] || fail "apksigner not found alongside aapt2"

work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
cp -- "$1" "$work/release.apk"
"$apksigner" verify "$work/release.apk"
badging=$("$aapt2" dump badging "$work/release.apk")
pattern="^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'"
package_name=
while IFS= read -r line; do
    if [[ $line =~ $pattern ]]; then
        package_name=${BASH_REMATCH[1]}
        version_code=${BASH_REMATCH[2]}
        version_name=${BASH_REMATCH[3]}
        break
    fi
done <<< "$badging"
[[ $package_name == com.tinydictation.android ]] || fail "Unexpected APK package"
[[ $version_code =~ ^[1-9][0-9]{0,9}$ ]] || fail "Invalid versionCode"
jq -en --argjson code "$version_code" '$code <= 2147483647' >/dev/null ||
    fail "Invalid versionCode"
[[ $version_name =~ ^[a-zA-Z0-9][a-zA-Z0-9._+-]*$ ]] || fail "Unsafe versionName"

base=https://pub-bc49d639ec0a4a4382ac076d5e94916c.r2.dev/android-latest
manifest_name=tinydictation-update.json
sha256=$(sha256sum "$work/release.apk")
sha256=${sha256%% *}
release_date=$(date -u +%F)
apk_name="TinyDictation-$version_name-arm64.apk"
status=$(curl --silent --show-error --location --header 'Cache-Control: no-cache' \
    --output "$work/previous.json" --write-out '%{http_code}' "$base/$manifest_name")
case $status in
    200)
        jq -e 'type == "object" and (.versionCode | type == "number") and
            (.versionCode > 0 and .versionCode <= 2147483647) and
            (.versionCode == (.versionCode | floor)) and
            ((.history // []) | type == "array" and all(.[]; type == "object"))' \
            "$work/previous.json" >/dev/null || fail "Invalid published manifest"
        if jq -e --argjson code "$version_code" --arg sha256 "$sha256" \
            '$code == .versionCode and $sha256 == .sha256' \
            "$work/previous.json" >/dev/null; then
            printf 'Update already published: %s (%s)\n' "$version_name" "$version_code"
            exit 0
        fi
        jq -e --argjson code "$version_code" '$code > .versionCode' \
            "$work/previous.json" >/dev/null ||
            fail "versionCode must be greater than the published versionCode"
        jq --arg date "$release_date" \
            '[(del(.history) | .date = (.date // $date))] + (.history // []) | .[:20]' \
            "$work/previous.json" > "$work/history.json"
        ;;
    404) printf '[]\n' > "$work/history.json" ;;
    *) fail "Cannot read published manifest (HTTP $status)" ;;
esac

python3 - "$root" "$version_name" > "$work/notes.txt" <<'PY'
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1])
version = re.escape(sys.argv[2])
changelog = root / "CHANGELOG.md"
notes = None
if changelog.is_file():
    text = changelog.read_text(encoding="utf-8")
    entry = re.search(r"^##\s+\[v?" + version + r"\][^\n]*\n(.*?)(?=^##\s|\Z)",
                      text, re.M | re.S)
    if entry:
        notes = re.findall(r"^\s*[-*•]\s+(.+)$", entry[1], re.M)
if notes is None:
    for path in sorted((root / "app/src/main/java").rglob("AboutActivity.kt")):
        text = path.read_text(encoding="utf-8")
        changelog = re.search(r'\b(?:val|var)\s+CHANGELOG\s*=\s*"""(.*?)"""', text, re.S)
        if not changelog:
            continue
        entry = re.search(r"^\s*v" + version + r":\s*\n(.*?)(?=^\s*v[^\s:]+:|\Z)",
                          changelog[1], re.M | re.S)
        if entry:
            notes = re.findall(r"^\s*•\s+(.+)$", entry[1], re.M)
            break
if not notes:
    sys.exit("No changelog bullets found for " + sys.argv[2])
print("\n".join("• " + note for note in notes))
PY

size_bytes=$(stat -c %s "$work/release.apk")
jq -n --arg packageName "$package_name" --argjson versionCode "$version_code" \
    --arg versionName "$version_name" --arg apkUrl "$base/$apk_name" \
    --arg sha256 "$sha256" --argjson sizeBytes "$size_bytes" --arg date "$release_date" \
    --rawfile notes "$work/notes.txt" --slurpfile history "$work/history.json" \
    '{schemaVersion: 1, packageName: $packageName, versionCode: $versionCode,
      versionName: $versionName, date: $date, abi: "arm64-v8a", apkUrl: $apkUrl, sha256: $sha256,
      sizeBytes: $sizeBytes, notes: ($notes | rtrimstr("\n")), history: $history[0]}' \
    > "$work/$manifest_name"

if [[ $# == 3 ]]; then
    mkdir -p -- "$3"
    cp -- "$work/release.apk" "$3/$apk_name"
    cp -- "$work/$manifest_name" "$3/$manifest_name"
    printf 'Dry run written to %s\n' "$3"
    exit 0
fi

if [[ -z ${CLOUDFLARE_API_TOKEN:-} ]]; then
    labels=$(yeo-key "Tiny Dictation Updates R2")
    label=${labels%%$'\n'*}
    [[ -n $label ]] || fail "No Cloudflare token label found"
    CLOUDFLARE_API_TOKEN=$(yeo-key "Tiny Dictation Updates R2" "$label")
fi
[[ -n $CLOUDFLARE_API_TOKEN ]] || fail "Empty Cloudflare token"
CLOUDFLARE_API_TOKEN="$CLOUDFLARE_API_TOKEN" \
    CLOUDFLARE_ACCOUNT_ID=7865043d9ae730882b77f3d5d670c158 \
    npx --yes wrangler@3 r2 object put "tinydictation-updates/android-latest/$apk_name" \
    --file "$work/release.apk" --content-type application/vnd.android.package-archive
CLOUDFLARE_API_TOKEN="$CLOUDFLARE_API_TOKEN" \
    CLOUDFLARE_ACCOUNT_ID=7865043d9ae730882b77f3d5d670c158 \
    npx --yes wrangler@3 r2 object put "tinydictation-updates/android-latest/$manifest_name" \
    --file "$work/$manifest_name" --content-type application/json --cache-control no-cache
unset CLOUDFLARE_API_TOKEN

status=$(curl --silent --show-error --location --header 'Cache-Control: no-cache' \
    --output "$work/downloaded.json" --write-out '%{http_code}' "$base/$manifest_name")
[[ $status == 200 ]] || fail "Published manifest download failed (HTTP $status)"
jq -e --slurpfile expected "$work/$manifest_name" '. == $expected[0]' \
    "$work/downloaded.json" >/dev/null || fail "Published manifest does not match"
status=$(curl --silent --show-error --location --header 'Cache-Control: no-cache' \
    --output "$work/downloaded.apk" --write-out '%{http_code}' "$base/$apk_name")
[[ $status == 200 ]] || fail "Published APK download failed (HTTP $status)"
actual_sha256=$(sha256sum "$work/downloaded.apk")
[[ ${actual_sha256%% *} == "$sha256" ]] || fail "Published APK SHA-256 mismatch"
[[ $(stat -c %s "$work/downloaded.apk") == "$size_bytes" ]] ||
    fail "Published APK size mismatch"
printf 'Verified published update: %s (%s)\n' "$version_name" "$version_code"
