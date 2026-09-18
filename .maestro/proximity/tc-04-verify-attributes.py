#!/usr/bin/env python3
# ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
# navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
# attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
#

"""TC-04 - verify Android's full PID request against iOS's PARTIAL
response - the original manual-testing freeze repro (request/response
mismatch: Android asks for everything, iOS's consent screen shares only
what the user left checked).

Reconstructs the TransferManager "Request Doc" and "ResponseReceived"
log messages from an `adb logcat -v time` capture file, same technique
as TC-02/TC-03's verify scripts (a single grep match on either message
is a truncated fragment, not the full message).

Field-agnostic by design: this app's PID documents are a one-time-use
BATCH (see README.md - "the wallet holds multiple stored PID (MSO Mdoc)
instances... a request matches ALL of them"), so which specific fields
end up deselected can vary run to run depending on which stored
instance's card is shown and which of its rows tc-04-ios-partial-share.py
happens to detect and tap. This script does not assume specific field
names (unlike TC-02/TC-03's verify scripts, which check for a fixed
"portrait" field) - it instead compares the REQUEST's field set against
the RESPONSE's field set directly, whatever they turn out to be.

Asserts:
  - transfer status == 0 (clean ISO 18013-5 completion, i.e. the
    mismatch did NOT freeze/crash the transfer - this is the actual
    pass/fail signal for TC-04)
  - at least one eu.europa.ec.eudi.pid.1 document present in the RESPONSE
  - for every returned document, the response field set is a PROPER
    NON-EMPTY SUBSET of the requested field set: at least one field was
    dropped (proving a genuine partial share happened, not a silent
    full share) and at least one field remains (proving canShare()'s
    ">=1 selected" gate was respected, not an empty/failed share)

Usage: tc-04-verify-attributes.py <logcat_capture_file>
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
        print("usage: tc-04-verify-attributes.py <logcat_capture_file>", file=sys.stderr)
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
    if not requested_fields:
        print("FAIL: zero fields found in the outgoing request", file=sys.stderr)
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

    def repair_truncated_fields(fields, requested_fields):
        """adb's own line-wrapping can drop a byte or two right at a
        wrap boundary (confirmed live, 2026-08-27 - reproduced
        identically twice: "issuance_date" consistently arrived as
        "...D/TransferManager( <pid>): ssuance_date", the tag/timestamp
        of the NEXT wrapped line glued onto the value with its leading
        "i" lost). This is an adb/logcat capture artifact, not an app
        bug - repair it by matching a corrupted value against the
        requested field it's a suffix of, rather than flagging it as an
        unrequested/unknown field.
        """
        repaired = set()
        for f in fields:
            if f in requested_fields:
                repaired.add(f)
                continue
            candidates = [
                rf
                for rf in requested_fields
                if f.endswith(rf)
                or any(
                    len(rf) - drop >= 4 and f.endswith(rf[drop:])
                    for drop in range(1, 4)
                )
            ]
            if len(candidates) == 1:
                print(f"NOTE: repairing adb line-wrap-truncated field {f!r} -> {candidates[0]!r}", file=sys.stderr)
                repaired.add(candidates[0])
            else:
                repaired.add(f)
        return repaired

    failures = []
    for i, chunk in enumerate(doc_chunks):
        ns_part = chunk.split('"deviceSigned"')[0]
        fields = set(re.findall(r'"elementIdentifier":\s*"([^"]+)"', ns_part))
        fields = repair_truncated_fields(fields, requested_fields)

        not_requested = fields - requested_fields
        if not_requested:
            failures.append(f"doc {i}: response includes {sorted(not_requested)}, which were never requested")
        if fields == requested_fields:
            failures.append(
                f"doc {i}: response includes ALL {len(fields)} requested fields - "
                "this should be a PARTIAL share, nothing was deselected"
            )
        if not fields:
            failures.append(f"doc {i}: response includes ZERO fields - empty share, not a partial one")

    if failures:
        print("FAIL:", file=sys.stderr)
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        sys.exit(1)

    dropped_counts = []
    for chunk in doc_chunks:
        ns_part = chunk.split('"deviceSigned"')[0]
        fields = set(re.findall(r'"elementIdentifier":\s*"([^"]+)"', ns_part))
        fields = repair_truncated_fields(fields, requested_fields)
        dropped_counts.append(len(requested_fields - fields))

    print(
        f"PASS: request had {len(requested_fields)} fields (full PID), status=0, "
        f"{len(doc_chunks)} PID document(s) received, each a proper non-empty subset "
        f"of the request (fields dropped per doc: {dropped_counts}) - "
        "partial share handled without a freeze"
    )
    sys.exit(0)


if __name__ == "__main__":
    main()
