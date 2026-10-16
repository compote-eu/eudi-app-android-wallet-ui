# Spike contract boundary: week-0 exporter ↔ spike runtime (M1b)

Status: **PROVISIONAL, for week 0 to accept or amend** (2026-10-16). Author: M1b agent (Claude Opus 5.5).
Spike branch `spike/ux-runtime` @ `b23f6a1f5`; spec read at ux `ec5e58e` (main, read-only).

This file fixes the minimum both sides must agree on so the spike can build ahead of `ux-spec/` (owner ruling R3).
It does not design the exporter. Where the current `ux/` spec already has a field, the bundle copies it unchanged.
Where it does not, the field is marked **W0** ("week 0 adds") and carries the default the spike builds against.

Companion fixture: [`spike-evidence/boundary/FLW-pres-cross-device.bundle.PROVISIONAL.json`](spike-evidence/boundary/FLW-pres-cross-device.bundle.PROVISIONAL.json)
(sha256 `31bfda65…cf8d71`, 25 states, 16 screens, 85 copy keys). A script derived it from the 50 source files listed in its
`_provisional.source_files`. It is **not** exporter output.

Inputs read: brief §3–§6 (`ux/evaluation/kmp-ui-from-spec/spike-brief.md`), background `spike-plan.md` §3–§6,
`experience/flows/FLW-pres-cross-device.yaml`, the 16 `SCR-pres-*` screens the flow references, the 9 rules they
use, the 9 elements and 4 templates of brief §3, `product/variants.yaml`, `product/theme/{eudiw_v1,idsk}/components.yaml`,
`product/copy/sk/{common,BRF-001,brands/eudiw_v1}.yaml`, `schemas/{flow,screen,rule,element,template,common}.schema.json`,
and this repo's `detekt.yml` and `.editorconfig`.

**Week-0 state found (read-only check, 2026-10-16):** none started. There is no `tools/export_kmp.py`, no
`product/services/`, and no `bundle_schema_version`/`UX_SPEC.lock` anywhere outside `evaluation/kmp-ui-from-spec/*.md`.
The flow schema still has only `exit: string`. There is one worktree (main) and no week-0 branch. The branches `ux-anatomy`,
`ux-merged`, `ux-port-merged`, `ux-propagation`, `ux-variants` and `ux-visual` are all from 2026-10-10/11 and unrelated.
Two facts moved since the brief was written:
- **REV-012 is taken.** It is the eudiw_v1 dark-mode review of 2026-10-16. Week 0 files its evidence record as REV-013.
- **eudiw_v1 now has dark modes** (DEC-066/067: `sys.dark*.tokens.json`). Dark mode stays out of scope (brief §3), so the hosted flow forces `light`.

---

## 1. Bundle schema, `bundle_schema_version: 1`

One JSON file per flow: `ux-spec/resources/files/FLW-pres-cross-device.bundle.json`. UTF-8, no comments.
Keys that start with `_` are annotations: the runtime ignores them, and no semantics may depend on them.

### 1.1 Versioning and strictness

- `bundle_schema_version` is an integer. The runtime supports exactly `{1}`, and any other value is refused (§4).
- Inside version 1, the exporter may **add** optional keys. The runtime ignores unknown keys in descriptive places
  (`description`, `satisfies`, `name`, and the screen `props` the element does not read).
- An unknown value in a **semantic enum** fails the load (`Refused(UnsupportedFeature)`). The semantic enums are: state
  `kind`, a back or restore `policy`, a guard `op`, an assign `op`, a derive `op`, an invoke `scope` and an exit `kind`.
  Making a semantic change silently is the failure mode KC1/KC2 care about.
- An unknown rule **effect** in a screen rule is logged and ignored, and counted in the M6 divergence list. Effects only
  change what is drawn.
- A breaking change (renaming, removing, or changing a meaning) bumps `bundle_schema_version` to 2.

### 1.2 Top level

| Key | Type | Spec today | Notes |
|---|---|---|---|
| `bundle_schema_version` | int | W0 | `1` |
| `flow_id` | string | `id` | must equal the contract's `PresCrossDeviceSpec.FLOW_ID` |
| `spec_hash` | string | W0 | `sha256:<64 hex>`; the provisional form is `provisional:sha256:<hex>` (§4) |
| `provisional` | bool | — | present and `true` only in PROVISIONAL fixtures; the exporter omits it |
| `source` | object | partly | `ux_commit`, `exporter`, `brief`, `pattern`, `maturity`, `locale` (`brief`/`pattern`/`maturity` exist today) |
| `initial` | state id | `initial: scan` | used when the flow starts with no entry name |
| `entries` | object | **W0** (brief §4: `entries: {deeplink: connecting}`) | §1.5 |
| `process_death` | object | **W0** | `{android: "restore-per-state", ios: "abandon"}`: brief §5, iOS cold start |
| `context` | object | the screens' `data_model` (union) | §1.3; `default`, `derive` and the flow-level paths are W0 |
| `events` | object | the event names exist (`on` keys, screen `events[].emits`) | `source` and `payload` are W0 |
| `services` | object | **W0** (`product/services/SVC-pres-*.yaml`) | §1.6 |
| `states` | object | `states` | §1.4 |
| `screens` | object | `experience/screens/SCR-*.yaml` | §1.7; every screen the flow references (Q6) |
| `copy` | object | copy ids exist; the resource names are W0 | §1.8 |
| `layouts` | object | `product/variants.yaml` axis `decision-steps` | W0 moves it to `TPL-sheet` `layouts:` (brief §4) |
| `theme` | object | `components.yaml` `brand`, `profile` | brand **references** only; token values live in theme Kotlin (§1.9) |
| `waived` | list | `waived[].error_class` | informational |

### 1.3 `context`: the flow's data, one entry per absolute path

The key is a `dataPath` (`^(/[a-z_][a-z0-9_]*)+$`, `common.schema.json`). No array indexing and no wildcards.

```json
"/request/attributes": {
  "type": "list", "nullable": false, "default": [],
  "item": {"id": {"type": "id"}, "label_copy": {"type": "copy", "required": true}, "value": {"type": "text"},
           "requirement": {"type": "enum", "values": ["mandatory", "optional"], "required": true},
           "selected": {"type": "boolean", "default": false}, "...": "copyable, media (from EL-attribute-list.props.items.item)"},
  "declared_by": ["SCR-pres-request"], "example": [ ... ]
},
"/request/selected_count": {"type": "number", "default": 0,
  "derive": {"op": "count_where", "list": "/request/attributes",
             "where": {"any": [{"field": "requirement", "op": "eq", "value": "mandatory"}, {"field": "selected", "op": "is_true"}]}}}
```

