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

print("Release workflow check ok")
