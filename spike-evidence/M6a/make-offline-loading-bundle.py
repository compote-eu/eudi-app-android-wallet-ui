#!/usr/bin/env python3
"""M5b test (ii) / D8-added-state-preliminary, re-derived at M6a from the real export: the TEST copy of the exported
bundle that adds one state, `offline-loading`, with its screen, event, service result and copy. Data only: the runtime
gets no Kotlin change. The export carries no copy text (strings.xml does), so the three new texts sit in the copy's
`_test_strings` annotation, which only the tests read.

Usage: python3 -I spike-evidence/M6a/make-offline-loading-bundle.py   (from the worktree root; rewrites the .TEST.json)
"""
import json
import sys

SRC = "ux-spec/resources/files/FLW-pres-cross-device.bundle.json"
DST = "spike-evidence/M6a/FLW-pres-cross-device.bundle.offline-loading.TEST.json"

b = json.load(open(SRC, encoding="utf-8"))
b["_test_copy"] = "M5b test (ii) at M6a: the exported bundle plus the state offline-loading; never shipped, never in ux-spec/"
b["events"]["SYS_OFFLINE"] = {"source": "service", "description": "No network while fetching the request (test copy)"}
b["services"]["fetchRequest"]["results"]["Offline"] = {}
b["states"]["connecting"]["invoke"]["on"]["Offline"] = {"event": "SYS_OFFLINE"}
b["states"]["connecting"]["on"]["SYS_OFFLINE"] = [{"target": "offline-loading", "kind": "error", "error_class": "offline"}]
b["states"]["offline-loading"] = {
    "kind": "recovery",
    "description": "No network: wait, then retry the request (D8-offline shape, M5b test copy)",
    "screen": "SCR-pres-offline-loading",
    "ui_state": "default",
    "on": {
        "RETRY": [{"target": "connecting", "kind": "main"}],
        "CANCEL": [{"target": "cancelled", "kind": "alt", "error_class": "user-refusal"}],
    },
    "back": {"policy": "event", "event": "CANCEL"},
    "restore": {"policy": "snapshot"},
}
b["screens"]["SCR-pres-offline-loading"] = {
    "name": "Offline, waiting to retry",
    "template": "TPL-progress",
    "in_brief_scope": False,
    "regions": {
        "hero": [{"id": "hero", "element": "EL-status-hero",
                  "props": {"tone": "pending", "title": {"copy": "CPY-pres-offline-title"}, "text": {"copy": "CPY-pres-offline-text"}},
                  "a11y": {"live": "polite"}}],
        "actions": [{"id": "retry", "element": "EL-button",
                     "props": {"variant": "secondary", "label": {"copy": "CPY-pres-offline-retry"}},
                     "events": [{"on": "press", "emits": "RETRY"}]}],
    },
    "rules": [],
    "ui_states": ["default"],
    "a11y": {"heading": None, "on_enter": "announce-live", "live_region": "hero", "focus_order": ["hero", "retry"]},
}
texts = {"cpy_pres_offline_title": "Ste offline", "cpy_pres_offline_text": "Čakáme na pripojenie k internetu.",
         "cpy_pres_offline_retry": "Skúsiť znova"}
for res in texts:
    b["copy"]["keys"]["CPY-" + res[4:].replace("_", "-")] = {"res": res}
b["copy"]["_test_strings"] = texts

text = json.dumps(b, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
if "$" in text or '"""' in text:
    sys.exit("cannot embed in a Kotlin raw string")
open(DST, "w", encoding="utf-8").write(text)
print(DST)