| Field | Spec today | Notes |
|---|---|---|
| `type` | `data_model.*.type` (`string number boolean url date list`) | |
| `nullable` | `data_model.*.nullable` | the exporter forces `true` when `default` is `null` |
| `item` | from the element prop the list is bound to (`EL-attribute-list.props.items.item`) | |
| `example` | `data_model.*.example` | fixtures and tests only; the runtime never reads it |
| `declared_by` | — | traceability: the screens whose `data_model` declares the path |
| `default` | **W0** | the start value; provisional: `null`/`0`/`false`/`[]`, and `/request/channel = "cross-device"` |
| `derive` | **W0** | recomputed after every assign; ops `count_where` and `ids_where` only |
| flow paths | **W0** | `/launch/uri` (the request URI), `/request/selection` (derived, the ids to `send`), `/flow/error_class` (set by the engine) |

The context is a plain `JsonObject` tree that the paths address. Services write it only through `assign` (§1.4). The
runtime builds the consent snapshot of `SCR-pres-request` from `/verifier/*` and `/request/*` (brief §6.6).

### 1.4 `states`

```json
"connecting": {
  "kind": "step", "screen": "SCR-pres-connecting", "ui_state": "default",
  "invoke": {"service": "fetchRequest", "args": {"uri": {"context": "/launch/uri"}},
             "on": {"RequestReady": {"event": "SYS_REQUEST_READY",
                                     "assign": [{"op": "merge", "path": "/verifier", "from": {"result": "verifier"}},
                                                {"op": "merge", "path": "/request",  "from": {"result": "request"}}]},
                    "Expired": {"event": "SYS_EXPIRED"}, "Error": {"event": "SYS_ERROR"}, "...": "..."}},
  "on": {"SYS_REQUEST_READY": [{"target": "request", "kind": "main", "satisfies": ["UXR:REQ-PRES-001"]}],
         "CANCEL": [{"target": "cancelled", "kind": "alt", "error_class": "user-refusal"}], "...": "..."},
  "back": {"policy": "event", "event": "CANCEL"},
  "restore": {"policy": "to", "target": "error"}
}
```

| Field | Spec today | Meaning in the runtime |
|---|---|---|
| `kind` | `type` (`final`/`recovery`, absent → `step`) | `step`, `recovery` or `final`. A `final` state has `exit` and nothing else. |
| `screen`, `ui_state` | `screen`, `ui_state` | the static ui_state |
| `ui_state_when` | **W0** | `[{when: <condition>, ui_state}]`; the first match wins, else `ui_state`. Provisional use: `verification-code` → `no-url` when `/verifier/portal_url` `is_empty`. |
| `on` | `on: {EVENT: {target, kind, guard?, error_class?, satisfies?}}` | **changed shape (W0)**: `EVENT → [branch, …]`, an **ordered** list (§1.4.1) |
| `invoke` | **W0** | §1.6. It runs in a job scoped to the state. |
| `back` | **W0** | §1.4.2 |
| `restore` | **W0** | §1.4.2 |
| `exit` (final) | `exit: "<prose>"` | **W0 structured**: `{kind: home\|flow\|platform, ref, result: presented\|not-presented, carries?: [paths], note: "<the prose>"}` |
| `description`, `satisfies` | as today | carried for traceability; the runtime ignores them |

#### 1.4.1 Guarded transitions, assign, and the guard language

A branch is `{target: <state>|null, kind, guard?: {rule?: RUL-…, when: <condition>}, assign?: [op…], error_class?, satisfies?}`.

When event E arrives in state S, with generation `gen`:
1. If `S.on[E]` is absent, the event is **not accepted**. It is dropped and logged.
2. For each branch in order: apply the branch's `assign` to a **copy** of the context, recompute `derive`, then
   evaluate `guard.when` on that copy. Take the first branch whose guard is absent or true, and commit its copy.
   (This is **assign-then-guard**, a deliberate deviation from XState's guard-then-action. It makes "untick the last item"
   decidable without arithmetic in guards. See Q3.)
3. If the branch has `target: null`, it is an **internal** transition: the state is kept, there is no exit or entry, and `gen` stays the same.
4. If it has a target, the engine exits S (which cancels its invoke job), sets `/flow/error_class` when the branch has
   `error_class`, enters the target, and sets `gen += 1`.
5. Only the **last** branch of an event may have no guard. The loader refuses a list that breaks this rule.
6. Idempotency: a UI event that **changes state** is accepted at most once per `gen`. Internal events may repeat.

**Guard and rule condition language.** This is exactly `rule.schema.json#/$defs/condition`, with nothing added:
- leaf `{path, op, value?}`, where `op` ∈ `is_empty`, `not_empty`, `is_true`, `is_false`, `eq`, `neq`, `in`;
- the combinators `{all: [≥2]}`, `{any: [≥2]}` and `{not: c}`.

The semantics:
- `is_empty` is true for `null`, a missing value, `""` and `[]`.
- `eq`/`neq` use JSON equality with no coercion. The number `0` is not the string `"0"`.
- `in` takes a JSON array `value`.
- A missing path evaluates as `null`.
- There is no arithmetic, no comparison operators, no event or result references and no function calls.

Inside a `derive.where`, a leaf uses `field` (a key of the list item) instead of `path`.

`guard.rule` names the rule that the guard was inlined from, so the bundle needs no rule files at run time. The rule's
`then: allow_transition` *is* the guard (brief §4 gives week 0 the job of saying so in the schema). `message` is not a guard.

**Assign ops (W0, the minimal set):**
- `set {path, value | from}`
- `merge {path, from}`, a shallow merge of an object
- `toggle_item {list, key, key_from, field}`, which flips a boolean field of the list item whose `key` equals the event payload

`from` is one of `{event: <payload field>}`, `{result: <result field>}`, `{launch: <launch arg>}` and
`{context: <path>}`. There are no `$` sigils, so the JSON also fits in a Kotlin raw string.

**The PROVISIONAL L58 fix** (week 0 owns the real one):
- `request-optional-selected` `TOGGLE_ITEM` has three branches: back to `request-no-selection` when the count is 0 and the
  user has already attempted to continue; back to `request-optional-only` when the count is 0; otherwise internal.
- Its `CONTINUE` gets the guard `not count==0`.
- `request-no-selection` `CONTINUE` is an accepted no-op (internal), because the spec says "pressing Pokračovať again changes nothing".
- `request` and `request-untrusted` get an internal `TOGGLE_ITEM`. Their screen emits it, but the flow has no transition for it today.

#### 1.4.2 `back` and `restore` policies (W0)

