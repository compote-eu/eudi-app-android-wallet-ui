"""D8-logo corrected pass condition (SPIKE-PLAN §5) on `maestro hierarchy` JSONs (iOS accessibility tree).
usage: python3 -I assert-logo.py <baseline-request.json> <drill-request.json> <drill-a11y-audit.txt>
The audit file is spike-evidence/tools/ios-a11y-audit.sh output (XCUIApplication.performAccessibilityAudit + the XCUI
element tree with element types and traits). An image node hidden from accessibility: (a) the node `rp-trusted.logo`
exists (its testTag is kept for tests) but is not an Image element and has no label, the audit reports no issue on it
(an exposed unlabelled image would be flagged "no description"), and no other id/label mentions logo/image, (b) yet the name is laid out to the right by the 48 dp avatar box + the row spacing, i.e. the
image node occupies layout; (c) rp-trusted.name still names the party."""
import json, re, sys
NAME = "Príkladová banka, a.s."
def load(p):
    out = []
    def walk(n):
        a = n.get("attributes", {}); out.append((a.get("resource-id", ""), a.get("accessibilityText", ""), a.get("bounds", "")))
        for c in n.get("children", []): walk(c)
    walk(json.load(open(p))); return out
def x0(nodes, tag):
    for i, _, b in nodes:
        if i == tag: return int(re.match(r"\[(\d+),", b).group(1))
base, drill = load(sys.argv[1]), load(sys.argv[2])
rp = sorted({i for i, _, _ in drill if i.startswith("rp-trusted")})
print("rp-trusted a11y elements:", rp)
logoish = [(i, t) for i, t, _ in drill if i != "rp-trusted.logo" and re.search(r"logo|image", f"{i} {t}", re.I)]
audit = open(sys.argv[3], encoding="utf-8").read().splitlines()
logo_lines = [l.strip() for l in audit if "identifier: 'rp-trusted.logo'" in l]
logo_issues = [l for l in audit if l.startswith("ISSUE") and "logo" in l]
print("audit tree line(s) for the logo:", logo_lines)
print("audit issues:", [l for l in audit if l.startswith("ISSUE")])
name_label = {i: t for i, t, _ in drill}.get("rp-trusted.name")
bx, dx = x0(base, "rp-trusted.name"), x0(drill, "rp-trusted.name")
checks = [
    ("a the image node rp-trusted.logo exists, hidden from accessibility (type Other, not Image; no label; no audit issue; nothing else names a logo)",
     len(logo_lines) == 1 and logo_lines[0].startswith("Other,") and "label:" not in logo_lines[0] and not logo_issues and not logoish),
    (f"b the name moved right by the avatar box: x {bx} -> {dx} (delta {None if bx is None or dx is None else dx - bx} pt, expect >= 48)", bx is not None and dx is not None and dx - bx >= 48),
    (f"c rp-trusted.name names the party: {name_label!r}", name_label == NAME),
]
for n, ok in checks: print(("PASS " if ok else "FAIL ") + n)
sys.exit(0 if all(ok for _, ok in checks) else 1)
