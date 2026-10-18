// ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
// navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
// attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
//

// TC-24 post-rejection verification — the inverse of tc-22-verify-
// server-receipt.js: confirms the verifier backend genuinely received
// NOTHING for this transaction, rather than trusting the app's own
// clean return to Documents as proof nothing leaked.
//
// What "rejected" looks like at the verifier differs between this app and
// the iOS reference app this was ported from - both are legitimate
// "nothing was shared" outcomes, just different protocol-level behaviour,
// not a bug in either app:
//
// - This app ACTIVELY DECLINES. Cancel/Back on the consent screen runs
//   RequestViewModel.handleOnBack() -> onUserDeclined(), which posts an
//   encrypted OpenID4VP error response ("access_denied") to the verifier's
//   direct_post endpoint. Verified 2026-09-30 on Android: the decline's
//   encrypted `response=` payload was 619 chars against 3921 for a real
//   share (which carries the mdoc vp_token), and afterwards
//   GET /ui/presentations/{id} returned 200 with {"error":"access_denied"}
//   and no vp_token.
// - The iOS reference app just ABANDONS the request: nothing is posted, and
//   the same GET returns 400 ("Presentation should be in Submitted state
//   but is in RequestObjectRetrieved").
//
// So the one thing that tells "shared" from "not shared" is whether the
// verifier holds a vp_token for this transaction. The event log can't:
// after this app's decline it reads "Transaction initialized", "Request
// object retrieved", "Wallet response posted", "Verifier got wallet
// response" - the same "Wallet response posted" a real share produces - so
// the previous "no such event" check would fail a correct decline. It is
// still fetched, and only its event names are logged, for diagnosis.
//
// Accepted as a safe reject:
//   - 200 with error "access_denied" and no vp_token (this app's decline)
//   - any non-200 (nothing was ever submitted: the abandon case)
// Failed:
//   - a vp_token present - something WAS shared
//   - 200 with neither a vp_token nor error "access_denied" (unexpected state)
//
// Never includes a response body in its output: on a failure it would be
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
    throw new Error("Verifier backend HAS a vp_token for this transaction - data WAS shared, rejection did not prevent it (body keys=" + JSON.stringify(Object.keys(body)) + ")");
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

console.log("TC-24 verify: no data received - " + outcome + ", event trail=" + eventNames);