| Policy | `back` | `restore` (Android process death) |
|---|---|---|
| `event` `{event}` | fires the named event of the state, so the declared transition runs ("cancel" in SPIKE-PLAN) | — |
| `to` `{target}` | goes straight to the target state (no event) | enters the target state |
| `to_history` `{candidates[], fallback}` | goes to the most recently visited state in `candidates`, else `fallback` | same, using the history in the snapshot |
| `blocked` | consumed, does nothing; the platform back handler stays **enabled** | — |
| `pass` | the handler is disabled and Nav3 pops the flow entry (used only by states with no dismissive event) | — |
| `snapshot` | — | the state and context come back as they were. The loader **refuses** `snapshot` on a state whose invoke is `idempotent: false`. |

Runtime invariants:
- Restoring never re-invokes a non-idempotent service.
- An idempotent invoke re-runs on restore with a new `gen`.
- The snapshot is `{bundle_schema_version, spec_hash, state, context, history, gen}`.

### 1.5 `entries` (W0) and process death

```json
"entries": {
  "default":  {"state": "scan"},
  "deeplink": {"state": "connecting", "schemes": ["openid4vp"], "requires": ["unlocked"],
               "assign": [{"op": "set", "path": "/launch/uri", "from": {"launch": "uri"}}]}
},
"process_death": {"android": "restore-per-state", "ios": "abandon"}
```

`requires: ["unlocked"]` is a precondition that the host enforces (the Dashboard is the unlock gate in this repo). The
runtime only checks that the host passes `unlocked = true`. When it is false, the runtime refuses to start the entry.

### 1.6 `services` and `invoke` (W0, from `product/services/SVC-pres-*.yaml`)

```json
"services": {
  "fetchRequest":     {"ref": "SVC-pres-fetch-request", "args": {"uri": "url"}, "idempotent": false,
                       "results": {"RequestReady": {"verifier": {...}, "request": {...}}, "OptionalOnly": {...}, "RpUntrusted": {...},
                                   "DocMissing": {"document_name": "string"}, "DocInvalid": {...}, "Expired": {}, "Error": {"reason": "string?"}}},
  "authenticate":     {"idempotent": true,  "results": {"Authenticated": {}, "Failed": {}, "NotEnrolled": {}, "Expired": {}, "Error": {}}},
  "verificationCode": {"idempotent": true,  "results": {"Ready": {"code": "string", "portal_url": "url?"}, "Expired": {}, "Error": {}}},
  "send":             {"args": {"selection": "list:id"}, "idempotent": false, "results": {"Sent": {}, "Expired": {}, "Error": {}}},
  "cancel":           {"idempotent": true,  "results": {"Cancelled": {}, "NothingToCancel": {}, "Error": {}}}
}
```

| State | invoke | Result → event |
|---|---|---|
| `connecting` | `fetchRequest(uri = /launch/uri)` | RequestReady/OptionalOnly/RpUntrusted → `SYS_REQUEST_READY`/`_OPTIONAL_ONLY`/`SYS_RP_UNTRUSTED` (+ merge `/verifier`, `/request`); DocMissing/DocInvalid → `SYS_DOC_*`; Expired → `SYS_EXPIRED`; Error → `SYS_ERROR` |
| `auth` | `authenticate()` | Authenticated → `SYS_AUTH_OK`, Failed → `SYS_AUTH_FAILED`, NotEnrolled → `SYS_AUTH_NOT_ENROLLED`, … |
| `verification-code` | `verificationCode()`, `accepts_while_pending: [CANCEL, SYS_EXPIRED, SYS_ERROR]` | Ready → `event: null` (internal; sets `/verification/code`, `/verifier/portal_url`) |
| `sending` | `send(selection = /request/selection)` | Sent → `SYS_SENT`, Expired, Error |
| `cancelled` | `cancel()`, `scope: detached`, `on: {}` | results ignored (fire-and-forget) |

Invoke semantics:
- An invoke result maps **only** to an event that is already in `on`, plus assigns. The transitions stay the single source of truth.
- A result that `on` does not list is a contract error and fails the load.
- `scope` is `state` (the default: cancelled on exit) or `detached` (allowed to finish after exit, results ignored).

**Generic interpreter rule:** a node whose emitted event is not accepted in the current state is drawn **disabled**. An event
is not accepted when it has no `on` entry, or when it is blocked while an invoke is pending. This rule needs no screen code,
and the brief §4 gate "screen rules agree with flow transitions" keeps it consistent with the spec.

### 1.7 `screens`: node trees

```json
"SCR-pres-outcome-expired": {
  "name": "Verification expired", "template": "TPL-outcome", "in_brief_scope": true,
  "regions": {
    "hero":    [{"id": "hero", "element": "EL-status-hero",
                 "props": {"tone": "warning", "title": {"copy": "CPY-pres-outcome-error-expired-title"}, "text": {"copy": "…"}},
                 "a11y": {"heading": 1}}],
    "body":    [{"id": "help-note", "element": "EL-notice", "props": {...},
                 "slots": {"extra": [{"id": "help", "element": "EL-link", "props": {"href": {"bind": "/links/help"}, ...},
                                      "events": [{"on": "activate", "emits": "HELP"}]}]}}],
    "actions": [{"id": "retry", "element": "EL-button", "props": {...}, "events": [{"on": "press", "emits": "RETRY"}]}, {"id": "close", ...}]
  },
  "rules": [],
  "ui_states": ["default"],
  "a11y": {"heading": "hero", "on_enter": "focus-heading", "focus_order": ["hero", "hint", "help-note", "help", "retry", "close"]}
}
```

| Field | Spec today | Notes |
|---|---|---|
| `template` | `template` | the runtime chooses the template composable by the `TPL-*` id |
| `layout_axes` | **W0** (`TPL-sheet layouts:`) | `["decision-steps"]` on TPL-sheet screens. The brand chooses the option (§1.9). |
| `regions` | `nodes[].region` + the template's `regions[].order` | the exporter **nests and orders** the nodes: regions in template order, nodes in spec order |
| node `id`, `element`, `props` | as today | `element` is `EL-*`, and it is the only key into the element registry |
| node `props` values | `common.propValue` | a literal, `{copy, args?: {name: path}}` or `{bind: path}` |
| node `slots` | `parent` + `slot` | the children nested under their parent |
| node `events` | screen `events[]` regrouped per node | `[{on: <element event>, emits: <EVENT>}]`; the payload comes from the element (EL-attribute-list `toggle` → `{item_id}`) |
| `rules` | `rules: [RUL-…]` + `product/rules/*.yaml` | inlined `{id, when, then}`. The interpreter applies `hide`/`show`/`enable`/`disable`/`set_copy` and ignores `allow_transition`/`message` (Q12). |
| `ui_states` | `ui_states[].id` | ids only; the example `data` is not exported |
| node `a11y` | **W0** (derived) | `heading: 1\|2` (EL-text title/heading; EL-status-hero non-pending) · `live: "polite"` (EL-status-hero `tone=pending`) · `inert: true` (TPL-sheet `appbar` region) |
| screen `a11y` | **W0** (derived) | `heading` (the h1 node), `on_enter: focus-heading\|announce-live`, `live_region`, `focus_order` (reading order without the inert nodes), `dialog.labelled_by` on TPL-sheet |
| `in_brief_scope` | — | `true` for the 8 brief §3 screens. The other 8 come from the same 9 elements and 4 templates (Q6). |

