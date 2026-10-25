set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
python3 - "$root" <<'PY'
import base64
import hashlib
import io
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import zipfile

root = Path(sys.argv[1])
checker = root / "tools/check_release_apk_signer.py"
trusted_certificate = base64.b64decode("MIIBODCB36ADAgECAgEBMAoGCCqGSM49BAMCMCYxJDAiBgNVBAMMG0lzb2xhdGVkIEFQSyBzaWduZXIgZml4dHVyZTAeFw0yNDAxMDEwMDAwMDBaFw0zNDAxMDEwMDAwMDBaMCYxJDAiBgNVBAMMG0lzb2xhdGVkIEFQSyBzaWduZXIgZml4dHVyZTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABNYHLKgXYVLc6UCHyPYfvHL6g3osKtQBEaZsdBPAawz5b9qDuncf//5tfOIVZ7uPQANLYUQ9myQfQsD2mNvlrmMwCgYIKoZIzj0EAwIDSAAwRQIgR9NpQbIMTRKgyrBUZ14Lnb8JPo0/bU3DHxS8SLF7dIACIQCzbYGSQSoPKd2FMsaQIHLgTHasIQvYp3RWwVkxGyD8QA==")
debug_certificate = base64.b64decode("MIIDXzCCAkegAwIBAgIIdOrM6yeULdMwDQYJKoZIhvcNAQELBQAwXTELMAkGA1UEBhMCVVMxDjAMBgNVBAgTBURlYnVnMQ4wDAYDVQQHEwVEZWJ1ZzEOMAwGA1UEChMFRGVidWcxDjAMBgNVBAsTBURlYnVnMQ4wDAYDVQQDEwVEZWJ1ZzAgFw0yNjAxMDkwMDExMDNaGA8yMDUzMDUyNzAwMTEwM1owXTELMAkGA1UEBhMCVVMxDjAMBgNVBAgTBURlYnVnMQ4wDAYDVQQHEwVEZWJ1ZzEOMAwGA1UEChMFRGVidWcxDjAMBgNVBAsTBURlYnVnMQ4wDAYDVQQDEwVEZWJ1ZzCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBANdFK8x5vPi6pjZhULbxNnRrz+AAxIhGhv/T6wB8dCk9Hdv37hwIYvyN5B8FH0wD7n0matXswTYcLW0KqV1VfB8vtEo9X127oCRlakMSj03VJL52aMwhFM0DkB9cvKansC/JESwmWOA00jWJhQJKk4vaaqPlmTjj1uhd3mWIrSuRodmzZfxmZ/X8wKDZhSAyTGD+S7ZhTrMxA6rtyTTzRjFzBofz7ZwFCqt4f7QskavrRsV8ZG2QYaHKQW0w+1sAfUlfcNhFHuWYb9BysH8mgKZkH+ZO2butNZRagsL1yoR2zsLBOv4PhKQ/3xXt0K/KYLlmPIyC4G3XkhXCDonOlXUCAwEAAaMhMB8wHQYDVR0OBBYEFDhAFgiYTfxQJEHTtoHBzv+a2whTMA0GCSqGSIb3DQEBCwUAA4IBAQDCmvXVLllGznTudUA+6W0csqCq6c4pNpChkaESBiP+xYC2ePdwQVVfpIH8bcABsI/Z0Oz5Yqg8nR8t3g9cVBcPGe1Lmf4nRuc03pY7zNDZbXt2V0k9TuGTXW+CDiK9LwBz59Oq9VFt8ZPUQYGTaKNweO/laSNigChmFWvae0FCPX0thtA3jJYV4yP9ik9oyFw2EnTFhN51dhF9fnlFvNK9/wOYdRo3oKNrr1rMYV/xcbtvVp44DW3yqFVvNsO/3neQJVRp30NlD5hFLeLVwVcUlhsMa2lxVyxai+zvNT/ULr/MQyIntQnzZ0QTdTJjf5CrWc1LRTsxJgoEInzJOK7l")
trusted_fingerprint = hashlib.sha256(trusted_certificate).hexdigest().upper()
debug_fingerprint = hashlib.sha256(debug_certificate).hexdigest().upper()
assert debug_fingerprint == "1EB74BD682990752C135CCC52A87DB839808E4DBDDEB1C85443544EDC3830C52"
V3 = 0xF05368C0
V2 = 0x7109871A
cases = 0


def lp(data):
    return struct.pack("<I", len(data)) + data


def scheme(certificate, version):
    signed = lp(b"") + lp(lp(certificate))
    if version == V3:
        signed += struct.pack("<II", 28, 0x7FFFFFFF)
    signed += lp(b"")
    signer = lp(signed)
    if version == V3:
        signer += struct.pack("<II", 28, 0x7FFFFFFF)
    signer += lp(b"") + lp(b"")
    return lp(lp(signer))


