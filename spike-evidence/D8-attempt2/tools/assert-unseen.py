"""D8-unseen pass conditions 2-4 on a `maestro hierarchy` JSON (iOS accessibility tree; testTag = resource-id).
usage: python3 -I assert-unseen.py shown|hidden <hierarchy.json>   exit 0 = the condition holds."""
import json, sys
mode, path = sys.argv[1], sys.argv[2]
NAME, TEXT = "Príkladová banka, a.s.", "Overenie spustite znovu na stránke tohto overovateľa."
EXPECTED = ["hero", "hint", "verifier", "help-note", "help", "retry", "close"]
nodes = []
def walk(n):
    a = n.get("attributes", {})
    nodes.append((a.get("resource-id", ""), a.get("accessibilityText", "") or a.get("text", ""), a.get("bounds", "")))
    for c in n.get("children", []): walk(c)
walk(json.load(open(path)))
ids = [i for i, _, _ in nodes]
label = {i: t for i, t, _ in nodes if i}
print("screen:", "SCR-pres-outcome-expired" in ids and "ux-state-error-expired" in ids)
order = [i for i in ids if i in EXPECTED]
print("testTag order (iOS a11y tree):", order)
checks = []
if mode == "shown":
    checks.append(("2 verifier exists", "verifier" in ids))
    checks.append(("2 verifier.title == name", label.get("verifier.title") == NAME))
    checks.append(("2 verifier.text == text", label.get("verifier.text") == TEXT))
    ok3 = all(x in order for x in ("hint", "verifier", "help-note")) and \
        order.index("hint") < order.index("verifier") < order.index("help-note")
    checks.append(("3 hint < verifier < help-note", ok3))
    checks.append(("3 order == expected minus tags absent from the iOS tree",
                   order == [e for e in EXPECTED if e in order]))
else:
    checks.append(("4 no node tagged verifier", not any(i.startswith("verifier") for i in ids)))
    checks.append(("4 text nowhere on screen", not any(TEXT in (t or "") for _, t, _ in nodes)))
for name, ok in checks: print(("PASS " if ok else "FAIL ") + name)
print("verifier.title label:", label.get("verifier.title")); print("verifier.text label:", label.get("verifier.text"))
sys.exit(0 if all(ok for _, ok in checks) else 1)
