import argparse
import hashlib
from pathlib import Path
import struct
import sys


PRIVATE_FINGERPRINT = "25FD78F3BE97F357E786689FB738C6670BF10F352EED4403DC93A72E03476C0F"
DEBUG_FINGERPRINT = "1EB74BD682990752C135CCC52A87DB839808E4DBDDEB1C85443544EDC3830C52"
V3_ID = 0xF05368C0
V2_ID = 0x7109871A


def length_prefixed(data, offset=0):
    if offset + 4 > len(data):
        raise ValueError("truncated signing-block length")
    size = struct.unpack_from("<I", data, offset)[0]
    start = offset + 4
    end = start + size
    if end > len(data):
        raise ValueError("truncated signing-block field")
    return data[start:end], end


def signer_fingerprint(path):
    data = path.read_bytes()
    eocd = data.rfind(b"PK\x05\x06", max(0, len(data) - 65557))
    while eocd >= 0:
        if eocd + 22 <= len(data):
            comment_size = struct.unpack_from("<H", data, eocd + 20)[0]
            if eocd + 22 + comment_size == len(data):
                break
        eocd = data.rfind(b"PK\x05\x06", max(0, len(data) - 65557), eocd)
    if eocd < 0:
        raise ValueError("missing ZIP end-of-central-directory record")
    disk, cd_disk, disk_entries, entries, cd_size, cd_offset = struct.unpack_from(
        "<HHHHII", data, eocd + 4
    )
    if disk or cd_disk or disk_entries != entries or entries == 0xFFFF:
        raise ValueError("unsupported multi-disk or ZIP64 APK")
    if cd_offset + cd_size != eocd or cd_offset < 24:
        raise ValueError("invalid ZIP central-directory bounds")
    if data[cd_offset - 16:cd_offset] != b"APK Sig Block 42":
        raise ValueError("missing APK Signing Block (v2/v3 required)")
    block_size = struct.unpack_from("<Q", data, cd_offset - 24)[0]
    start = cd_offset - block_size - 8
    if block_size < 24 or start < 0:
        raise ValueError("invalid APK Signing Block size")
    if struct.unpack_from("<Q", data, start)[0] != block_size:
        raise ValueError("APK Signing Block size headers disagree")
    offset = start + 8
    end = cd_offset - 24
    schemes = {}
    while offset < end:
        if offset + 8 > end:
            raise ValueError("truncated APK Signing Block entry")
        size = struct.unpack_from("<Q", data, offset)[0]
        offset += 8
        if size < 4 or offset + size > end:
            raise ValueError("invalid APK Signing Block entry size")
        scheme_id = struct.unpack_from("<I", data, offset)[0]
        if scheme_id in (V3_ID, V2_ID):
            if scheme_id in schemes:
                raise ValueError("duplicate APK signature scheme")
            schemes[scheme_id] = data[offset + 4:offset + size]
        offset += size
    scheme = schemes.get(V3_ID, schemes.get(V2_ID))
    if scheme is None:
        raise ValueError("APK has no v3 or v2 signer")
    signers, consumed = length_prefixed(scheme)
    if consumed != len(scheme):
        raise ValueError("unexpected trailing signature-scheme data")
    signer, _ = length_prefixed(signers)
    signed_data, _ = length_prefixed(signer)
    _, offset = length_prefixed(signed_data)
    certificates, _ = length_prefixed(signed_data, offset)
    certificate, _ = length_prefixed(certificates)
    if not certificate:
        raise ValueError("empty signer certificate")
    return hashlib.sha256(certificate).hexdigest().upper()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apks", nargs="+", type=Path)
    parser.add_argument("--expected", default=PRIVATE_FINGERPRINT)
    args = parser.parse_args()
    expected = args.expected.replace(":", "").upper()
    verified = []
    for path in args.apks:
        try:
            fingerprint = signer_fingerprint(path)
            if fingerprint != expected:
                identity = " (public debug key)" if fingerprint == DEBUG_FINGERPRINT else ""
                raise ValueError(
                    f"signer SHA-256 {fingerprint}{identity}; expected {expected}"
                )
        except (OSError, ValueError) as error:
            print(f"release APK signer failed {path}: {error}", file=sys.stderr)
            return 1
        verified.append((path, fingerprint))
    for path, fingerprint in verified:
        print(f"release APK signer ok {fingerprint} {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