The exporter derives the a11y fields from what the spec already says in prose:
- `EL-text.a11y.role`: h1 when `variant=title`.
- `EL-status-hero.a11y`: pending is a polite live region; an outcome moves focus to the h1 on entry.
- `TPL-sheet.regions.appbar.content_contract`: the appbar is inert, and the dialog is labelled by the header h1.

So these fields add no new design decision. They make the existing one machine-readable, which D7 needs.

### 1.8 `copy`

```json
"copy": {
  "keys": {"CPY-pres-request-data-intro": {"res": "cpy_pres_request_data_intro", "placeholders": ["document"]}, "...": {}},
  "brand_overrides": {"eudiw_v1": {"CPY-home-title": {"res": "cpy_home_title__eudiw_v1"}}}
}
```

- Resource name: `cpy_` followed by the id without `CPY-`, with `-` replaced by `_`. Brand overrides add `__<brand>`.
  `product/copy/sk/brands/eudiw_v1.yaml` overrides `CPY-home-title` today ("eDoklady" instead of "Moje eDoklady").
- Placeholders: `{name}` in the copy text becomes positional `%1$s…` in `strings.xml`, in the order of `placeholders`.
  The prop `args` map binds each name to a context path.
- The runtime resolves the key through `Res.allStringResources[res]`. It tries the brand override first, then the base.
  The bundle carries **no** text. (The PROVISIONAL fixture has `_sk` annotations for tests only.)
- List items carry copy ids in data (`label_copy`). The interpreter resolves them through the same table.

### 1.9 Brand and theme reference

```json
"layouts": {"TPL-sheet": {"decision-steps": {"attr": "decision-steps", "default": "sheet",
            "options": {"sheet": {"label": "…"}, "page": {"label": "…"}}, "scope": ["SCR-pres-auth", "SCR-pres-request", "SCR-pres-untrusted-confirm", "SCR-pres-verification-code"]}}},
"theme":   {"default_brand": "eudiw_v1", "brands": ["eudiw_v1", "idsk"], "default_mode": "light",
            "kotlin": "eu.europa.ec.ux.theme.generated", "profiles": {"eudiw_v1": "PRF-eudiw-v1", "idsk": "PRF-dvm"},
            "token_contract": "library/tokens/sys.contract.yaml schema_version 1"}
```

The bundle names brands and never carries token values. The theme Kotlin (§2.6) carries, per brand:
- the token values per mode;
- the `styles:` selections (eudiw_v1: `EL-attribute-list {pairs: columns}`, `EL-attribute-item {checkbox: outline}`);
- the `layouts` selection (`TPL-sheet {decision-steps: sheet|page}`).

Neither profile has a feature on a `SCR-pres-*` node today, so the bundle needs no per-profile node data. If that changes,
week 0 adds `profiles: {PRF-…: {hidden_nodes, hidden_events}}` in version 1 as an optional key.

---

## 2. Generated Kotlin: sample

The constraints come from this repo's lint gate. detekt is root-wide (`setSource(rootProject.projectDir)`) with no
baseline, so committed `ux-spec/**/*.kt` is linted. ktlint 1.8.0 follows `.editorconfig`. The rules generated code
must satisfy:

| Rule | Consequence for the exporter |
|---|---|
| detekt `PackageNaming` `[a-z]+(\.[a-z][A-Za-z0-9_]*)*` | `eu.europa.ec.ux.contract.pres` and `eu.europa.ec.ux.theme.generated`; never `…pres-cross-device` or `…presCrossDevice`-style segments starting with an uppercase letter |
| detekt `InvalidPackageDeclaration` (default on) | the directory equals the package: `ux-spec/contract/kotlin/eu/europa/ec/ux/contract/pres/` |
| detekt `WildcardImport` (strict, `excludeImports: []`), `UnusedImports` | explicit imports only, each one used |
| detekt `UnusedParameter`, `UnusedPrivateMember`/`Property`/`Class` | emit no helper that a given flow does not call; the interface methods are abstract, so their parameters are fine |
| detekt `ClassNaming`, `FunctionNaming`, `EnumNaming`, `VariableNaming`, `TopLevelPropertyNaming`, `ObjectPropertyNaming` | PascalCase types; camelCase functions and properties; `UPPER_SNAKE` for `const val` and enum entries; snake-case JSON names only through `@SerialName` |
| detekt `FunctionOnlyReturningConstant` (default on) | constants as `const val`, never `fun x() = "…"` |
| ktlint `annotation` | each annotation on its own line, as in `uilogic/config/ConfigNavigation.kt` |
| ktlint `kdoc` | the generated-file header is a `//` comment, never an orphan `/** */` |
| off in this repo: `MaxLineLength`, `MagicNumber`, `CyclomaticComplexMethod`, `NewLineAtEndOfFile`, `ForbiddenComment`, trailing commas, wrapping | no constraint, but the exporter should still wrap at about 120 characters and end files with a newline |

Header of every generated file:
`// Generated by ux/tools/export_kmp.py <version> from ux@<commit> (spec <hash12>). Do not edit: re-export.`

### 2.1 `PresCrossDeviceSpec.kt`

```kotlin
// Generated by ux/tools/export_kmp.py 0.1.0 from ux@ec5e58e (spec 849289280a98). Do not edit: re-export.
package eu.europa.ec.ux.contract.pres

object PresCrossDeviceSpec {
    const val FLOW_ID: String = "FLW-pres-cross-device"
    const val SPEC_HASH: String = "sha256:849289280a9851bd69bbff91970996129df868b7a4d29e30b6a5d5386917b7e5"
    const val BUNDLE_SCHEMA_VERSION: Int = 1
    const val BUNDLE_PATH: String = "files/FLW-pres-cross-device.bundle.json"
}
```

### 2.2 `PresCrossDeviceState.kt`: one entry per state, plus the consent screen's typed data

