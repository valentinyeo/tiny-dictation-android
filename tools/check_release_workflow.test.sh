#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
python3 - "$root" <<'PY'
import copy
from pathlib import Path
import subprocess
import sys
import tempfile

import yaml

root = Path(sys.argv[1])
checker = root / "tools/check_release_workflow.py"
workflow = yaml.safe_load((root / ".github/workflows/release.yml").read_text())
steps = workflow["jobs"]["release"]["steps"]
publish_index = next(index for index, step in enumerate(steps)
                     if "bash tools/publish-update.sh " in step.get("run", ""))
release_index = next(index for index, step in enumerate(steps)
                     if step.get("uses", "").startswith("softprops/action-gh-release@"))
cases = 0

with tempfile.TemporaryDirectory() as directory:
    path = Path(directory) / "release.yml"

    def check(name, value, expected):
        global cases
        path.write_text(yaml.safe_dump(value))
        result = subprocess.run([sys.executable, str(checker), str(path)],
                                capture_output=True, text=True, timeout=10)
        assert result.returncode == expected, (name, result.stdout, result.stderr)
        output = result.stdout + result.stderr
        assert ("Release workflow check ok" in output) == (expected == 0), name
        assert ("Release workflow check failed:" in output) == (expected != 0), name
        cases += 1

    check("real workflow", workflow, 0)
    broken = copy.deepcopy(workflow)
    del broken["jobs"]["release"]["steps"][publish_index]
    check("missing publisher", broken, 1)
    broken = copy.deepcopy(workflow)
    changed = broken["jobs"]["release"]["steps"]
    changed.insert(release_index, changed.pop(publish_index))
    check("publishing before release", broken, 1)
    for key, value in (("run", "bash tools/publish-update.sh wrong.apk"),
                       ("env", {}),
                       ("env", {"CLOUDFLARE_API_TOKEN": "${{ secrets.WRONG_TOKEN }}"}),
                       ("if", "false"), ("continue-on-error", True)):
        broken = copy.deepcopy(workflow)
        broken["jobs"]["release"]["steps"][publish_index][key] = value
        check("invalid publisher " + key, broken, 1)
    triggers = "on" if "on" in workflow else True
    for trigger in ("push", "workflow_dispatch"):
        broken = copy.deepcopy(workflow)
        del broken[triggers][trigger]
        check("missing " + trigger, broken, 1)

print(f"RELEASE_WORKFLOW_TESTS_OK: {cases} isolated cases")
PY
