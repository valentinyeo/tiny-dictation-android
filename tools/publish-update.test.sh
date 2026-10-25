#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
python3 - "$root" "$work" <<'PY'
import datetime
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys

source, work = map(pathlib.Path, sys.argv[1:])
repo = work / "repo"
(repo / "tools").mkdir(parents=True)
publisher = source / "tools/publish-update.sh"
(repo / "tools/publish-update.sh").write_text(publisher.read_text().replace(
    "https://pub-bc49d639ec0a4a4382ac076d5e94916c.r2.dev/android-latest",
    "https://updates.example.invalid/android-latest"))
about = repo / "app/src/main/java/example/settings/AboutActivity.kt"
about.parent.mkdir(parents=True)
about.write_text('''private val CHANGELOG = """
    v1.2.3:
    • Kotlin fallback with "quotes"
    • Second fallback bullet

    v1.2.2:
    • Not this older entry
""".trimIndent()
''', encoding="utf-8")
apk = work / "fixture with spaces.apk"
apk.write_bytes(b"Synthetic APK bytes; metadata and signatures are supplied by isolated shims.\x00\xff")
base = "https://updates.example.invalid/android-latest/"
apk_name = "TinyDictation-1.2.3-arm64.apk"
manifest_name = "tinydictation-update.json"
markdown = '''# Changelog
## [1.2.3] - today
- Markdown wins with "quotes" and \\paths
- Second markdown bullet

## [1.2.2]
- Not this older entry
'''
bin_dir = work / "bin"
bin_dir.mkdir()
sdk = work / "sdk"
build_tools = sdk / "build-tools/99.0.0"
build_tools.mkdir(parents=True)
shim = r'''#!/usr/bin/env python3
import json
import os
import pathlib
import shutil
import sys

state = pathlib.Path(os.environ["PUBLISH_TEST_STATE"])
settings = json.loads((state / "settings.json").read_text())
command = pathlib.Path(sys.argv[0]).name
args = sys.argv[1:]
with (state / "calls.jsonl").open("a") as log:
    log.write(json.dumps({"command": command, "args": args,
                          "token_present": "CLOUDFLARE_API_TOKEN" in os.environ}) + "\n")
if command != "npx":
    assert "CLOUDFLARE_API_TOKEN" not in os.environ, "credential leaked beyond wrangler"
    assert "CLOUDFLARE_ACCOUNT_ID" not in os.environ, "account leaked beyond wrangler"
if command == "date":
    assert args == ["-u", "+%F"]
    print("2030-01-02")
elif command == "aapt2":
    assert args[:2] == ["dump", "badging"] and pathlib.Path(args[2]).is_file()
    print("package: name='%s' versionCode='%s' versionName='%s' platformBuildVersionName='14'" % (
        settings.get("package", "com.tinydictation.android"),
        settings.get("versionCode", 900), settings.get("versionName", "1.2.3")))
elif command == "apksigner":
    assert args[0] == "verify" and pathlib.Path(args[1]).is_file()
    sys.exit(1 if settings.get("bad_signature") else 0)
elif command in ("yeo-key", "npx", "wrangler"):
    assert settings.get("uploads"), "FORBIDDEN credential read or upload in dry-run"
    if command == "yeo-key":
        assert not settings.get("env_token"), "FORBIDDEN vault access with environment token"
        assert args in (["Tiny Dictation Updates R2"],
                        ["Tiny Dictation Updates R2", "first label with spaces"])
        print("first label with spaces\nunused label" if len(args) == 1 else "fixture-token")
    elif command == "npx":
        assert os.environ.get("CLOUDFLARE_API_TOKEN") == (settings.get("env_token") or "fixture-token")
        assert os.environ.get("CLOUDFLARE_ACCOUNT_ID") == "7865043d9ae730882b77f3d5d670c158"
        assert args[:5] == ["--yes", "wrangler@3", "r2", "object", "put"]
        key = args[5]
        assert key.startswith("tinydictation-updates/android-latest/")
        name = key.rsplit("/", 1)[1]
        file = args[args.index("--file") + 1]
        content_type = args[args.index("--content-type") + 1]
        is_manifest = name.endswith(".json")
        expected = ["--file", file, "--content-type", content_type]
        if is_manifest:
            expected += ["--cache-control", "no-cache"]
            assert (state / "remote/TinyDictation-1.2.3-arm64.apk").is_file()
        assert args[6:] == expected + ["--remote"]
        assert content_type == ("application/json" if is_manifest else
                                "application/vnd.android.package-archive")
        if settings.get("fail_upload") == ("manifest" if is_manifest else "apk"):
            sys.exit(1)
        (state / "remote").mkdir(exist_ok=True)
        shutil.copyfile(file, state / "remote" / name)
    else:
        sys.exit("FORBIDDEN direct wrangler call")
elif command == "curl":
    assert args[:5] == ["--silent", "--show-error", "--location", "--header",
                       "Cache-Control: no-cache"]
    output = pathlib.Path(args[args.index("--output") + 1])
    assert args[args.index("--write-out") + 1] == "%{http_code}"
    url = args[-1]
    assert url.startswith("https://updates.example.invalid/android-latest/")
    name = url.rsplit("/", 1)[1]
    remote = state / "remote" / name
    uploaded = (state / "remote/tinydictation-update.json").exists()
    if settings.get("network_error"):
        sys.exit(7)
    if uploaded:
        status = settings.get("verify_status", 200)
        data = remote.read_bytes()
        if name.endswith(".apk") and settings.get("corrupt_apk"):
            data = b"x" * len(data)
        if name.endswith(".apk") and settings.get("truncate_apk"):
            data = data[:-1]
        if name.endswith(".json") and settings.get("corrupt_manifest"):
            manifest = json.loads(data)
            manifest["versionCode"] += 1
            data = json.dumps(manifest).encode()
    else:
        assert name == "tinydictation-update.json"
        status = settings.get("status", 200 if (state / "previous.json").exists() else 404)
        data = (state / "previous.json").read_bytes() if status == 200 else b"HTTP error"
    output.write_bytes(data)
    print(status, end="")
else:
    sys.exit("Unexpected shim command")
'''
for command in ("date", "aapt2", "apksigner", "curl", "yeo-key", "npx", "wrangler"):
    path = (build_tools if command in ("aapt2", "apksigner") else bin_dir) / command
    path.write_text(shim)
    path.chmod(0o755)

