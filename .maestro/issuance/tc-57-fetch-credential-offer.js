// ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
// navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
// attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
//

// TC-57 setup step — fetches a FRESH credential-offer URI from the live
// DEV issuer (ec.dev.issuer.eudiw.dev) by replicating its own web
// frontend's request sequence directly via Maestro's `http` binding,
// same "call the API directly instead of driving a browser" approach
// already established by tc-22-setup-presentation.js.
//
// Why three requests, not one: this mirrors exactly what a real browser
// does when a user walks the issuer's own "Credential Offer URL" page
// (see .maestro/issuance/README.md's TC-57 section for the full
// request/response shapes, captured live via curl during this test's
// development):
//   1. GET /credential_offer - redirects (auto-followed by `http.get`)
//      to /credential_offer_choice?frontend_id=..., which sets a
//      session cookie and returns a hidden `payload` field listing
//      every credential type/format this issuer can offer.
//   2. POST that same `payload` value, unmodified, to
//      /display_credential_offer - returns the actual "Please select
//      credentials" form (checkboxes per credential type + a grant-type
//      radio + a `credential_offer_URI` scheme field).
//   3. POST the chosen selection (here: PID MSO Mdoc + Authorization
//      Code Grant + the default `haip-vci://` scheme, matching what a
//      real user would submit unmodified) to /credential_offer -
//      returns another auto-redirecting page whose embedded JSON
//      payload contains `url_data`: the actual, ready-to-open
//      credential-offer URI (also a `qrcode` base64 PNG of the same
//      value, and a `wallet_dev` same-device link - `url_data` is the
//      one this script needs).
//
// Maestro's `http` binding auto-follows redirects (confirmed live: step
// 1's response is already the POST-redirect target's body, status 200,
// not the 302 itself) and exposes response headers including
// `set-cookie` (confirmed live via a throwaway probe script - not
// documented anywhere in Maestro's own docs at the time of writing).
// The session cookie from step 1 must be threaded through steps 2 and 3
// by hand - the `http` binding has no automatic cookie jar the way a
// real browser or `curl -b/-c` does.
//
// `credential_configuration_ids` is hardcoded to PID MSO Mdoc here,
// matching TC-01's own document choice for easy comparison - see the
// README for how this differs from TC-01's "PID Combined" (which
// requests both MSO Mdoc AND SD-JWT VC in one bundled request; this
// flow, like any real credential offer, requests exactly the
// configuration(s) named in `credential_configuration_ids` and nothing
// else).
function extractCookie(response) {
  const raw = response.headers["set-cookie"];
  if (!raw) {
    throw new Error("No set-cookie header in response - issuer session flow may have changed. headers=" + JSON.stringify(response.headers));
  }
  return raw.split(";")[0];
}

function extractPayload(html) {
  const match = html.match(/name="payload" value='(.*?)'/);
  if (!match) {
    throw new Error("Could not find hidden 'payload' field in response body - issuer page shape may have changed. body=" + html.substring(0, 500));
  }
  return match[1];
}

// Step 1: GET the offer entry point, follow the redirect, capture the
// session cookie and the full credential-type/format payload.
const step1 = http.get("https://ec.dev.issuer.eudiw.dev/credential_offer");
if (!step1.ok) {
  throw new Error("GET /credential_offer failed: status=" + step1.status + " body=" + step1.body);
}
const sessionCookie = extractCookie(step1);
const choicePayload = extractPayload(step1.body);

// Step 2: hand that payload back to get the real credential-selection form.
// (This flow doesn't need to parse the resulting form - the field names
// used in step 3 below are static and already known from a live capture.)
const step2 = http.post("https://ec.dev.issuer.eudiw.dev/display_credential_offer", {
  headers: {
    "Content-Type": "application/x-www-form-urlencoded",
    "Cookie": sessionCookie
  },
  body: "payload=" + encodeURIComponent(choicePayload)
});
if (!step2.ok) {
  throw new Error("POST /display_credential_offer failed: status=" + step2.status + " body=" + step2.body);
}

// Step 3: submit the actual selection - PID (MSO Mdoc) only, Authorization
// Code Grant, default `haip-vci://` scheme (all matching what a real user
// would pick unmodified on this page).
const selectionBody = [
  "eu.europa.ec.eudi.pid_mdoc=" + encodeURIComponent("eu.europa.ec.eudi.pid_mdoc"),
  "Authorization+Code+Grant=auth_code",
  "credential_offer_URI=" + encodeURIComponent("haip-vci://"),
  "proceed=Submit"
].join("&");
const step3 = http.post("https://backend.dev.issuer.eudiw.dev/credential_offer", {
  headers: {
    "Content-Type": "application/x-www-form-urlencoded",
    "Cookie": sessionCookie
  },
  body: selectionBody
});
if (!step3.ok) {
  throw new Error("POST /credential_offer failed: status=" + step3.status + " body=" + step3.body);
}

const urlDataMatch = step3.body.match(/"url_data":\s*"([^"]+)"/);
if (!urlDataMatch) {
  throw new Error("Could not find 'url_data' in the final response - issuer response shape may have changed. body=" + step3.body.substring(0, 1000));
}

output.offerUri = urlDataMatch[1];
console.log("TC-57 setup: offerUri=" + output.offerUri);
