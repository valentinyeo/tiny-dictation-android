# Gates: IGSH-484 update-channel monitoring

OWNS: GATES.md, tools/check_update_channel.py, tools/check_update_channel.test.sh, tools/fixtures/**, .github/workflows/ci.yml

Scope: Detect GitHub releases missing from the update channel, prove the historical failure offline and current health live, and submit a branch PR without builds or merging.

- [x] G0: the ledger has valid, falsifiable checks
  CHECK: node /home/valentin/.agents/skills/unlazy/scripts/gate-lint.mjs GATES.md
  EXPECT: LINT OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=69f81179f3934636347ff01de4824d2a1f500f2d29238b9fd688f82f77d1c57b; exit=0; EXPECT=matched; output-sha256=9716e2b7534864b33613bc0adc3ad1f50f5511c8cbcae25a65bf8d6bf34d44d6; output-bytes=386; shell=/bin/sh; cwd=/home/valentin/projects/td-484-ch; path=433695eed2f1/23 entries

- [x] G1: offline tests reject the real stale manifest and unreadable inputs and accept the current manifest with numeric version ordering
  CHECK: bash tools/check_update_channel.test.sh
  EXPECT: All update-channel tests passed
  EVIDENCE: automatic-evidence=v1; definition-sha256=8c258fcc4ae4d151db6095a39515079fd8523af6a6506b39f37cba823f5c2b8c; exit=0; EXPECT=matched; output-sha256=0c0fa9455a58f1dac5ff82c4c9638da50c98cd924072901ef14cdbaa182f6faf; output-bytes=4282; shell=/bin/sh; cwd=/home/valentin/projects/td-484-ch; path=433695eed2f1/23 entries

- [x] G2: the live manifest is at least as new as the newest published GitHub release
  CHECK: python3 tools/check_update_channel.py
  EXPECT: Update channel OK:
  EVIDENCE: automatic-evidence=v1; definition-sha256=43cf90a16272cb3bbfb5764486044ffed918b4127962e2a3ddf9b3223b91ce8e; exit=0; EXPECT=matched; output-sha256=a4286820314114215a4b3953d804cef05caa51d1b61f8926f4b4724e0372c946; output-bytes=111; shell=/bin/sh; cwd=/home/valentin/projects/td-484-ch; path=433695eed2f1/23 entries

- [x] G3: CI runs a read-only, pinned-action update-channel job after existing jobs on master pushes only, with no builds added
  EVIDENCE: Parsed CI YAML against HEAD: existing triggers, permissions and android-test are unchanged; needs lists every existing job; always plus exact push/master condition excludes PRs; checkout@v4 matches existing CI; new steps run only Python/shell checks. No build commands were executed on this VPS.

- [x] G4: fixtures preserve the supplied real manifests and README records measured historical red and live green transcripts
  EVIDENCE: Byte equality verified for all three /tmp/r1-IGSH-484 captures. Re-executed historical checker exit 1 and default live checker exit 0; exact output lines match tools/fixtures/README.md. Offline test transcript also captured stale exit 1 and current exit 0.

- [x] G5: the focused change contains no code comments or whitespace errors and uses no Gradle, builds, or emulator
  CHECK: git diff --check && git diff --cached --check && node tools/strip-comments.mjs --check
  EXPECT: No code comments found.
  EVIDENCE: automatic-evidence=v1; definition-sha256=593e44c095d3724b4ba27102a25662536f8daab9dac56f0bfde17cf7953da08a; exit=0; EXPECT=matched; output-sha256=33ad9c77b5aaa9b59009bc21cbb061083aebe1abb6a656c7c02af72264a20ded; output-bytes=24; shell=/bin/sh; cwd=/home/valentin/projects/td-484-ch; path=433695eed2f1/23 entries

- [ ] G6: the requested branch is committed and pushed and an open PR targets master with the requested title, without merging
  EVIDENCE: pending
