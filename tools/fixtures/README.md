# Update-channel regression evidence

The manifests are byte-for-byte copies of `/tmp/r1-IGSH-484/live-update-manifest.json`
(before publication, versionCode 369 / versionName 1.43.0) and
`/tmp/r1-IGSH-484/live2.json` (after publication, 372 / 1.43.3).
`release-v1.43.3.json` is the saved GitHub `releases/latest` API response from
`/tmp/r1-IGSH-484/latest-release.json`, with published tag `v1.43.3`.

Measured historical red case from `bash tools/check_update_channel.test.sh`:

```text
real stale manifest: exit 1
Update channel FAIL: manifest 1.43.0, release 1.43.3; newest GitHub release is ahead of the published manifest; fix: tools/publish-update.sh <apk>
current manifest: exit 0
Update channel OK: manifest 1.43.3, release 1.43.3; manifest is up to date; fix: tools/publish-update.sh <apk>
```

The test script expects the stale check to exit 1, also rejects missing/corrupt
manifests and releases, and tests numeric dotted version ordering without any
network requests. Its overall result was `All update-channel tests passed`, exit 0.

Measured live green check (default R2 manifest and public GitHub API):

```text
$ python3 tools/check_update_channel.py
Update channel OK: manifest 1.43.3, release 1.43.3; manifest is up to date; fix: tools/publish-update.sh <apk>
$ echo $?
0
```