sha_shim = bin_dir / "sha256sum"
sha_shim.write_text('''#!/usr/bin/env python3
import hashlib
import json
import os
import pathlib
import sys
settings = json.loads((pathlib.Path(os.environ["PUBLISH_TEST_STATE"]) / "settings.json").read_text())
path = pathlib.Path(sys.argv[1])
sha = "0" * 64 if settings.get("fixture_sha") else hashlib.sha256(path.read_bytes()).hexdigest()
print(sha + "  " + str(path))
''')
sha_shim.chmod(0o755)

cases = 0

def run(name, *, previous=None, settings=None, dry=True, error=None, md=markdown,
        env_token=None, trace=False):
    global cases
    state = work / name
    state.mkdir()
    config = dict(settings or {})
    config["uploads"] = not dry
    config["env_token"] = env_token
    (state / "settings.json").write_text(json.dumps(config))
    if previous is not None:
        (state / "previous.json").write_text(
            previous if isinstance(previous, str) else json.dumps(previous))
    (repo / "CHANGELOG.md").write_text(md, encoding="utf-8")
    env = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ["PATH"],
               ANDROID_HOME=str(sdk), PUBLISH_TEST_STATE=str(state),
               CLOUDFLARE_ACCOUNT_ID="inherited-fixture-account")
    env.pop("CLOUDFLARE_API_TOKEN", None)
    if env_token is not None:
        env["CLOUDFLARE_API_TOKEN"] = env_token
    args = ["bash", *(["-x"] if trace else []), str(repo / "tools/publish-update.sh"), str(apk)]
    if dry:
        args += ["--dry-run", str(state / "output with spaces")]
    result = subprocess.run(args, env=env, capture_output=True, text=True)
    output = result.stdout + result.stderr
    assert "fixture-token" not in output, name + ": credential printed"
    if env_token:
        assert env_token not in output, name + ": environment credential printed"
    calls = [json.loads(line) for line in (state / "calls.jsonl").read_text().splitlines()]
    assert "fixture-token" not in json.dumps(calls), name + ": credential in command arguments"
    if env_token:
        assert env_token not in json.dumps(calls), name + ": environment credential in arguments"
        assert not any(call["command"] == "yeo-key" for call in calls), name + ": vault accessed"
    if error is None:
        assert result.returncode == 0, name + ": " + output
    else:
        assert result.returncode != 0, name + ": unexpectedly succeeded"
        if error:
            assert error in output, name + ": missing expected error: " + output
    if dry:
        assert not any(call["command"] in ("yeo-key", "npx", "wrangler") for call in calls), name
    cases += 1
    return state, calls

