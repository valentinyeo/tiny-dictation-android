#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
python3 - "$root" <<'PY'
import json
from pathlib import Path
import subprocess
import sys
import tempfile

root = Path(sys.argv[1])
checker = root / "tools/check_update_channel.py"
fixtures = root / "tools/fixtures"
release = fixtures / "release-v1.43.3.json"


def check(name, expected, manifest_args, release_file=release, versions=()):
    result = subprocess.run(
        [sys.executable, str(checker), *manifest_args, "--release-file", str(release_file)],
        capture_output=True, text=True, timeout=10,
    )
    output = result.stdout.strip()
    assert result.returncode == expected, (name, result.returncode, output, result.stderr)
    assert not result.stderr, (name, result.stderr)
    assert len(output.splitlines()) == 1, (name, output)
    assert "tools/publish-update.sh <apk>" in output, (name, output)
    assert ("Update channel OK:" if expected == 0 else "Update channel FAIL:") in output
    for version in versions:
        assert version in output, (name, version, output)
    print(f"{name}: exit {result.returncode}")
    print(output)


check("real stale manifest", 1,
      ["--manifest-file", str(fixtures / "manifest-v1.43.0.json")],
      versions=("manifest 1.43.0", "release 1.43.3"))
check("current manifest", 0,
      ["--manifest-file", str(fixtures / "manifest-v1.43.3.json")],
      versions=("manifest 1.43.3", "release 1.43.3"))
check("manifest URL override", 0,
      ["--manifest-url", (fixtures / "manifest-v1.43.3.json").as_uri()])

with tempfile.TemporaryDirectory() as directory:
    work = Path(directory)
    missing = work / "missing.json"
    corrupt = work / "corrupt.json"
    corrupt.write_text("{broken", encoding="utf-8")
    valid_manifest = ["--manifest-file", str(fixtures / "manifest-v1.43.3.json")]
    for name, path in (("missing", missing), ("corrupt", corrupt)):
        check(f"{name} manifest", 1, ["--manifest-file", str(path)],
              versions=("manifest unavailable", "release 1.43.3"))
        check(f"{name} release", 1, valid_manifest, release_file=path,
              versions=("manifest 1.43.3", "release unavailable"))
    check("unreadable manifest URL", 1, ["--manifest-url", missing.as_uri()])
    manifest_file = work / "manifest.json"
    release_file = work / "release.json"
    for manifest, latest, expected in (
        ("1.43.9", "1.43.10", 1),
        ("1.43.10", "1.43.9", 0),
        ("1.43.3", "1.43.3.0", 0),
        ("1.43.3.0", "1.43.3", 0),
    ):
        manifest_file.write_text(json.dumps({"versionName": manifest}), encoding="utf-8")
        release_file.write_text(json.dumps({"tag_name": "v" + latest}), encoding="utf-8")
        check("numeric version ordering", expected, ["--manifest-file", str(manifest_file)],
              release_file=release_file, versions=(f"manifest {manifest}", f"release {latest}"))
    for invalid in ("[]", "null", "{}", '{"versionName": 1433, "tag_name": 1433}',
                    '{"versionName": "1.43.x", "tag_name": "v1.43.x"}'):
        corrupt.write_text(invalid, encoding="utf-8")
        check("invalid manifest schema or version", 1, ["--manifest-file", str(corrupt)])
        check("invalid release schema or version", 1, valid_manifest, release_file=corrupt)

print("All update-channel tests passed")
PY
