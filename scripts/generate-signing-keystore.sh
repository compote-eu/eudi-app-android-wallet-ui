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
# Generates an EC (P-256 / ES256) PKCS12 keystore and exports its public certificate.
# Used for the wallet-provider attestation signing key; reusable for any JVM service that
# needs an ES256 keystore (e.g. the verifier access-certificate keystore).
#
# Usage: generate-signing-keystore.sh <out.p12> <alias> <password> ["<dname>"]
# ---------------------------------------------------------------------------------------
set -euo pipefail

OUT="${1:?output .p12 path required}"
ALIAS="${2:?alias required}"
PASS="${3:?password required}"
DNAME="${4:-CN=$ALIAS, O=Local Development, C=EU}"

command -v keytool >/dev/null 2>&1 || { echo "ERROR: keytool not found (needs a JDK on PATH)." >&2; exit 1; }

mkdir -p "$(dirname "$OUT")"
if [ -f "$OUT" ]; then
  echo "Keystore already exists at $OUT (delete it to regenerate)."
else
  keytool -genkeypair -alias "$ALIAS" -keyalg EC -groupname secp256r1 \
    -sigalg SHA256withECDSA -validity 3650 -storetype PKCS12 \
    -keystore "$OUT" -storepass "$PASS" -keypass "$PASS" -dname "$DNAME"
  echo "Keystore created : $OUT (alias '$ALIAS', ES256)"
fi

CRT="${OUT%.p12}.crt"
keytool -exportcert -rfc -alias "$ALIAS" -keystore "$OUT" -storepass "$PASS" -file "$CRT" >/dev/null
echo "Public cert      : $CRT"
echo "Register this public key with the issuer so it trusts wallet attestations."
