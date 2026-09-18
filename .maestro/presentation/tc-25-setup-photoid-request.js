// ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
// navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
// attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
//

// TC-25 setup step — fetches a FRESH presentation request from the live
// EUDI reference verifier backend, same mechanism as tc-22-setup-
// presentation.js, but deliberately requesting a document type this
// wallet holds zero of: Photo ID (org.iso.23220.photoid.1). A
// well-formed, backend-accepted request (confirmed live: POST returns
// 200 with a real transaction_id/authorization_request_uri, same as any
// other request) for something the wallet genuinely cannot fulfill -
// not a malformed/rejected-by-the-backend request.
//
// See tc-22-setup-presentation.js for why this can't be a hardcoded URL
// (single-use transaction_id/request_uri per call) and why /ui/
// presentations/v2 with intended_use_id is required over the simpler-
// looking v1 endpoint - all the same reasoning applies here unchanged.
const body = {
  dcql_query: {
    credentials: [{
      id: "photoid-cred",
      format: "mso_mdoc",
      meta: { doctype_value: "org.iso.23220.photoid.1" },
      claims: [{ path: ["org.iso.23220.photoid.1", "portrait"] }]
    }],
    credential_sets: [{ options: [["photoid-cred"]], purpose: "We need to verify your photo ID" }]
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
console.log("TC-25 setup: transaction_id=" + parsed.transaction_id);