```kotlin
// Generated … Do not edit: re-export.
package eu.europa.ec.ux.contract.pres

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class PresCrossDeviceState(val id: String, val isFinal: Boolean = false) {
    SCAN("scan"),
    CONNECTING("connecting"),
    REQUEST("request"),
    REQUEST_OPTIONAL_ONLY("request-optional-only"),
    REQUEST_NO_SELECTION("request-no-selection"),
    REQUEST_OPTIONAL_SELECTED("request-optional-selected"),
    REQUEST_UNTRUSTED("request-untrusted"),
    UNTRUSTED_CONFIRM("untrusted-confirm"),
    AUTH("auth"),
    AUTH_NOT_ENROLLED("auth-not-enrolled"),
    AUTH_FAILED("auth-failed"),
    VERIFICATION_CODE("verification-code"),
    SENDING("sending"),
    SUCCESS("success"),
    CANCELLED("cancelled"),
    DOCUMENT_MISSING("document-missing"),
    DOCUMENT_INVALID("document-invalid"),
    ERROR("error"),
    ERROR_EXPIRED("error-expired"),
    ERROR_INVALID_QR("error-invalid-qr"),
    DONE("done", isFinal = true),
    CLOSED("closed", isFinal = true),
    ADD_DOCUMENT("add-document", isFinal = true),
    DEVICE_SETTINGS("device-settings", isFinal = true),
    TO_HELP("to-help", isFinal = true);

    companion object {
        fun of(id: String): PresCrossDeviceState? = entries.firstOrNull { it.id == id }
    }
}

/** The ui_states of SCR-pres-request that this flow reaches (the `sd-*` ones belong to FLW-pres-same-device). */
enum class PresRequestUiState(val id: String) {
    DEFAULT("default"),
    UNTRUSTED("untrusted"),
    OPTIONAL_ONLY("optional-only"),
    NO_SELECTION("no-selection"),
    OPTIONAL_SELECTED("optional-selected")
}

/** Consent snapshot of SCR-pres-request: `/verifier` and `/request` of the flow context. */
@Serializable
data class PresRequestData(
    val verifier: Verifier,
    val request: RequestData
)

@Serializable
data class Verifier(
    val name: String,
    @SerialName("service_name")
    val serviceName: String,
    val reason: String,
    val trust: Trust,
    @SerialName("privacy_url")
    val privacyUrl: String? = null
)

@Serializable
enum class Trust {
    @SerialName("trusted")
    TRUSTED,

    @SerialName("untrusted")
    UNTRUSTED
}

@Serializable
data class RequestData(
    @SerialName("document_name")
    val documentName: String,
    val attributes: List<RequestedAttribute>,
    @SerialName("selected_count")
    val selectedCount: Int = 0,
    val attempted: Boolean = false,
    val channel: String = "cross-device"
)

@Serializable
data class RequestedAttribute(
    val id: String,
    @SerialName("label_copy")
    val labelCopy: String,
    val value: String? = null,
    val requirement: Requirement,
    val selected: Boolean = false
)

@Serializable
enum class Requirement {
    @SerialName("mandatory")
    MANDATORY,

    @SerialName("optional")
    OPTIONAL
}
```

### 2.3 `PresCrossDeviceEvent.kt`

```kotlin
// Generated … Do not edit: re-export.
package eu.europa.ec.ux.contract.pres

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Every event of bundle.events. The class discriminator `type` is the bundle event name. */
@Serializable
sealed interface PresCrossDeviceEvent {
    @Serializable
    @SerialName("CONTINUE")
    data object Continue : PresCrossDeviceEvent

    @Serializable
    @SerialName("REJECT")
    data object Reject : PresCrossDeviceEvent

    @Serializable
    @SerialName("TOGGLE_ITEM")
    data class ToggleItem(
        @SerialName("item_id")
        val itemId: String
    ) : PresCrossDeviceEvent

    @Serializable
    @SerialName("SYS_QR_SCANNED")
    data class QrScanned(val uri: String) : PresCrossDeviceEvent

    @Serializable
    @SerialName("SYS_QR_INVALID")
    data object QrInvalid : PresCrossDeviceEvent

    // … one entry for each remaining event in bundle.events: ADD_DOCUMENT, APPROVE, CANCEL, CLOSE, CONFIRM, HELP,
    // OPEN_SETTINGS, RETRY (ui) and the SYS_* events of service results (emitted by the runtime, listed for exhaustiveness).
}
```

### 2.4 `PresCrossDeviceServices.kt`: the typed seam, with sealed results per call

```kotlin
// Generated … Do not edit: re-export.
package eu.europa.ec.ux.contract.pres

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** App logic behind FLW-pres-cross-device. The runtime calls nothing else (product/services/SVC-pres-*.yaml). */
interface PresCrossDeviceServices {
    suspend fun fetchRequest(uri: String): FetchRequestResult
    suspend fun authenticate(): AuthenticateResult
    suspend fun verificationCode(): VerificationCodeResult
    suspend fun send(selection: List<String>): SendResult
    suspend fun cancel(): CancelResult
}

@Serializable
data class RequestPayload(
    @SerialName("document_name")
    val documentName: String,
    val attributes: List<RequestedAttribute>
)

@Serializable
sealed interface FetchRequestResult {
    @Serializable
    @SerialName("RequestReady")
    data class RequestReady(val verifier: Verifier, val request: RequestPayload) : FetchRequestResult

    @Serializable
    @SerialName("OptionalOnly")
    data class OptionalOnly(val verifier: Verifier, val request: RequestPayload) : FetchRequestResult

    @Serializable
    @SerialName("RpUntrusted")
    data class RpUntrusted(val verifier: Verifier, val request: RequestPayload) : FetchRequestResult

    @Serializable
    @SerialName("DocMissing")
    data class DocMissing(
        @SerialName("document_name")
        val documentName: String
    ) : FetchRequestResult

    @Serializable
    @SerialName("DocInvalid")
    data class DocInvalid(
        @SerialName("document_name")
        val documentName: String
    ) : FetchRequestResult

    @Serializable
    @SerialName("Expired")
    data object Expired : FetchRequestResult

    @Serializable
    @SerialName("Error")
    data class Error(val reason: String? = null) : FetchRequestResult
}

@Serializable
sealed interface AuthenticateResult {
    @Serializable
    @SerialName("Authenticated")
    data object Authenticated : AuthenticateResult

    @Serializable
    @SerialName("Failed")
    data object Failed : AuthenticateResult

    @Serializable
    @SerialName("NotEnrolled")
    data object NotEnrolled : AuthenticateResult

    @Serializable
    @SerialName("Expired")
    data object Expired : AuthenticateResult

    @Serializable
    @SerialName("Error")
    data class Error(val reason: String? = null) : AuthenticateResult
}

@Serializable
sealed interface VerificationCodeResult {
    @Serializable
    @SerialName("Ready")
    data class Ready(
        val code: String,
        @SerialName("portal_url")
        val portalUrl: String? = null
    ) : VerificationCodeResult

    @Serializable
    @SerialName("Expired")
    data object Expired : VerificationCodeResult

    @Serializable
    @SerialName("Error")
    data class Error(val reason: String? = null) : VerificationCodeResult
}

@Serializable
sealed interface SendResult {
    @Serializable
    @SerialName("Sent")
    data object Sent : SendResult

    @Serializable
    @SerialName("Expired")
    data object Expired : SendResult

    @Serializable
    @SerialName("Error")
    data class Error(val reason: String? = null) : SendResult
}

@Serializable
sealed interface CancelResult {
    @Serializable
    @SerialName("Cancelled")
    data object Cancelled : CancelResult

    @Serializable
    @SerialName("NothingToCancel")
    data object NothingToCancel : CancelResult

    @Serializable
    @SerialName("Error")
    data class Error(val reason: String? = null) : CancelResult
}
```

