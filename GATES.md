# Gates: IGSH-778 release update publishing

OWNS: .github/workflows/release.yml, tools/publish-update.sh, tools/publish-update.test.sh, tools/check_release_workflow.py, tools/check_release_workflow.test.sh, GATES.md

Scope: Publish the built ARM64 APK through the existing publisher after GitHub release creation, accept CI credentials without vault access, and make identical reruns safe without allowing stale or conflicting releases. Only isolated tools tests run locally; no Gradle, Android builds, live publishing, board writes, merge, or PR creation.

- [x] G0: the gate ledger has valid, falsifiable checks
  CHECK: node /home/valentin/.agents/skills/unlazy/scripts/gate-lint.mjs GATES.md
  EXPECT: LINT OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=69f81179f3934636347ff01de4824d2a1f500f2d29238b9fd688f82f77d1c57b; exit=0; EXPECT=matched; output-sha256=48630b7361dd44ee870917b12c3d19b9d7bdea738aaca16bb04d4cab83b772d2; output-bytes=8; shell=/bin/sh; cwd=/home/valentin/projects/tiny-dictation-android-r5-778; path=433695eed2f1/23 entries

- [x] G1: publisher tests prove environment credentials bypass the vault without leaks, vault fallback remains intact, identical reruns skip uploads, and lower or conflicting versions fail
  CHECK: bash tools/publish-update.test.sh
  EXPECT: PUBLISH_UPDATE_TESTS_OK:
  EVIDENCE: automatic-evidence=v1; definition-sha256=5966a1154716523c8c7adc1dcff7d524a0973761c9cb14ceff87684be3ff6677; exit=0; EXPECT=matched; output-sha256=cf70d9d94d71d0352667cf19d90d98c2f9f4bbca1322295e012cadafd1b549be; output-bytes=43; shell=/bin/sh; cwd=/home/valentin/projects/tiny-dictation-android-r5-778; path=433695eed2f1/23 entries

- [x] G2: release workflow retains its triggers and publishes its built ARM64 APK after the GitHub release with the expected secret
  CHECK: python3 tools/check_release_workflow.py
  EXPECT: Release workflow check ok
  EVIDENCE: automatic-evidence=v1; definition-sha256=c90a1d988966d3b64ce7b2d0a9f8281978528bb5e3b24da498dfeba26c99d777; exit=0; EXPECT=matched; output-sha256=a5ae5b739422640083461319562028b5378439b05373a7c4d01f9e023666f1fb; output-bytes=26; shell=/bin/sh; cwd=/home/valentin/projects/tiny-dictation-android-r5-778; path=433695eed2f1/23 entries

- [x] G3: current manifests are accepted and stale or invalid manifests are rejected
  CHECK: bash tools/check_update_channel.test.sh
  EXPECT: All update-channel tests passed
  EVIDENCE: automatic-evidence=v1; definition-sha256=8c258fcc4ae4d151db6095a39515079fd8523af6a6506b39f37cba823f5c2b8c; exit=0; EXPECT=matched; output-sha256=276e67a3dc4e61e9c847cf4d1ced3ed903f15225207fe67ac59576d8adf81ea7; output-bytes=3982; shell=/bin/sh; cwd=/home/valentin/projects/tiny-dictation-android-r5-778; path=433695eed2f1/23 entries

- [x] G4: no code comments are added and release signing checks still pass
  CHECK: node tools/strip-comments.mjs --check && python3 tools/check_release_signing.py
  EXPECT: Release signing check ok
  EVIDENCE: automatic-evidence=v1; definition-sha256=a64b8f7668805e8e792e72b09933debb6cd219dc11c99ee4991611d11513185b; exit=0; EXPECT=matched; output-sha256=8e7e9c5d4ad51dfdd3802a037a39a63e9248f0967910835a850b866c280d1366; output-bytes=49; shell=/bin/sh; cwd=/home/valentin/projects/tiny-dictation-android-r5-778; path=433695eed2f1/23 entries

- [x] G5: existing CI and channel checker are unchanged, application version is unchanged, and patch whitespace is valid
  CHECK: git diff --exit-code 1b3d67aeacfc1276658058a3f10be669d3094ca2 -- .github/workflows/ci.yml tools/check_update_channel.py app/build.gradle.kts && git diff --check 1b3d67aeacfc1276658058a3f10be669d3094ca2 && printf 'SCOPE_CHECK_OK\n'
  EXPECT: SCOPE_CHECK_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=1cca67685c5ac8de97dc39683f13704386ff998018b1ca4bc8076129bc913cbf; exit=0; EXPECT=matched; output-sha256=1a1620390e57511178189d050288ac177e4c15e2027c278c710295b458f72cef; output-bytes=15; shell=/bin/sh; cwd=/home/valentin/projects/tiny-dictation-android-r5-778; path=433695eed2f1/23 entries

- [ ] G6: the ticket branch is committed and pushed with no outstanding changes outside the gate evidence
  CHECK: test "$(git branch --show-current)" = ceo/IGSH-778 && test "$(git rev-parse HEAD)" = "$(git ls-remote --exit-code origin refs/heads/ceo/IGSH-778 | cut -f1)" && test -z "$(git status --porcelain --untracked-files=all -- . ':!GATES.md')" && printf 'BRANCH_PUSH_OK\n'
  EXPECT: BRANCH_PUSH_OK
  EVIDENCE: pending

- [x] G7: workflow checks reject a missing or misplaced publisher, wrong APK or secret, suppressed failures, and lost release triggers
  CHECK: bash tools/check_release_workflow.test.sh
  EXPECT: RELEASE_WORKFLOW_TESTS_OK:
  EVIDENCE: automatic-evidence=v1; definition-sha256=1933a2920e0022c49fe23622c40ec51e47a624de57f62f1acf004e12323324c5; exit=0; EXPECT=matched; output-sha256=fdeca77639ba64d0c9f418330c9feaadc25f262006e22ef7fc9b6537d9066950; output-bytes=45; shell=/bin/sh; cwd=/home/valentin/projects/tiny-dictation-android-r5-778; path=433695eed2f1/23 entries
