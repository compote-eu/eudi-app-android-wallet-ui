// ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
// navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
// attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
//

// TC-26 setup step — fetches a FRESH presentation request from the live
// EUDI reference verifier backend, same mechanism as tc-22-setup-
// presentation.js, but requesting TWO documents in one transaction: PID
// (family_name) and mDL (document_number), tied together via a single
// credential_sets entry listing both credential ids in one option. Per
// the verifier frontend's own DCQL model (DCQL.ts), a credential_set's
// "options" is a list of alternative ID-combinations that satisfy that
// set - listing both ids together in one option (rather than as two
// separate credential_sets entries) requires BOTH, not either/or.
// Confirmed live during TC-26's exploration: this is a real, intended
// feature (the verifier frontend ships predefined multi-attestation
// definitions for exactly this kind of combined request), not something
// forced into working.
//
// See tc-22-setup-presentation.js for why this can't be a hardcoded URL
// and why /ui/presentations/v2 with intended_use_id is required - all
// the same reasoning applies unchanged.
const body = {
  dcql_query: {
    credentials: [
      {
        id: "pid-cred",
        format: "mso_mdoc",
        meta: { doctype_value: "eu.europa.ec.eudi.pid.1" },
        claims: [{ path: ["eu.europa.ec.eudi.pid.1", "family_name"] }]
      },
      {
        id: "mdl-cred",
        format: "mso_mdoc",
        meta: { doctype_value: "org.iso.18013.5.1.mDL" },
        claims: [{ path: ["org.iso.18013.5.1", "document_number"] }]
      }
    ],
    credential_sets: [{ options: [["pid-cred", "mdl-cred"]], purpose: "We need to verify your identity and driving license" }]
  },
  jar_mode: "by_reference",
  nonce: "" + Date.now() + "-" + Math.random(),
  profile: "openid4vp",
  intended_use_id: "TEST-01"
};

const response = http.post("https://dev.verifier-backend.eudiw.dev/ui/presentations/v2", {
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body)
});

if (!response.ok) {
  throw new Error("Verifier POST /ui/presentations/v2 failed: status=" + response.status + " body=" + response.body);
}

const parsed = json(response.body);

output.presentationUrl = parsed.authorization_request_uri;
output.transactionId = parsed.transaction_id;
console.log("TC-26 setup: transaction_id=" + parsed.transaction_id);