### 2.5 `PresCrossDeviceExit.kt` and `PresCrossDeviceInvoker.kt`

```kotlin
// Generated … Do not edit: re-export.
package eu.europa.ec.ux.contract.pres

/** Structured exits of the final states (bundle states.<final>.exit). */
sealed interface PresCrossDeviceExit {
    /** home, presented: "Wallet home; the verifier website continues the user's session" */
    data object Done : PresCrossDeviceExit

    /** home, not-presented */
    data object Closed : PresCrossDeviceExit

    /** flow FLW-dashboard#add-document */
    data object AddDocument : PresCrossDeviceExit

    /** platform security-settings */
    data object DeviceSettings : PresCrossDeviceExit

    /** flow FLW-app-info#help-from-error, carries /flow/error_class */
    data class ToHelp(val errorClass: String?) : PresCrossDeviceExit

    companion object {
        fun of(finalState: String, errorClass: String?): PresCrossDeviceExit? = when (finalState) {
            "done" -> Done
            "closed" -> Closed
            "add-document" -> AddDocument
            "device-settings" -> DeviceSettings
            "to-help" -> ToHelp(errorClass)
            else -> null
        }
    }
}
```

```kotlin
// Generated … Do not edit: re-export.
package eu.europa.ec.ux.contract.pres

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bridges the generic runtime (JSON in, JSON out) to the typed [PresCrossDeviceServices].
 * The result's `type` is the bundle result name; :ux-runtime matches it against `invoke.on`.
 * :ux-contract does not depend on :ux-runtime: the host passes `invoker::invoke` as a `suspend (String, JsonObject) -> JsonObject`.
 */
class PresCrossDeviceInvoker(private val services: PresCrossDeviceServices) {

    suspend fun invoke(service: String, args: JsonObject): JsonObject = when (service) {
        FETCH_REQUEST -> encode(FetchRequestResult.serializer(), services.fetchRequest(args.string("uri")))
        AUTHENTICATE -> encode(AuthenticateResult.serializer(), services.authenticate())
        VERIFICATION_CODE -> encode(VerificationCodeResult.serializer(), services.verificationCode())
        SEND -> encode(SendResult.serializer(), services.send(args.strings("selection")))
        CANCEL -> encode(CancelResult.serializer(), services.cancel())
        else -> error("Unknown service '$service' in ${PresCrossDeviceSpec.FLOW_ID}")
    }

    private fun <T> encode(serializer: KSerializer<T>, value: T): JsonObject =
        json.encodeToJsonElement(serializer, value).jsonObject

    private companion object {
        const val FETCH_REQUEST = "fetchRequest"
        const val AUTHENTICATE = "authenticate"
        const val VERIFICATION_CODE = "verificationCode"
        const val SEND = "send"
        const val CANCEL = "cancel"
        val json = Json { classDiscriminator = "type" }
    }
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.strings(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }
```

### 2.6 Theme Kotlin (shape only)

`ux-spec/theme/kotlin/eu/europa/ec/ux/theme/generated/`:
- `UxBrandTokens.kt`: `object EudiwV1Light : UxTokenValues`, `object IdskLight …`, and so on. The value types are
  declared by the hand-written `:ux-theme` shell, so the generated code depends on the shell and not the other way round.
- `UxBrandConfig.kt`: per brand, the `styles` and `layouts` selections, and the profile id.
- `UxThemeSpec.kt`: `const val SPEC_HASH`, the same value as the contract.

---

## 3. `ux-spec/` layout and `UX_SPEC.lock`

```
ux-spec/                                             (top-level folder, not a Gradle module; written only by the exporter)
  UX_SPEC.lock                                       JSON (below)
  contract/kotlin/eu/europa/ec/ux/contract/pres/     PresCrossDeviceSpec.kt, PresCrossDeviceState.kt, PresCrossDeviceEvent.kt,
                                                     PresCrossDeviceServices.kt, PresCrossDeviceExit.kt, PresCrossDeviceInvoker.kt
  theme/kotlin/eu/europa/ec/ux/theme/generated/      UxBrandTokens.kt, UxBrandConfig.kt, UxThemeSpec.kt
  resources/values/strings.xml                       sk copy as the default locale (Q7), with brand override keys
  resources/files/FLW-pres-cross-device.bundle.json  the bundle
  fixtures/scripts/<name>.json                       fake-engine scripts (Q14): happy, expired-at-request, expired-at-sending,
                                                     slow, untrusted, doc-missing
```

| File | Consumed by | How |
|---|---|---|
| `contract/kotlin/**` | `:ux-contract` commonMain | `kotlin.srcDir(rootDir.resolve("ux-spec/contract/kotlin"))`; the kotlinx-serialization plugin |
| `theme/kotlin/**` | `:ux-theme` commonMain | `kotlin.srcDir(…/theme/kotlin)`, next to the hand-written shell |
| `resources/**` | `:ux-contract` | `compose.resources { publicResClass = true; packageOfResClass = "eu.europa.ec.ux.contract.pres"; customDirectory("commonMain", …/resources) }`. The bundle is read with `Res.readBytes(PresCrossDeviceSpec.BUNDLE_PATH)` and copy through `Res.allStringResources`. |
| `fixtures/scripts/*.json` | `:ux-spike` fake engine (debug only) and the tests | read as data. Then a new script step needs no Kotlin (D8). |
| `UX_SPEC.lock` | `:ux-contract:verifyUxSpec` (wired into `check`) | it recomputes the sha256 of every file and fails on a mismatch, a missing file or an unlisted file. It also cross-checks `lock.spec_hash` against the `SPEC_HASH` literal in the contract and theme sources and against `bundle.spec_hash`. |

```json
{
  "lock_version": 1,
  "ux_repo": "dvm-edoklady-eudiw-analysis",
  "ux_commit": "<40 hex>",
  "ux_dirty": false,
  "spec_hash": "sha256:<64 hex>",
  "bundle_schema_version": 1,
  "exporter": {"path": "tools/export_kmp.py", "version": "0.1.0"},
  "flows": ["FLW-pres-cross-device"],
  "brands": ["eudiw_v1", "idsk"],
  "default_brand": "eudiw_v1",
  "locale": "sk",
  "gates": {"make_gates": "green", "g4_profiles": {"PRF-eudiw-v1": "green", "PRF-dvm": "green"}},
  "sources": [{"path": "experience/flows/FLW-pres-cross-device.yaml", "sha256": "…"}],
  "files": [{"path": "contract/kotlin/eu/europa/ec/ux/contract/pres/PresCrossDeviceSpec.kt", "sha256": "…", "bytes": 512}]
}
```

