#!/usr/bin/env python3
# ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
# navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
# attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
#

"""TC-03 - verify Android's full (no exclusions) PID request and the
resulting response.

Reconstructs the TransferManager "Request Doc" and "ResponseReceived"
log messages from an `adb logcat -v time` capture file. adb wraps long
log lines across many separate entries - a single grep match on either
message is a truncated fragment, not the full message (confirmed the
hard way during TC-02's manual verification: a naive grep looked
complete but silently cut off after a few hundred characters of a
16KB+ message).

Why this checks the REQUEST, not just the response, for "nothing was
excluded": confirmed live (2026-08-26) that this device's actual issued
PID credential simply does not contain a "portrait" data element (nor
several other optional fields like "sex" or "document_number") - a
direct field-set comparison showed TC-02's custom-PID-minus-portrait
response and this flow's full-PID response contain the exact same 9
fields (family_name, given_name, birth_date, expiry_date,
issuing_country, issuing_authority, nationality, issuance_date,
place_of_birth). So a response-side "is portrait present" check would
always fail here regardless of what was requested - it's capped by the
credential, not by the request. The real, verifiable distinction
between TC-02 and TC-03 is in the outgoing REQUEST's nameSpaces map:
confirmed live that TC-02's has 26 entries (no "portrait" key at all)
and TC-03's has 27 (includes "portrait") - see the two scripts' Android
yaml flows ("Custom PID" + deselect vs. "Full PID").

Asserts:
  - the outgoing request's nameSpaces map for eu.europa.ec.eudi.pid.1
    includes "portrait" as a requested field (proving nothing was
    excluded - see above for why this must be checked on the request,
    not the response)
  - transfer status == 0 (clean ISO 18013-5 completion)
  - at least one eu.europa.ec.eudi.pid.1 document present in the response
  - every returned document includes every field this credential is
    actually capable of providing (the same 9 fields noted above) -
    stronger than TC-02's minimal 3-field check, appropriate since this
    is the maximal-request case

Usage: tc-03-verify-attributes.py <logcat_capture_file>
"""
import re
import sys

TAG_LINE = re.compile(r"^\S+ \S+ D/TransferManager\(\d+\): ?")


def _reconstruct(log_path, marker):
    capturing = False
    parts = []
    with open(log_path) as f:
        for line in f:
            if "D/TransferManager" in line and marker in line:
                capturing = True
            if capturing:
                if "D/TransferManager" not in line:
                    break
                parts.append(TAG_LINE.sub("", line).rstrip("\n"))
    if not parts:
        return None
    full = "".join(parts)
    idx = full.find(marker + " ")
    if idx == -1:
        return None
    return full[idx + len(marker + " ") :]


def main():
    if len(sys.argv) != 2:
        print("usage: tc-03-verify-attributes.py <logcat_capture_file>", file=sys.stderr)
        sys.exit(2)

    log_path = sys.argv[1]

    request_content = _reconstruct(log_path, "Request Doc")
    if request_content is None:
        print("FAIL: no 'Request Doc' message found in logcat capture", file=sys.stderr)
        sys.exit(1)

    ns_match = re.search(
        r'"nameSpaces":\s*\{\s*"eu\.europa\.ec\.eudi\.pid\.1":\s*\{([^}]*)\}',
        request_content,
    )
    if ns_match is None:
        print("FAIL: could not find eu.europa.ec.eudi.pid.1 nameSpaces in the request", file=sys.stderr)
        sys.exit(1)
    requested_fields = set(re.findall(r'"([^"]+)":\s*(?:true|false)', ns_match.group(1)))
    if "portrait" not in requested_fields:
        print(
            f"FAIL: 'portrait' was not requested ({len(requested_fields)} fields requested) "
            "- this should be a full PID request with nothing excluded",
            file=sys.stderr,
        )
        sys.exit(1)

    response_content = _reconstruct(log_path, "ResponseReceived")
    if response_content is None:
        print("FAIL: no 'ResponseReceived' message found in logcat capture", file=sys.stderr)
        sys.exit(1)

    status_match = re.search(r'"status":\s*(\d+)', response_content)
    status = status_match.group(1) if status_match else None
    if status != "0":
        print(f"FAIL: transfer status was {status!r}, expected '0'", file=sys.stderr)
        sys.exit(1)

    doc_chunks = re.split(r'\{"docType":\s*"eu\.europa\.ec\.eudi\.pid\.1"', response_content)[1:]
    if not doc_chunks:
        print("FAIL: zero eu.europa.ec.eudi.pid.1 documents in response", file=sys.stderr)
        sys.exit(1)

    # Every field this credential has been confirmed live to actually
    # contain - see module docstring for why "portrait" isn't in this set.
    required_fields = {
        "family_name", "given_name", "birth_date", "expiry_date",
        "issuing_country", "issuing_authority", "nationality",
        "issuance_date", "place_of_birth",
    }
    failures = []
    for i, chunk in enumerate(doc_chunks):
        # Bound to issuerSigned.nameSpaces + issuerAuth - stop before
        # deviceSigned, which belongs to a different part of the structure.
        ns_part = chunk.split('"deviceSigned"')[0]
        fields = set(re.findall(r'"elementIdentifier":\s*"([^"]+)"', ns_part))
        missing = required_fields - fields
        if missing:
            failures.append(f"doc {i}: missing expected fields {sorted(missing)} (got {sorted(fields)})")

    if failures:
        print("FAIL:", file=sys.stderr)
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        sys.exit(1)

    print(
        f"PASS: request included 'portrait' ({len(requested_fields)} fields requested, "
        f"nothing excluded), status=0, {len(doc_chunks)} PID document(s) received, "
        f"all {len(required_fields)} available fields present in all"
    )
    sys.exit(0)


if __name__ == "__main__":
    main()
