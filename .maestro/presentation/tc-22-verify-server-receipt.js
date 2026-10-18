// ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
// navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
// attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
//

// TC-22 post-share verification — confirms the verifier backend actually
// RECEIVED the shared credential, rather than trusting the app's own
// "Data shared" success screen at face value.
//
// Rationale: a UI success screen only proves the app believes the
// presentation completed; it doesn't prove the verifier's side actually
// parsed and stored a valid vp_token (the lesson from the TC-13/TC-14
// investigation earlier in this test suite's development - UI success
// there did not always mean the other side received anything). This
// queries the same GET /ui/presentations/{transaction_id} endpoint used
// to manually confirm receipt during TC-22's exploration, and fails the
// flow (via a thrown JS error, which fails the runScript command) if the
// expected vp_token entry for the "pid-cred" DCQL credential id isn't
// present.
//
// This intentionally does NOT decode the CBOR-encoded mdoc inside
// vp_token["pid-cred"][0] (that required a Python/CBOR toolchain during
// exploration, not available to Maestro's JS engine) - presence and
// non-emptiness of the entry is the achievable automated check here;
// the byte-level "does it really contain family_name=Testerova" decode
// was verified manually during development, not on every CI run.
const response = http.get("https://dev.verifier-backend.eudiw.dev/ui/presentations/" + output.transactionId);

if (!response.ok) {
  throw new Error("Verifier GET /ui/presentations/{id} failed: status=" + response.status + " body=" + response.body);
}

const parsed = json(response.body);
const vpToken = parsed.vp_token && parsed.vp_token["pid-cred"] && parsed.vp_token["pid-cred"][0];

if (!vpToken) {
  throw new Error("Verifier backend recorded no vp_token for pid-cred - server-side receipt missing. body=" + response.body);
}

console.log("TC-22 verify: server-side receipt confirmed, vp_token length=" + vpToken.length);
