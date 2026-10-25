import argparse
from fnmatch import fnmatchcase
from pathlib import Path
import sys

import yaml


parser = argparse.ArgumentParser()
parser.add_argument("workflow", nargs="?", default=".github/workflows/release.yml")
args = parser.parse_args()

try:
    workflow = yaml.safe_load(Path(args.workflow).read_text())
except (OSError, UnicodeError, yaml.YAMLError) as error:
    sys.exit(f"Release workflow check failed: cannot parse {args.workflow}: {error}")

triggers = workflow.get("on", workflow.get(True)) if isinstance(workflow, dict) else None
if not isinstance(triggers, dict):
    sys.exit("Release workflow check failed: missing push tags and workflow_dispatch triggers")

push = triggers.get("push")
tags = push.get("tags") if isinstance(push, dict) else None
if not isinstance(tags, list) or not any(
    isinstance(pattern, str) and fnmatchcase("v1.2.3", pattern) for pattern in tags
):
    sys.exit("Release workflow check failed: no push tag pattern matches v1.2.3")

dispatch = triggers.get("workflow_dispatch")
inputs = dispatch.get("inputs") if isinstance(dispatch, dict) else None
if not isinstance(inputs, dict) or "version" not in inputs:
    sys.exit("Release workflow check failed: workflow_dispatch requires a version input")

jobs = workflow.get("jobs")
release = jobs.get("release") if isinstance(jobs, dict) else None
steps = release.get("steps") if isinstance(release, dict) else None
if not isinstance(steps, list):
    sys.exit("Release workflow check failed: missing release steps")

release_index = next((index for index, step in enumerate(steps)
                      if isinstance(step, dict) and
                      str(step.get("uses", "")).startswith("softprops/action-gh-release@")), None)
publish_command = ('bash tools/publish-update.sh '
                   '"dist/TinyDictation-${{ steps.version.outputs.version }}-arm64-v8a.apk"')
publish_index = next((index for index, step in enumerate(steps)
                      if isinstance(step, dict) and step.get("run", "").strip() == publish_command), None)
if release_index is None or publish_index is None or publish_index <= release_index:
    sys.exit("Release workflow check failed: publish the built ARM64 APK after the GitHub release")

publish = steps[publish_index]
env = publish.get("env")
if not isinstance(env, dict) or env.get("CLOUDFLARE_API_TOKEN") != "${{ secrets.CLOUDFLARE_API_TOKEN }}":
    sys.exit("Release workflow check failed: publisher requires the CLOUDFLARE_API_TOKEN secret")
if "if" in publish or publish.get("continue-on-error", False):
    sys.exit("Release workflow check failed: update publishing must not be skipped or allowed to fail")

print("Release workflow check ok")
