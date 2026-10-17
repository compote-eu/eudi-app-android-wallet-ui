#!/usr/bin/env python3
"""SUPERSEDED (M5e): the Home probe now reads the exporter's own home probe (ux-spec/, synced in M6c) through
ScreenProbe in :ux-spike, with per-brand gating and the visibility oracle. This script and spike-evidence/M5d/bundles are kept
as the M5d record only; nothing packages or reads them any more."""
"""M5d: PROVISIONAL single-screen bundle fragments for the dashboard Home screen (SCR-home), read-only on the ux/ spec.

Usage: make_home_fragment.py <ux dir> <out dir>

One bundle per Home ui_state (profile eudiw_v1: nodes of features that are off are dropped, the bottom bar replaces
the present button) plus `full` (default state, every node, no profile filtering). Each bundle is a one-state flow:
the state draws SCR-home, every event the screen emits is a stay-in-place branch (so nodes are enabled), no services.
Context = the screen's data_model examples (static fixture), overridden by the ui_state's `data`.
Also writes inventory.json (elements, templates, props, variants per node and state) for the coverage table.
"""
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

import yaml

UX = Path(sys.argv[1])
OUT = Path(sys.argv[2])
OUT.mkdir(parents=True, exist_ok=True)
FLOW_ID = "FLW-home-probe"
SCREEN = "SCR-home"
read_files = []


def fix_on(o):
    """YAML 1.1 reads the key `on` as boolean True; restore it."""
    if isinstance(o, dict):
        return {("on" if k is True else k): fix_on(v) for k, v in o.items()}
    if isinstance(o, list):
        return [fix_on(v) for v in o]
    return o


def load(rel):
    read_files.append(rel)
    return fix_on(yaml.safe_load((UX / rel).read_text(encoding="utf-8")))


scr = load(f"experience/screens/{SCREEN}.yaml")
tpl = load(f"library/templates/{scr['template']}.yaml")
profile = load("product/profiles/PRF-eudiw-v1.yaml")
rule_ids = scr["rules"]
rules = {r: load(f"product/rules/{r}.yaml") for r in rule_ids}
copy_files = ["common"] + [p.stem for p in sorted((UX / "product/copy/sk").glob("BRF-*.yaml"))]
copy_all = {}
for f in reversed(copy_files):  # common wins over BRF files, as the generator's lookup order
    copy_all.update(load(f"product/copy/sk/{f}.yaml")["entries"])
brand_copy = load("product/copy/sk/brands/eudiw_v1.yaml")["entries"]

OFF_NODES = {n.split("#")[1] for f in profile["features"].values() if not f["on"] for n in f["nodes"] if n.startswith(SCREEN + "#")}
# bottom-nav variant `bar` (eudiw_v1): the bottom bar replaces the present button (TPL-home actions contract)
BAR_REPLACES = {"present"}
used_copy = set()


def scan_copy(o):
    if isinstance(o, dict):
        for k, v in o.items():
            if k in ("copy", "label_copy") and isinstance(v, str) and v.startswith("CPY-"):
                used_copy.add(v)
            scan_copy(v)
    elif isinstance(o, list):
        for v in o:
            scan_copy(v)


def res_name(c):
    return "cpy_" + c[4:].replace("-", "_")


def heading_level(n):
    p = n.get("props") or {}
    if n["element"] == "EL-text" and p.get("variant") == "title":
        return 1
    if n["element"] == "EL-text" and p.get("variant") == "heading":
        return 2
    return None


