#!/usr/bin/env python3
# ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
# navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
# attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
#

"""TC-02 - verify Android's received PID attributes against the custom
(minus Portrait) request.

Reconstructs the TransferManager "ResponseReceived" log message from an
`adb logcat -v time` capture file. adb wraps long log lines across many
separate entries - a single grep match on this message is a truncated
fragment, not the full message (confirmed the hard way during manual
verification: a naive grep looked complete but silently cut off after a
few hundred characters of a 16KB+ message).

Asserts:
  - transfer status == 0 (clean ISO 18013-5 completion)
  - at least one eu.europa.ec.eudi.pid.1 document present
  - none of the returned documents include "portrait" (the deselected field)
  - every returned document includes the core fields expected for a
    non-full PID share: family_name, given_name, birth_date

Usage: tc-02-verify-attributes.py <logcat_capture_file>
"""
import re
import sys

TAG_LINE = re.compile(r"^\S+ \S+ D/TransferManager\(\d+\): ?")


def reconstruct_response_received(log_path):
    capturing = False
    parts = []
    with open(log_path) as f:
        for line in f:
            if "D/TransferManager" in line and "ResponseReceived" in line:
                capturing = True
            if capturing:
                if "D/TransferManager" not in line:
                    break
                parts.append(TAG_LINE.sub("", line).rstrip("\n"))
    if not parts:
        return None
    full = "".join(parts)
    idx = full.find("ResponseReceived ")
    if idx == -1:
        return None
    return full[idx + len("ResponseReceived ") :]


def main():
    if len(sys.argv) != 2:
        print("usage: tc-02-verify-attributes.py <logcat_capture_file>", file=sys.stderr)
        sys.exit(2)

    content = reconstruct_response_received(sys.argv[1])
    if content is None:
        print("FAIL: no ResponseReceived message found in logcat capture", file=sys.stderr)
        sys.exit(1)

    status_match = re.search(r'"status":\s*(\d+)', content)
    status = status_match.group(1) if status_match else None
    if status != "0":
        print(f"FAIL: transfer status was {status!r}, expected '0'", file=sys.stderr)
        sys.exit(1)

    doc_chunks = re.split(r'\{"docType":\s*"eu\.europa\.ec\.eudi\.pid\.1"', content)[1:]
    if not doc_chunks:
        print("FAIL: zero eu.europa.ec.eudi.pid.1 documents in response", file=sys.stderr)
        sys.exit(1)

    required_fields = {"family_name", "given_name", "birth_date"}
    failures = []
    for i, chunk in enumerate(doc_chunks):
        # Bound to issuerSigned.nameSpaces + issuerAuth - stop before
        # deviceSigned, which belongs to a different part of the structure.
        ns_part = chunk.split('"deviceSigned"')[0]
        fields = set(re.findall(r'"elementIdentifier":\s*"([^"]+)"', ns_part))
        if "portrait" in fields:
            failures.append(f"doc {i}: 'portrait' present but was deselected in the request")
        missing = required_fields - fields
        if missing:
            failures.append(f"doc {i}: missing expected fields {sorted(missing)} (got {sorted(fields)})")

    if failures:
        print("FAIL:", file=sys.stderr)
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        sys.exit(1)

    print(
        f"PASS: status=0, {len(doc_chunks)} PID document(s) received, "
        f"'portrait' absent from all, core fields present in all"
    )
    sys.exit(0)


if __name__ == "__main__":
    main()
