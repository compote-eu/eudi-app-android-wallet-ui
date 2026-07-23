#!/usr/bin/env bash
#
# Copyright (c) 2026 European Commission
#
# Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the European
# Commission - subsequent versions of the EUPL (the "Licence"); You may not use this work
# except in compliance with the Licence.
#
# You may obtain a copy of the Licence at:
# https://joinup.ec.europa.eu/software/page/eupl
#
# Unless required by applicable law or agreed to in writing, software distributed under
# the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF
# ANY KIND, either express or implied. See the Licence for the specific language
# governing permissions and limitations under the Licence.
#
# ---------------------------------------------------------------------------------------
# Generates Document Signer (DS) key/cert material for the local issuer, signed by the
# local dev CA (network-logic/.local-dev-ca). FOR LOCAL DEVELOPMENT ONLY — these are not
# ISO 18013-5 IACA/DS-profile compliant; they work because the `local` flavor relaxes
# issuer trust (INFORM + relaxCertificateProfiles).
#
# Produces, under local-services/config/issuer/:
#   privKey/PID-DS-0002_<CC>.pem   (EC P-256 private key, per country)
#   cert/PID-DS-0002_<CC>_cert.der (DS certificate, DER, per country)
#   privKey/PID-DS-03.key + cert/PID-DS-03.crt  (metadata signing DS, PEM)
#   cert/dev_ca.crt                (the dev CA, as a trust anchor)
#
# Run from the local-services/ directory:  ./gen-issuer-ds.sh [--force]
# ---------------------------------------------------------------------------------------
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"           # local-services/
CA_DIR="$(cd "$DIR/.." && pwd)/network-logic/.local-dev-ca"
CA_CRT="$CA_DIR/ca.crt"
CA_KEY="$CA_DIR/ca.key"
CERT_DIR="$DIR/config/issuer/cert"
KEY_DIR="$DIR/config/issuer/privKey"
FORCE="${1:-}"

COUNTRIES=(EU UT PT EE)   # matches the paths in config_issuer_backend.yaml

command -v openssl >/dev/null 2>&1 || { echo "ERROR: openssl not found on PATH." >&2; exit 1; }
[ -f "$CA_CRT" ] && [ -f "$CA_KEY" ] || {
  echo "ERROR: dev CA missing. Run: (cd .. && ./gradlew :network-logic:generateLocalDevCa)" >&2; exit 1; }

mkdir -p "$CERT_DIR" "$KEY_DIR"

sign_leaf() { # <key> <subject> <out-pem-cert>
  local key="$1" subj="$2" out="$3" csr
  csr="$(mktemp)"
  openssl req -new -key "$key" -subj "$subj" -out "$csr"
  openssl x509 -req -in "$csr" -CA "$CA_CRT" -CAkey "$CA_KEY" -CAcreateserial \
    -days 825 -sha256 -out "$out" \
    -extfile <(printf "basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\n")
  rm -f "$csr"
}

for cc in "${COUNTRIES[@]}"; do
  key="$KEY_DIR/PID-DS-0002_${cc}.pem"
  der="$CERT_DIR/PID-DS-0002_${cc}_cert.der"
  if [ -f "$key" ] && [ -f "$der" ] && [ "$FORCE" != "--force" ]; then
    echo "DS for $cc exists — skipping (use --force)."; continue
  fi
  openssl ecparam -name prime256v1 -genkey -noout -out "$key"
  pem_crt="$(mktemp)"
  sign_leaf "$key" "/CN=PID DS ${cc}/O=Local Development/C=${cc}" "$pem_crt"
  openssl x509 -in "$pem_crt" -outform DER -out "$der"
  rm -f "$pem_crt"
  echo "DS $cc: $key + $der"
done

# Metadata signing DS (PEM key + PEM cert)
md_key="$KEY_DIR/PID-DS-03.key"
md_crt="$CERT_DIR/PID-DS-03.crt"
if [ ! -f "$md_key" ] || [ ! -f "$md_crt" ] || [ "$FORCE" = "--force" ]; then
  openssl ecparam -name prime256v1 -genkey -noout -out "$md_key"
  sign_leaf "$md_key" "/CN=PID Metadata DS/O=Local Development/C=EU" "$md_crt"
  echo "Metadata DS: $md_key + $md_crt"
else
  echo "Metadata DS exists — skipping."
fi

# Global issuer keys.
#  - nonce signing key: RSA 2048
#  - credential-request/encryption key: EC P-256 (issuer requires a P-256 EC key here)
nonce_key="$KEY_DIR/nonce_rsa2048.pem"
if [ -f "$nonce_key" ] && [ "$FORCE" != "--force" ]; then
  echo "Global key nonce_rsa2048 exists — skipping."
else
  openssl genrsa -out "$nonce_key" 2048
  echo "Global key: $nonce_key"
fi

cred_key="$KEY_DIR/credential_request.pem"
if [ -f "$cred_key" ] && [ "$FORCE" != "--force" ]; then
  echo "Global key credential_request exists — skipping."
else
  openssl ecparam -name prime256v1 -genkey -noout -out "$cred_key"
  echo "Global key: $cred_key (EC P-256)"
fi

# Trust anchor (dev CA) in the trusted_CAs_path directory
cp "$CA_CRT" "$CERT_DIR/dev_ca.crt"
echo "Trust anchor: $CERT_DIR/dev_ca.crt"

echo
echo "Done. Ensure config_issuer_backend.yaml metadata_*_path point at the mounted files:"
echo "  metadata_signing_key_path:        /etc/eudiw/pid-issuer-dev/privKey/PID-DS-03.key"
echo "  metadata_access_certificate_path: /etc/eudiw/pid-issuer-dev/cert/PID-DS-03.crt"
