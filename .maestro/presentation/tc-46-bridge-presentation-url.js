// ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
// navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
// attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
//

// TC-46 — bridges a CLI-supplied `-e` variable into `output.*` so
// `openLink` can actually use it.
//
// Confirmed live, not assumed: `openLink: "${PRESENTATION_URL}"` with
// `-e PRESENTATION_URL=...` does NOT interpolate - the literal,
// unsubstituted string is passed through and the link never opens
// (verified: no navigation happened at all, vs. a real page load once
// routed through `output.*` instead). `openLink: "${output.x}"` DOES
// work (verified: a real https URL opened Safari to the actual target
// page). Also confirmed live: a `-e KEY=value` variable is exposed
// inside runScript as a bare global identifier (`KEY`), NOT nested
// under an `env` object as might be assumed - `env.KEY` throws
// "Cannot read property of undefined" here.
output.presentationUrl = PRESENTATION_URL