def manifest_at(state, dry=True):
    return json.loads((state / ("output with spaces" if dry else "remote") / manifest_name).read_text())

state, calls = run("first-publish")
manifest = manifest_at(state)
assert manifest == {
    "schemaVersion": 1, "packageName": "com.tinydictation.android", "versionCode": 900,
    "versionName": "1.2.3", "date": "2030-01-02", "abi": "arm64-v8a", "apkUrl": base + apk_name,
    "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "sizeBytes": apk.stat().st_size,
    "notes": '• Markdown wins with "quotes" and \\paths\n• Second markdown bullet', "history": []}
assert (state / "output with spaces" / apk_name).read_bytes() == apk.read_bytes()
assert [call["command"] for call in calls] == ["apksigner", "aapt2", "date", "curl"]

state, _ = run("kotlin-fallback", md="# Changelog\n## [1.2.2]\n- Wrong version\n")
assert manifest_at(state)["notes"] == '• Kotlin fallback with "quotes"\n• Second fallback bullet'
state, _ = run("newest-matching-entry", md=markdown + "\n## [1.2.3]\n- Older duplicate\n")
assert manifest_at(state)["notes"] == manifest["notes"]

previous = dict(manifest, versionCode=899, versionName="1.2.2", date="2029-12-31", history=[
    {"versionCode": 898 - i, "versionName": "older-" + str(i), "notes": "retained"}
    for i in range(25)])
state, _ = run("history-cap", previous=previous)
history = manifest_at(state)["history"]
assert history == [{key: value for key, value in previous.items() if key != "history"}] + previous["history"][:19]
assert len(history) == 20 and "history" not in history[0]
state, _ = run("missing-history", previous={"versionCode": 899, "versionName": "1.2.2"})
assert manifest_at(state)["history"] == [
    {"versionCode": 899, "versionName": "1.2.2", "date": "2030-01-02"}]
state, _ = run("null-previous-date", previous={"versionCode": 899, "date": None})
assert manifest_at(state)["history"][0]["date"] == "2030-01-02"
state, _ = run("contract-fixture", settings={"fixture_sha": True}, previous={
    "versionCode": 899, "versionName": "1.2.2", "date": "2029-12-31",
    "notes": "Synthetic previous release"})
fixture = state / "output with spaces" / manifest_name
contract = manifest_at(state)
assert contract["date"] == "2030-01-02"
assert contract["history"][0]["date"] == "2029-12-31"
assert contract["sha256"] == "0" * 64
assert contract["apkUrl"].startswith("https://updates.example.invalid/")
for release in [contract] + contract["history"]:
    datetime.date.fromisoformat(release["date"])
fixture_path = source / "app/src/test/resources/update/published-manifest.json"
if os.environ.get("PUBLISH_UPDATE_WRITE_FIXTURE") == "1":
    fixture_path.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(fixture, fixture_path)
