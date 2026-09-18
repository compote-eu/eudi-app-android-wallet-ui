// ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
// navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
// attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
//

// TC-25 post-error verification — same shape as tc-24-verify-no-
// receipt.js, and deliberately so: confirmed live during TC-25's
// exploration that the verifier's own event log is BYTE-FOR-BYTE
// identical between a rejected transaction (TC-24) and an unsatisfiable-
// request transaction (this one) - both produce exactly:
//   Transaction initialized -> Request object retrieved ->
//   Verifier failed to get wallet ("should be in Submitted state but is
//   in RequestObjectRetrieved")
// and both 400 on the main GET /ui/presentations/{id} endpoint.
//
// Why: the verifier only ever learns that a response was never
// submitted - it has no visibility into WHY. "User tapped Back on a
// normal consent screen" and "app determined it can't satisfy the
// credential_set and showed an error screen instead of a consent
// screen" are both just silence from the verifier's side. There is no
// server-side signal that distinguishes this scenario from TC-24's -
// the ONLY place that distinction is actually visible is the app's own
// UI (the exclamation-mark icon and "Required credential_set cannot be
// satisfied" text, asserted in tc-25-unsatisfiable-request.yaml before
// this script ever runs). This script's job is only what TC-24's job
// is: confirm nothing was received either way.
const mainResponse = http.get("https://dev.verifier-backend.eudiw.dev/ui/presentations/" + output.transactionId);

if (mainResponse.ok) {
  throw new Error("Verifier GET /ui/presentations/{id} returned " + mainResponse.status + " (expected non-200 for an unsatisfiable-request transaction) - body=" + mainResponse.body);
}

const eventsResponse = http.get("https://dev.verifier-backend.eudiw.dev/ui/presentations/" + output.transactionId + "/events");

if (!eventsResponse.ok) {
  throw new Error("Verifier GET /ui/presentations/{id}/events failed: status=" + eventsResponse.status + " body=" + eventsResponse.body);
}

const parsed = json(eventsResponse.body);
const events = parsed.events || [];
const walletResponsePosted = events.some(function (e) { return e.event === "Wallet response posted"; });

if (walletResponsePosted) {
  throw new Error("Verifier backend recorded a \"Wallet response posted\" event - data WAS shared despite the unsatisfiable request. events=" + JSON.stringify(events.map(function (e) { return e.event; })));
}

console.log("TC-25 verify: no data received - main endpoint status=" + mainResponse.status + ", event trail=" + JSON.stringify(events.map(function (e) { return e.event; })));
