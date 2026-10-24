import argparse
from http.client import HTTPException
import json
from pathlib import Path
import re
import sys
from urllib.request import Request, urlopen


MANIFEST_URL = (
    "https://pub-bc49d639ec0a4a4382ac076d5e94916c.r2.dev/"
    "android-latest/tinydictation-update.json"
)
RELEASE_URL = "https://api.github.com/repos/valentinyeo/tiny-dictation-android/releases/latest"


def read_json(file, url):
    if file is not None:
        return json.loads(Path(file).read_text(encoding="utf-8"))
    request = Request(url, headers={
        "User-Agent": "TinyDictation-update-channel-check",
        "Accept": "application/json",
        "Cache-Control": "no-cache",
    })
    with urlopen(request, timeout=30) as response:
        return json.load(response)


def version_parts(version):
    if not isinstance(version, str) or not re.fullmatch(r"[0-9]+(?:\.[0-9]+)*", version):
        raise ValueError("expected a numeric dotted version")
    return tuple(int(part) for part in version.split("."))


def main():
    parser = argparse.ArgumentParser()
    manifest_source = parser.add_mutually_exclusive_group()
    manifest_source.add_argument("--manifest-url", default=MANIFEST_URL)
    manifest_source.add_argument("--manifest-file")
    parser.add_argument("--release-file")
    args = parser.parse_args()

    versions = {"manifest": "unavailable", "release": "unavailable"}
    parts = {}
    errors = []
    for label, file, url, key in (
        ("manifest", args.manifest_file, args.manifest_url, "versionName"),
        ("release", args.release_file, RELEASE_URL, "tag_name"),
    ):
        try:
            document = read_json(file, url)
            if not isinstance(document, dict) or key not in document:
                raise ValueError(f"expected a JSON object with {key}")
            version = document[key]
            if label == "release" and isinstance(version, str):
                version = version.removeprefix("v")
            parts[label] = version_parts(version)
            versions[label] = version
        except (OSError, UnicodeError, ValueError, HTTPException) as error:
            detail = " ".join(str(error).splitlines())
            errors.append(f"cannot read or parse {label}: {detail}")

    if not errors:
        width = max(len(parts["manifest"]), len(parts["release"]))
        manifest = parts["manifest"] + (0,) * (width - len(parts["manifest"]))
        release = parts["release"] + (0,) * (width - len(parts["release"]))
        if release > manifest:
            errors.append("newest GitHub release is ahead of the published manifest")

    prefix = f"manifest {versions['manifest']}, release {versions['release']}"
    if errors:
        print(f"Update channel FAIL: {prefix}; {'; '.join(errors)}; fix: tools/publish-update.sh <apk>")
    else:
        print(f"Update channel OK: {prefix}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
