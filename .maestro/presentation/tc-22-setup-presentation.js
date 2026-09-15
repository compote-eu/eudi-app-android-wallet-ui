// ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
// navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
// attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
//

// TC-22 setup step — fetches a FRESH presentation request from the live
// EUDI reference verifier backend (dev.verifier-backend.eudiw.dev) and
// exposes it to the rest of the flow via `output`.
//
// Why this can't be a hardcoded URL in the YAML: the verifier's
// /ui/presentations/v2 response embeds a single-use transaction_id and a
// request_uri pointing at a one-time signed request JWT — both are
// generated per call and are not meant to be reused/replayed across runs.
// A hardcoded URL from one exploration session would not work on a later
// CI run.
//
// intended_use_id is required: calling /ui/presentations (v1, or v2
// without this field) returns {"error": "MissingRegistrationCertificate"}
// on this deployment. GET /ui/intended-uses lists the pre-registered,
// certificate-backed use cases available on this dev backend; "TEST-01"
// ("For testing purposes") is the general-purpose one, unlike "PID-01"
// which is scoped to name-only queries.
//
// v2, not v1: /ui/presentations (v1) returns client_id/request_uri/
// request_uri_method but NOT a ready-to-use deep link — the caller would
// have to assemble and urlencode the openid4vp/haip-vp URL by hand. /ui/
// presentations/v2 (what eudi-web-verifier's own frontend calls) returns
// an additional `authorization_request_uri` field that's already the
// exact, correctly-encoded deep link string.
const body = {
  dcql_query: {
    credentials: [{
      id: "pid-cred",
      format: "mso_mdoc",
      meta: { doctype_value: "eu.europa.ec.eudi.pid.1" },
      claims: [{ path: ["eu.europa.ec.eudi.pid.1", "family_name"] }]
    }],
    credential_sets: [{ options: [["pid-cred"]], purpose: "We need to verify your identity" }]
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

// json(text) is Maestro's own JS-prelude helper (a thin wrapper over
// JSON.parse) - not a "json" global object with a .parse method.
const parsed = json(response.body);

// output.* set here is readable as ${output.*} in the rest of THIS SAME
// flow run only - it does not persist across separate `maestro test`
// invocations (confirmed: a standalone run of a later step referencing
// output.transactionId with no prior setup step in the same run sees it
// undefined). tc-22-remote-presentation.yaml must run this script and
// consume its output in one continuous flow.
output.presentationUrl = parsed.authorization_request_uri;
output.transactionId = parsed.transaction_id;
console.log("TC-22 setup: transaction_id=" + parsed.transaction_id);
