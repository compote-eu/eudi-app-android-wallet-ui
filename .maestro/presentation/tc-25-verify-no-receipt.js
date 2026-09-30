// ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
// navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
// attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
//

// TC-25 post-error verification — same shape and same accept/fail rules
// as tc-24-verify-no-receipt.js, deliberately: the verifier has no
// visibility into WHY no credential arrived. "User cancelled a normal
// consent screen" (TC-24) and "the request couldn't be satisfied, so the
// user cancelled the no-matching-document consent screen" (this one) look
// the same from its side. The only place the distinction is visible is the
// app's own UI (the request_no_data text and the disabled Share button,
// asserted in tc-25-unsatisfiable-request.yaml before this script runs).
// This script's job is only TC-24's: confirm nothing was received.
//
// See tc-24-verify-no-receipt.js for the full reasoning. In short: this
// app's Cancel actively posts an encrypted "access_denied" error response
// (-> 200 with {"error":"access_denied"} and no vp_token), where the iOS
// reference app just abandons the request (-> 400). Both mean nothing was
// shared, and the event log can't tell a decline from a share (both post
// a wallet response), so only the presence of a vp_token decides.
//
// Accepted: 200 with error "access_denied" and no vp_token; or any non-200.
// Failed: a vp_token present; or 200 with neither vp_token nor
// error "access_denied".
//
// Never includes a response body in its output - on a failure it would be
// the shared vp_token, and this output lands in maestro.log, which CI
// uploads as a public artifact.
const base = "https://dev.verifier-backend.eudiw.dev/ui/presentations/" + output.transactionId;
const mainResponse = http.get(base);

let outcome;
if (mainResponse.ok) {
  let body;
  try {
    body = json(mainResponse.body);
  } catch (e) {
    throw new Error("Verifier GET /ui/presentations/{id} returned " + mainResponse.status + " with a non-JSON body - cannot confirm nothing was shared");
  }
  if (body.vp_token) {
    throw new Error("Verifier backend HAS a vp_token for this transaction - data WAS shared despite the unsatisfiable request (body keys=" + JSON.stringify(Object.keys(body)) + ")");
  }
  if (body.error !== "access_denied") {
    throw new Error("Verifier GET /ui/presentations/{id} returned " + mainResponse.status + " with no vp_token but error=" + JSON.stringify(body.error) + " (expected \"access_denied\") - body keys=" + JSON.stringify(Object.keys(body)));
  }
  outcome = "declined (200, error=access_denied, no vp_token)";
} else {
  outcome = "never submitted (status " + mainResponse.status + ")";
}

const eventsResponse = http.get(base + "/events");
let eventNames = "unavailable (status " + eventsResponse.status + ")";
if (eventsResponse.ok) {
  eventNames = JSON.stringify((json(eventsResponse.body).events || []).map(function (e) { return e.event; }));
}

console.log("TC-25 verify: no data received - " + outcome + ", event trail=" + eventNames);
