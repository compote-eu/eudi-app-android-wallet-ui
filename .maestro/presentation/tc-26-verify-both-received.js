// ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
// navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
// attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
//

// TC-26 post-share verification — confirms the verifier backend
// genuinely received BOTH documents' claims, not just that the
// transaction as a whole succeeded.
//
// IMPORTANT - why this does NOT use a raw substring search, unlike a
// naive port of tc-22-verify-server-receipt.js's approach: during
// TC-26's exploration, a raw search for "123456789" (mDL's
// document_number value) inside pid-cred's own decoded CBOR bytes
// ALSO matched - not because PID discloses document_number, but
// because both credentials' IssuerAuth carry X.509 certificate
// metadata from the same test PKI containing the literal substring
// "LEIEU-123456789" (a Legal Entity Identifier field, unrelated to any
// disclosed claim). A plain `bytes.includes("123456789")` check on
// pid-cred would have silently passed even if mDL's claim had never
// been disclosed at all. DO NOT simplify this back into a substring
// check - it would reintroduce exactly that false positive.
//
// Instead, this reconstructs the EXACT byte adjacency of a genuine
// CBOR IssuerSignedItem map entry - "elementValue" immediately
// followed by its value, then "elementIdentifier" immediately followed
// by its identifier name, each string prefixed by its own CBOR
// major-type-3 (text string) short-form length byte
// (0x60 + length, valid for a CBOR string < 24 chars, true here for
// every string checked). This exact contiguous sequence is how mdoc
// namespaces actually encode a disclosed claim (confirmed by direct
// inspection of the decoded bytes during exploration - "elementValue"
// then value then "elementIdentifier" then identifier, in that order)
// and cannot occur by coincidence in unrelated certificate metadata,
// unlike a bare substring.
function base64UrlDecodeToByteString(b64url) {
  const base64 = b64url.replace(/-/g, "+").replace(/_/g, "/");
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  let bytes = "";
  let buffer = 0;
  let bitsCollected = 0;
  for (let i = 0; i < base64.length; i++) {
    const c = base64[i];
    if (c === "=") break;
    const val = alphabet.indexOf(c);
    if (val === -1) continue;
    buffer = (buffer << 6) | val;
    bitsCollected += 6;
    if (bitsCollected >= 8) {
      bitsCollected -= 8;
      bytes += String.fromCharCode((buffer >> bitsCollected) & 0xff);
    }
  }
  return bytes;
}

function cborShortStringPrefix(len) {
  // CBOR major type 3 (text string), short form - only valid for
  // len < 24, true for every fixed keyword and claim value used below.
  return String.fromCharCode(0x60 + len);
}

function elementPairPresent(byteString, valueText, identifierText) {
  const pattern =
    cborShortStringPrefix("elementValue".length) + "elementValue" +
    cborShortStringPrefix(valueText.length) + valueText +
    cborShortStringPrefix("elementIdentifier".length) + "elementIdentifier" +
    cborShortStringPrefix(identifierText.length) + identifierText;
  return byteString.indexOf(pattern) !== -1;
}

const response = http.get("https://dev.verifier-backend.eudiw.dev/ui/presentations/" + output.transactionId);

if (!response.ok) {
  throw new Error("Verifier GET /ui/presentations/{id} failed: status=" + response.status + " body=" + response.body);
}

const parsed = json(response.body);
const vpToken = parsed.vp_token || {};

const pidCred = vpToken["pid-cred"] && vpToken["pid-cred"][0];
const mdlCred = vpToken["mdl-cred"] && vpToken["mdl-cred"][0];

if (!pidCred || !mdlCred) {
  throw new Error("Verifier backend missing pid-cred and/or mdl-cred in vp_token - both were expected. vp_token keys=" + JSON.stringify(Object.keys(vpToken)));
}

const pidBytes = base64UrlDecodeToByteString(pidCred);
const mdlBytes = base64UrlDecodeToByteString(mdlCred);

const pidHasFamilyName = elementPairPresent(pidBytes, "Testerova", "family_name");
const mdlHasDocumentNumber = elementPairPresent(mdlBytes, "123456789", "document_number");

if (!pidHasFamilyName) {
  throw new Error("pid-cred does not contain a genuine family_name/Testerova elementIdentifier/elementValue pair - PID claim was not actually disclosed.");
}

if (!mdlHasDocumentNumber) {
  throw new Error("mdl-cred does not contain a genuine document_number/123456789 elementIdentifier/elementValue pair - mDL claim was not actually disclosed.");
}

console.log("TC-26 verify: both documents' claims confirmed as genuine elementIdentifier/elementValue pairs - pid-cred family_name=Testerova, mdl-cred document_number=123456789");