def apk(pairs, comment=b""):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("AndroidManifest.xml", b"")
        archive.comment = comment
    data = buffer.getvalue()
    eocd = len(data) - 22 - len(comment)
    cd_offset = struct.unpack_from("<I", data, eocd + 16)[0]
    entries = b"".join(struct.pack("<Q", 4 + len(value)) + struct.pack("<I", kind) + value
                       for kind, value in pairs)
    size = struct.pack("<Q", len(entries) + 24)
    block = size + entries + size + b"APK Sig Block 42"
    footer = bytearray(data[eocd:])
    struct.pack_into("<I", footer, 16, cd_offset + len(block))
    return data[:cd_offset] + block + data[cd_offset:eocd] + footer


with tempfile.TemporaryDirectory(dir=root) as directory:
    directory = Path(directory)

    def check(name, data, success, expected=None, message=None):
        global cases
        path = directory / (name + ".apk")
        path.write_bytes(data)
        command = [sys.executable, str(checker), str(path)]
        if expected is not None:
            command += ["--expected", expected]
        result = subprocess.run(command, capture_output=True, text=True, timeout=10)
        assert (result.returncode == 0) == success, (name, result.stdout, result.stderr)
        assert ("release APK signer ok " in result.stdout) == success, name
        if success:
            assert not result.stderr and trusted_fingerprint in result.stdout, name
        else:
            assert "release APK signer failed " in result.stderr, (name, result.stderr)
        if message:
            assert message in result.stderr, (name, result.stderr)
        print(f"{name}: exit {result.returncode}")
        print((result.stdout + result.stderr).strip())
        cases += 1
        return path

    v2 = apk([(V2, scheme(trusted_certificate, V2))])
    v3 = apk([(V3, scheme(trusted_certificate, V3))])
    good = check("matching-v2", v2, True, trusted_fingerprint)
    check("matching-v3", v3, True, trusted_fingerprint)
    check("colon-lowercase-expected", v3, True,
          ":".join(trusted_fingerprint[i:i + 2] for i in range(0, 64, 2)).lower())
    check("v3-preferred-over-v2", apk([(V2, scheme(debug_certificate, V2)),
                                       (V3, scheme(trusted_certificate, V3))]),
          True, trusted_fingerprint)
    check("zip-comment-with-eocd-marker", apk([(V2, scheme(trusted_certificate, V2))],
                                              b"comment PK\x05\x06"), True, trusted_fingerprint)
    bad = check("debug-fingerprint-rejected", apk([(V2, scheme(debug_certificate, V2))]),
                False, message="public debug key")
    check("debug-v3-rejected", apk([(V3, scheme(debug_certificate, V3))]),
          False, message="public debug key")
    check("v3-mismatch-does-not-fall-back", apk([(V2, scheme(trusted_certificate, V2)),
                                               (V3, scheme(debug_certificate, V3))]),
          False, trusted_fingerprint, "public debug key")
    check("default-private-identity-required", v2, False, message="expected 25FD78F3")
    check("missing-v2-v3", apk([(0x12345678, b"")]), False, trusted_fingerprint,
          "no v3 or v2 signer")
    check("duplicate-v3", apk([(V3, scheme(trusted_certificate, V3))] * 2), False,
          trusted_fingerprint, "duplicate APK signature scheme")
    check("truncated-signer-field", apk([(V2, struct.pack("<I", 1000))]), False,
          trusted_fingerprint, "truncated signing-block field")
    check("empty-signers", apk([(V2, lp(b""))]), False, trusted_fingerprint)
    check("empty-certificate", apk([(V2, scheme(b"", V2))]), False,
          trusted_fingerprint, "empty signer certificate")
    check("truncated-zip", v2[:-1], False, trusted_fingerprint)
    check("not-an-apk", b"not an apk", False, trusted_fingerprint)
    damaged = bytearray(v2)
    start = damaged.index(b"APK Sig Block 42") + 16
    size = struct.unpack_from("<Q", damaged, start - 24)[0]
    damaged[start - size - 8] ^= 1
    check("inconsistent-block-sizes", bytes(damaged), False, trusted_fingerprint,
          "size headers disagree")
    result = subprocess.run([sys.executable, str(checker), "--expected", trusted_fingerprint,
                             str(good), str(bad)], capture_output=True, text=True, timeout=10)
    assert result.returncode != 0 and not result.stdout and "public debug key" in result.stderr
    cases += 1
    print("mixed-apks-rejected: no success output")
    result = subprocess.run([sys.executable, str(checker), "--expected", "invalid", str(good)],
                            capture_output=True, text=True, timeout=10)
    assert result.returncode != 0 and "expected INVALID" in result.stderr
    cases += 1
    print("invalid-expected-rejected")

print(f"RELEASE_APK_SIGNER_TESTS_OK: {cases} isolated cases")
PY