`generated_at` is intentionally absent, so the same commit gives a byte-identical export. That is the D8 evidence.
`ux_dirty: true` makes `verifyUxSpec` fail **except** on a `spike/d8-*` branch. The D8 mutations come from uncommitted
throwaway worktrees.

---

## 4. Spec-hash handshake

**Definition (W0).** The exporter computes `spec_hash = "sha256:" + hex(sha256(Σ "<path>\0<sha256(file)>\n" over the sorted
ux/ source files it read, + "exporter:<version>\n"))`. It writes the same value into the bundle, the contract
(`PresCrossDeviceSpec.SPEC_HASH`), the theme (`UxThemeSpec.SPEC_HASH`) and the lock. The runtime never recomputes it,
because it has no sources. It only compares. The PROVISIONAL fixture uses the same recipe with the prefix
`provisional:` (`provisional:sha256:849289…`).

**Runtime API (hand-written, `eu.europa.ec.ux.runtime.bundle`):**
`FlowBundle.load(bytes, expectedFlowId, expectedSpecHash, themeSpecHash, allowProvisional = false): Loaded | Refused(reason)`.
A `Refused` result never builds an engine and never calls a service. The host shows a placeholder in debug builds and
logs `UX-SPIKE: refused <reason>`.

| # | Case | Expected |
|---|---|---|
| H1 | bundle, contract and theme hashes are equal; version 1 | `Loaded` |
| H2 | `bundle.spec_hash` ≠ `SPEC_HASH` | `Refused(SpecHashMismatch(expected, actual))`; the fake's call counters are all 0 |
| H3 | `spec_hash` missing or empty | `Refused(MissingSpecHash)` |
| H4 | `bundle_schema_version` is missing, `0`, `2` or a string | `Refused(UnsupportedSchemaVersion)` |
| H5 | `flow_id` ≠ `FLOW_ID` | `Refused(FlowIdMismatch)` |
| H6 | `UxThemeSpec.SPEC_HASH` ≠ `SPEC_HASH` (a partial sync) | `Refused(ThemeHashMismatch)` |
| H7 | the hash starts with `provisional:` and `allowProvisional = false` | `Refused(ProvisionalNotAllowed)`; the production host always passes `false` |
| H8 | malformed JSON or a wrong type in a semantic field | `Refused(Malformed(path))` |
| H9 | an unknown semantic enum value (e.g. `back.policy: "undo"`, guard `op: "gt"`) | `Refused(UnsupportedFeature(path))` |
| H10 | a structural invariant fails: an unguarded non-last branch, `snapshot` restore on a non-idempotent invoke, an invoke result event missing from `on`, an unknown target | `Refused(InvalidBundle(path))` |
| H11 | restore with a snapshot whose `spec_hash` ≠ the loaded bundle's | the snapshot is discarded and the flow exits `closed` (it is not resumed); 0 service calls |
| H12 | an unknown key in a descriptive place (`"x_note": 1` on a node) | `Loaded`; the key is ignored |
| L1 | (Gradle) a file is edited after export | `verifyUxSpec` fails with its path |
| L2 | (Gradle) the lock `spec_hash` ≠ the contract literal | `verifyUxSpec` fails |
| L3 | (Gradle) an unlisted file appears in `ux-spec/` | `verifyUxSpec` fails |

H1–H12 are commonTest in `:ux-runtime` and run against the PROVISIONAL fixture (M3). L1–L3 are a Gradle TestKit test or
a scripted check in M6.

---

## 5. Brief §5 owner decisions, as bundle data

| Decision | Bundle |
|---|---|
| `request`: Back = cancel (declined) | `states.request.back = {policy: event, event: REJECT}` → `cancelled` (`error_class: user-refusal`). The same holds for the 4 other request-family states (W0 confirms, Q8). |
| `request`: Restore = from the snapshot | `states.request.restore = {policy: snapshot}` (no invoke in `request`, so this is allowed) |
| `auth`: Back = return to `request` | `states.auth.back = {policy: to_history, candidates: [request, request-optional-only, request-no-selection, request-optional-selected, request-untrusted], fallback: request}`. Leaving `auth` cancels the state-scoped `authenticate` job. |
| `auth`: Restore = `request`; the user authenticates again | `states.auth.restore` = the same `to_history`. `authenticate` is never resumed; it runs again on the next `CONTINUE`. |
| `sending`: Back = blocked | `states.sending.back = {policy: blocked}`; the handler is enabled and consumes the press |
| `sending`: Restore = `error` (not idempotent) | `states.sending.restore = {policy: to, target: error}`, with `services.send.idempotent = false`. The loader enforces that `snapshot` is impossible here (H10). |
| iOS cold start: abandoned, Home opens, nothing restored | `process_death.ios = "abandon"`. The runtime ignores any snapshot on iOS, and the host does not recreate the flow entry (`IosNavHost` keeps no back stack). |
| A deep link starts a new flow at `connecting` | `entries.deeplink = {state: connecting, schemes: [openid4vp], requires: [unlocked], assign /launch/uri}` |
| Authentication after consent (DEC-001) | already the spec order: `request` `CONTINUE` → `auth` → `verification-code` |
| verification-code is the 8th screen | `SCR-pres-verification-code` is in `screens` with `in_brief_scope: true`; its data comes from `verificationCode()` (Q4) |
| PIN fallback: logged as a DEC; the fake is biometric only | `AuthenticateResult` has no PIN result; adding one is a version-1 additive change (a new result + event) |

The provisional defaults for states outside §5 (W0 rules on them, Q8):
- `scan`, `connecting`, `verification-code`: Back fires `CANCEL`.
- `untrusted-confirm`: Back fires `REJECT`.
- Outcomes: Back fires `CLOSE`, except `auth-failed`, which fires `CANCEL`.
- `connecting` restores to `error`, because `fetchRequest` is not idempotent.
- `verification-code` restores like `auth`.
- Every other state restores from the snapshot.

---

## 6. PROVISIONAL fixture rule

**What the spike builds before the export:**
1. **The engine, interpreter and handshake** (M3, M5b) are generic over the bundle. They are tested on
   `spike-evidence/boundary/FLW-pres-cross-device.bundle.PROVISIONAL.json`.
   - The test copy is a Kotlin raw string in `ux-runtime/src/commonTest/kotlin/eu/europa/ec/ux/runtime/fixture/ProvisionalPresBundle.kt`.
     Its header names the source file's sha256.
   - The bundle syntax has no `$`, so the raw string needs no escaping.
   - The `offline-loading` M5b state is added only in a second commonTest fixture.