assert fixture_path.read_bytes() == fixture.read_bytes(), "publisher/parser fixture drift"
for env_token in (None, "ci-fixture-token"):
    for dry in (False, True):
        state, calls = run("identical-rerun-" + str(cases), previous=manifest, dry=dry,
                           env_token=env_token, trace=True)
        assert [call["command"] for call in calls] == ["apksigner", "aapt2", "date", "curl"]
        assert not (state / "remote").exists()
        assert not (state / "output with spaces").exists()
    for code, sha in ((900, None), (900, "f" * 64), (901, manifest["sha256"])):
        state, calls = run("reject-version-" + str(cases), dry=False,
                           previous=dict(manifest, versionCode=code, sha256=sha),
                           env_token=env_token, error="must be greater")
        assert not any(call["command"] in ("yeo-key", "npx", "wrangler") for call in calls)
state, calls = run("ci-env-publish", dry=False, previous=previous,
                   env_token="ci-fixture-token", trace=True)
assert manifest_at(state, dry=False)["history"] == history
assert [call["command"] for call in calls] == [
    "apksigner", "aapt2", "date", "curl", "npx", "npx", "curl", "curl"]
assert all(call["token_present"] == (call["command"] == "npx") for call in calls)
run("ci-env-dry-run", previous=previous, env_token="ci-fixture-token")
state, calls = run("reject-before-credentials", dry=False,
                   previous={"versionCode": 900}, error="must be greater")
assert not any(call["command"] in ("yeo-key", "npx", "wrangler") for call in calls)
for value in ("{", {"versionCode": "899"}, {"versionCode": 899.5},
              {"versionCode": 899, "history": {}}, {"versionCode": 899, "history": [1]}):
    run("bad-manifest-" + str(cases), previous=value, error="Invalid published manifest")
run("server-error", settings={"status": 500}, error="HTTP 500")
run("network-error", settings={"network_error": True}, error="")
run("package-mismatch", settings={"package": "org.example.wrong"}, error="Unexpected APK package")
run("bad-signature", settings={"bad_signature": True}, error="")
run("unsafe-version-name", settings={"versionName": "../escape"}, error="Unsafe versionName")
run("invalid-version-code", settings={"versionCode": 2147483648}, error="Invalid versionCode")
run("missing-notes", settings={"versionName": "9.9.9"}, error="No changelog bullets found")
run("empty-matching-notes", md="## [1.2.3]\nNo bullets\n", error="No changelog bullets found")

state, calls = run("publish-success", dry=False, previous=previous, trace=True)
assert manifest_at(state, dry=False)["history"] == history
assert [call["command"] for call in calls] == [
    "apksigner", "aapt2", "date", "curl", "yeo-key", "yeo-key", "npx", "npx", "curl", "curl"]
assert calls[4]["args"] == ["Tiny Dictation Updates R2"]
assert calls[5]["args"] == ["Tiny Dictation Updates R2", "first label with spaces"]
assert calls[6]["args"][5].endswith(apk_name)
assert calls[7]["args"][5].endswith(manifest_name)
assert calls[-2]["args"][-1] == base + manifest_name
assert calls[-1]["args"][-1] == base + apk_name
assert all(call["token_present"] == (call["command"] == "npx") for call in calls)

state, calls = run("empty-env-vault-fallback", dry=False, env_token="")
assert sum(call["command"] == "yeo-key" for call in calls) == 2

for key, message in (("corrupt_apk", "SHA-256 mismatch"),
                     ("truncate_apk", "SHA-256 mismatch"),
                     ("corrupt_manifest", "Published manifest does not match"),
                     ("verify_status", "Published manifest download failed")):
    run(key, dry=False, settings={key: 503 if key == "verify_status" else True}, error=message)
state, calls = run("apk-upload-failure", dry=False, settings={"fail_upload": "apk"}, error="")
assert sum(call["command"] == "npx" for call in calls) == 1
assert not (state / "remote" / manifest_name).exists()
state, calls = run("manifest-upload-failure", dry=False, settings={"fail_upload": "manifest"}, error="")
assert sum(call["command"] == "npx" for call in calls) == 2
assert not (state / "remote" / manifest_name).exists()
print("PUBLISH_UPDATE_TESTS_OK: " + str(cases) + " isolated cases")
PY
