// ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
// navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
// attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
//

// TC-24 post-rejection verification — the inverse of tc-22-verify-
// server-receipt.js: confirms the verifier backend genuinely received
// NOTHING for this transaction, rather than trusting the app's own
// clean return to Documents as proof nothing leaked.
//
// Two checks, mirroring the exploration's side-by-side comparison of a
// rejected vs. a successful transaction's event log:
//
// 1. GET /ui/presentations/{id} (the same endpoint tc-22's verify script
//    asserts a 200 + populated vp_token from) must NOT return 200 here -
//    a rejected/abandoned transaction 400s instead (empty body; the
//    verifier's own error is "Presentation should be in Submitted state
//    but is in RequestObjectRetrieved").
//
// 2. GET /ui/presentations/{id}/events must contain no "Wallet response
//    posted" event anywhere in its log. This is the actual positive
//    proof of non-receipt: a successful share (confirmed during TC-22's
//    exploration) always produces exactly this event right before
//    "Verifier got wallet response"; its absence here is what
//    distinguishes "nothing was ever shared" from a successful run,
//    not just the 400 status code alone.
//
// Note (documented, not re-litigated here every run): this can't tell
// "user explicitly tapped Back" apart from "request was never touched
// at all" - both produce an identical event trail. That distinction
// doesn't matter for what this test checks (confirm nothing leaked,
// same TC-13/TC-14-derived goal as tc-22-verify-server-receipt.js) -
// both are the correct, safe outcome.
const mainResponse = http.get("https://dev.verifier-backend.eudiw.dev/ui/presentations/" + output.transactionId);

if (mainResponse.ok) {
  throw new Error("Verifier GET /ui/presentations/{id} returned " + mainResponse.status + " (expected non-200 for a rejected transaction) - body=" + mainResponse.body);
}

const eventsResponse = http.get("https://dev.verifier-backend.eudiw.dev/ui/presentations/" + output.transactionId + "/events");

if (!eventsResponse.ok) {
  throw new Error("Verifier GET /ui/presentations/{id}/events failed: status=" + eventsResponse.status + " body=" + eventsResponse.body);
}

const parsed = json(eventsResponse.body);
const events = parsed.events || [];
const walletResponsePosted = events.some(function (e) { return e.event === "Wallet response posted"; });

if (walletResponsePosted) {
  throw new Error("Verifier backend recorded a \"Wallet response posted\" event - data WAS shared, rejection did not prevent it. events=" + JSON.stringify(events.map(function (e) { return e.event; })));
}

console.log("TC-24 verify: no data received - main endpoint status=" + mainResponse.status + ", event trail=" + JSON.stringify(events.map(function (e) { return e.event; })));
