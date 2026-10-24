import argparse
import re
from pathlib import Path
import sys


parser = argparse.ArgumentParser()
parser.add_argument("workflow", nargs="?", default=".github/workflows/release.yml")
parser.add_argument("build", nargs="?", default="app/build.gradle.kts")
args = parser.parse_args()

ENV_NAMES = (
    "RELEASE_KEYSTORE_FILE",
    "RELEASE_KEYSTORE_PASSWORD",
    "RELEASE_KEY_ALIAS",
    "RELEASE_KEY_PASSWORD",
)

try:
    workflow = Path(args.workflow).read_text()
    build = Path(args.build).read_text()
except (OSError, UnicodeError) as error:
    sys.exit(f"Release signing check failed: cannot read {args.workflow} or {args.build}: {error}")

if "secrets.KEYSTORE_BASE64" not in workflow:
    sys.exit("Release signing check failed: the release workflow does not take a keystore from a repository secret")

decodes = re.search(r"base64\s+(?:-d|--decode)", workflow)
exports = re.search(r"GITHUB_ENV", workflow)
if not decodes or not exports:
    sys.exit("Release signing check failed: the release workflow does not decode the secret keystore into a file for the build")

for name in ENV_NAMES:
    if name not in workflow:
        sys.exit(f"Release signing check failed: the release workflow never sets {name} for the build")

release_config = re.search(r"create\(\"release\"\)", build)
if not release_config:
    sys.exit(f"Release signing check failed: {args.build} has no release signing configuration")

for name in ENV_NAMES:
    if f'System.getenv("{name}")' not in build:
        sys.exit(f"Release signing check failed: the release signing configuration does not read {name}")

if not re.search(r"signingConfig\s*=\s*signingConfigs\.(?:getByName|findByName)\(\"release\"\)", build):
    sys.exit(f"Release signing check failed: {args.build} does not sign release builds with the release signing configuration")

print("Release signing check ok")
