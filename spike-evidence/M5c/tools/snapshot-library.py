"""Snapshot of the library YAML subset the element registry is checked against (M5c, OR-2 (c)).

Usage: python3 -I snapshot-library.py <spec repo ux/ dir> > ux-elements/spec-snapshot/ux-library-subset.json
Read-only on the spec repo. Needs PyYAML. Records the spec repo's HEAD commit and whether ux/library had local changes.
"""
import json
import subprocess
import sys

import yaml

ux = sys.argv[1].rstrip("/")
ELEMENTS = ["text", "button", "status-hero", "notice", "link", "qr-viewfinder", "app-bar", "rp-identity",
            "attribute-list", "attribute-item"]
TEMPLATES = ["outcome", "progress", "scan", "sheet"]


def git(*a):
    return subprocess.check_output(["git", "-C", ux, *a], text=True).strip()


out = {
    "source_repo": "dvm-edoklady-eudiw-analysis",
    "source_commit": git("rev-parse", "HEAD"),
    "source_dir": "ux/library",
    "library_dirty": bool(git("status", "--short", "--", "library")),
    "elements": {},
    "templates": {},
}
for i in ELEMENTS:
    d = yaml.safe_load(open(f"{ux}/library/elements/EL-{i}.yaml"))
    props = {}
    for k, v in (d.get("props") or {}).items():
        p = {"type": v.get("type")}
        if v.get("type") == "enum":
            p["values"] = v["values"]
        props[k] = p
    out["elements"][d["id"]] = {
        "props": props,
        "events": sorted((d.get("events") or {}).keys()),
        "slots": sorted((d.get("slots") or {}).keys()) if isinstance(d.get("slots"), dict) else
        sorted(s["id"] if isinstance(s, dict) else s for s in (d.get("slots") or [])),
    }
for i in TEMPLATES:
    d = yaml.safe_load(open(f"{ux}/library/templates/TPL-{i}.yaml"))
    out["templates"][d["id"]] = {"regions": [r["id"] for r in d["regions"]]}
print(json.dumps(out, indent=1, sort_keys=True))