def build(name, ui_state, filtered):
    nodes_src = [n for n in scr["nodes"] if not filtered or (n["id"] not in OFF_NODES and n["id"] not in BAR_REPLACES)]
    data = {p: s.get("example") for p, s in scr["data_model"].items()}
    if ui_state:
        data.update(next(u for u in scr["ui_states"] if u["id"] == ui_state).get("data") or {})
    context = {}
    for p, s in scr["data_model"].items():
        v = data[p]
        context[p] = {"type": s["type"], "nullable": False, "default": v, "declared_by": [SCREEN]}
    scan_copy(data)
    for n in nodes_src:
        scan_copy(n.get("props"))
    ev_by_node = {}
    for e in scr.get("events") or []:
        if e["node"] in {n["id"] for n in nodes_src}:
            ev_by_node.setdefault(e["node"], []).append({"on": e["on"], "emits": e["emits"]})
    emitted = sorted({e["emits"] for v in ev_by_node.values() for e in v})
    region_order = [r["id"] for r in sorted(tpl["regions"], key=lambda r: r["order"])]
    regions = {r: [] for r in region_order}
    for n in nodes_src:
        node = {"id": n["id"], "element": n["element"], "props": dict(n.get("props") or {})}
        if n["id"] in ev_by_node:
            node["events"] = ev_by_node[n["id"]]
        lvl = heading_level(n)
        if lvl:
            node["a11y"] = {"heading": lvl}
        regions[n["region"]].append(node)
    regions = {r: v for r, v in regions.items() if v}
    flat = [nd for v in regions.values() for nd in v]
    heading = next((nd["id"] for nd in flat if nd.get("a11y", {}).get("heading") == 1), None)
    kept = {n["id"] for n in nodes_src}
    for r in rule_ids:
        scan_copy(rules[r]["then"])
    copy = {}
    for c in sorted(used_copy):
        e = copy_all.get(c)
        if e is None:
            raise SystemExit(f"copy {c} missing")
        text = e["text"] if isinstance(e, dict) else e
        ph = re.findall(r"\{([a-z_]+)\}", text)
        copy[c] = {"res": res_name(c), **({"placeholders": ph} if ph else {}), "_sk": text}
    overrides = {c: {"res": res_name(c) + "__eudiw_v1", "_sk": (brand_copy[c]["text"])} for c in sorted(used_copy) if c in brand_copy}
    h = hashlib.sha256()
    for rel in sorted(set(read_files)):
        h.update(rel.encode() + b"\0" + hashlib.sha256((UX / rel).read_bytes()).hexdigest().encode() + b"\n")
    h.update(f"exporter:m5d-home-probe:{name}\n".encode())
    ux_commit = subprocess.run(["git", "-C", str(UX), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
    return {
        "bundle_schema_version": 1,
        "flow_id": FLOW_ID,
        "spec_hash": "provisional:sha256:" + h.hexdigest(),
        "provisional": True,
        "source": {"ux_commit": ux_commit, "exporter": "m5d-home-probe (hand-derived)", "screen": SCREEN,
                   "variant": name, "profile": "PRF-eudiw-v1 applied" if filtered else "none (every node)", "locale": "sk"},
        "initial": "home",
        "entries": {"default": {"state": "home"}},
        "process_death": {"android": "restore-per-state", "ios": "abandon"},
        "context": context,
        "events": {e: {"source": "ui"} for e in emitted},
        "services": {},
        "states": {"home": {"kind": "step", "screen": SCREEN, "ui_state": ui_state or "default",
                            "on": {e: [{"target": None, "kind": "alt"}] for e in emitted},
                            "back": {"policy": "pass"}, "restore": {"policy": "snapshot"}}},
        "screens": {SCREEN: {
            "name": scr["name"], "template": scr["template"], "regions": regions,
            "rules": [{"id": r, "when": rules[r]["when"], "then": rules[r]["then"]} for r in rule_ids],
            "ui_states": [u["id"] for u in scr["ui_states"]],
            "a11y": {"heading": heading, "on_enter": "focus-heading", "focus_order": [nd["id"] for nd in flat]},
        }},
        "copy": {"keys": copy, "brand_overrides": {"eudiw_v1": overrides}},
        "theme": {"default_brand": "eudiw_v1", "brands": ["eudiw_v1"], "default_mode": "light"},
        "waived": [],
        "_provisional": {"rule": "PROVISIONAL M5d probe fragment, not exporter output; the runtime ignores '_' keys.",
                         "dropped_by_profile": sorted((OFF_NODES | BAR_REPLACES) & {n["id"] for n in scr["nodes"]}) if filtered else [],
                         "source_files": sorted(set(read_files))},
    }


files = []
for u in [None] + [u["id"] for u in scr["ui_states"] if u["id"] != "default"]:
    name = u or "default"
    used_copy.clear()
    b = build(name, u, True)
    p = OUT / f"home-{name}.bundle.json"
    p.write_text(json.dumps(b, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    files.append(p.name)
used_copy.clear()
(OUT / "home-full.bundle.json").write_text(json.dumps(build("full", None, False), ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
files.append("home-full.bundle.json")

used_copy.clear()
hc = build("high-contrast", None, True)
hc["theme"]["default_mode"] = "high-contrast"
(OUT / "home-high-contrast.bundle.json").write_text(json.dumps(hc, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")

# ---- inventory of what the screen needs (all nodes, all states)
inv = {"screen": SCREEN, "template": scr["template"], "template_regions": [r["id"] for r in tpl["regions"]],
       "profile_off_nodes": sorted(OFF_NODES), "bar_replaces": sorted(BAR_REPLACES), "ui_states": [u["id"] for u in scr["ui_states"]],
       "elements": {}}
for n in scr["nodes"]:
    e = inv["elements"].setdefault(n["element"], {"nodes": [], "props": set(), "variants": set(), "events": set()})
    e["nodes"].append(n["id"])
    e["props"] |= set((n.get("props") or {}).keys())
    p = n.get("props") or {}
    for k in ("variant", "tone", "status", "doc_type", "leading", "trailing", "action"):
        if isinstance(p.get(k), str):
            e["variants"].add(f"{k}={p[k]}")
for ev in scr["events"]:
    inv["elements"][next(n["element"] for n in scr["nodes"] if n["id"] == ev["node"])]["events"].add(ev["on"])
for e in inv["elements"].values():
    for k in ("props", "variants", "events"):
        e[k] = sorted(e[k])
(OUT / "inventory.json").write_text(json.dumps(inv, indent=1) + "\n")
print(len(files), "bundles", files)