2. **Fake scripts** are JSON in commonTest, in the shape §3 asks the exporter for.
3. **The one main-source exception.** M4's `FakePresCrossDeviceServices` and the M5b host need compile-time contract types
   before the export exists. A hand-copy of §2 lives in `ux-contract/provisional/kotlin/eu/europa/ec/ux/contract/pres/`.
   - Its package is identical to the generated one, so no consumer changes at sync.
   - Every file's header is `// PROVISIONAL (M1b): replaced by ux-spec/ at M6; do not extend`.
   - `:ux-contract` selects the srcDir by `file("ux-spec/UX_SPEC.lock").exists()`. M6 deletes the directory and the conditional.
   - It is never under `ux-spec/`, because `ux-spec/` is exporter-only (brief §6.1).
   - Its `SPEC_HASH` is the fixture's `provisional:sha256:…` value, so it hands-shakes only with the fixture, and only
     when `allowProvisional = true`.
4. Every PROVISIONAL test carries `PROVISIONAL` in its class name or `@Tag`-style name, so it can be listed with grep.
5. The provisional hash prefix makes a leftover provisional bundle refuse in any non-test host (H7).

**Re-check at sync (M6, before anything else uses the real export):**
1. Run `verifyUxSpec`, then the runtime handshake on the real bundle (H1).
2. **Shape diff.** Dump every JSON key path of the PROVISIONAL bundle and of the real one, with `jq 'paths|map(tostring)|join("/")'`.
   Classify each difference as same / renamed / absent / added. Record the table in `SPIKE-LOG.md` (the M6 divergence list).
3. **API diff.** List the public declarations of the provisional contract and of the generated contract: names, signatures,
   result names and `@SerialName`s. Diff them and record the result.
4. **Re-run** every PROVISIONAL test with the bundle source switched to `Res.readBytes(BUNDLE_PATH)` (the same assertions;
   a test that only made sense for the fixture is converted or deleted, and the reason is logged).
5. Classify each divergence:
   - (a) The loader adapts. This is spike-side and needs no ruling.
   - (b) A semantic difference: a policy, the guard language or an assign op. This goes to a ruling. Brief §5 differences go to the owner.
   - (c) Week 0 broke this boundary without a version bump. Report it to week 0 and do not hand-fix.
6. Delete `ux-contract/provisional/` and the srcDir conditional, and convert or delete the provisional fixtures. Then
   `git grep -n PROVISIONAL -- ':!spike-evidence' ':!*.md'` must return nothing.
7. If the real export fails detekt or ktlint, report it to week 0 with the findings. Never edit generated code,
   `detekt.yml` or `.editorconfig`.

---

## 7. Open questions for week 0 (each with the default the spike builds against)

1. **The request family.** Should the 5 `request*` flow states stay separate, or become one `request` state that uses
   `ui_state_when` on the context? *Default: keep the separate states (the smallest spec change), and fix L58 with the
   ordered guarded `TOGGLE_ITEM` branches of §1.4.1.*
2. **The context-mutation language.** Is `set`/`merge`/`toggle_item` + `derive` (`count_where`, `ids_where`) enough?
   *Default: yes. Anything else needs `bundle_schema_version` 2.*
3. **Assign-then-guard ordering** (unlike XState). *Default: assign-then-guard, documented in the flow schema. The
   bounded model check uses the same order.*
4. **Where the verification code comes from.** Options: a separate `verificationCode()` call on entering
   `verification-code`, or fields in the `fetchRequest` payload. *Default: the separate call, with `CONFIRM` blocked while it
   is pending. `portal_url` is set from its result even though `data_model` says "from the authorization request".*
5. **Attribute labels.** Services must return `label_copy` copy ids, which a real adapter cannot know (it has claim names).
   *Default: the fake returns copy ids. Week 0 adds a `claims → CPY-*` map in SVC, which the exporter emits into the bundle
   as `copy.claims`. The gap is logged as a port mismatch.*
6. **Which screens to export.** *Default: all 16 screens the flow references. They use only the 9 elements and 4 templates
   of the slice, and `sending` → restore → `error` needs `SCR-pres-outcome-error`. `in_brief_scope` marks the 8.*
7. **Locale and brand copy.** *Default: `sk` in `values/` (no `values-sk`, which `feature/kmp` does not have). Brand
   overrides are suffixed keys (`__eudiw_v1`), resolved through `Res.allStringResources`. `{name}` becomes `%n$s` in
   `placeholders` order.*
8. **Back and restore for the states outside brief §5.** *Default: the list at the end of §5.*
9. **The scope of `cancel()`.** *Default: invoked only on entering `cancelled`, detached, with its results ignored. It is not
   invoked on `closed`, `device-settings` or `to-help`, even after a request was fetched.*
10. **Exit kinds and the help payload.** *Default: `home`/`flow`/`platform`. `to-help` carries `/flow/error_class`, which
    the engine sets from the last `error_class` transition. The `add-document` ref is `FLW-dashboard#add-document`; confirm it.*
11. **`auth` Back after the untrusted path** (`request-untrusted` → `untrusted-confirm` → `auth`). *Default: `to_history`
    returns to `request-untrusted`, so the approval must be given again. `untrusted-confirm` is not a candidate.*
12. **Screen-rule effects `allow_transition` and `message`.** *Default: they are guards and flow concerns, so the interpreter
    ignores them on screens. `message` on `RUL-pres-request-no-selection-blocks-continue` is already covered by
    `RUL-pres-request-no-selection-error` hiding or showing `no-field-error`. Week 0 either defines `message` (for example,
    an announcement) or removes it.*
13. **The unlock precondition.** *Default: `entries.deeplink.requires: [unlocked]`, enforced by the host (the Dashboard
    gate), not by the flow.*
14. **Fake scripts as exporter output.** Should SVC files carry `fixtures:` that the exporter writes to
    `ux-spec/fixtures/scripts/`? *Default: yes. Otherwise D8 `offline-loading` needs a hand edit in the fake, and KC1's
    literal "only `ux-spec/**`" condition fails.*
15. **The spec-hash recipe.** *Default: the recipe in §4, over the sources the export read plus the exporter version, with
    those sources listed in the lock.*
16. **Event metadata.** *Default: every event has `source: ui|platform|service`. `SYS_QR_SCANNED {uri}` and `SYS_QR_INVALID`
    are platform events (the scanner). In the spike, the viewfinder tap emits them through the fake. `TOGGLE_ITEM` carries
    `{item_id}`.*
17. **Same-device leftovers.** `RETURN` events and `RUL-pres-sd-*` rules on `auth-not-enrolled`, `document-missing` and
    `document-invalid` belong to FLW-pres-same-device. *Default: export them unchanged. `/request/channel` defaults to
    `cross-device`, so the sd rules hide them. A node whose event has no transition is drawn disabled (§1.6), and the new
    G-gate should report it.*
