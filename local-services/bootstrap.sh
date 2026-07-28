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
# =======================================================================================
# One-shot setup of the full local EUDI issuer stack for the `local` build flavor.
#
# From a fresh clone a teammate only needs:
#     cp .env.example .env            # (optional) set LOCAL_IP; auto-detected otherwise
#     ./bootstrap.sh
#     # then: install the dev CA on the device, select `localDebug`, run
#
# Everything it produces lands under config/ and repos/ (git-ignored, per-developer).
# Re-running is safe: it refreshes config from the committed templates and regenerates
# anything missing. Pass --skip-app to leave the Android app untouched.
# =======================================================================================
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"     # local-services/
ROOT="$(cd "$DIR/.." && pwd)"            # repo root
cd "$DIR"
. "$DIR/lib.sh"                           # detect_local_ip (cross-platform)
GRADLEW="$ROOT/gradlew"
SKIP_APP=0
for a in "$@"; do [ "$a" = "--skip-app" ] && SKIP_APP=1; done

step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

# ---------------------------------------------------------------------------------------
step "0/10  Prerequisites & LOCAL_IP"
# ---------------------------------------------------------------------------------------
for tool in docker openssl curl python3 git; do
  command -v "$tool" >/dev/null 2>&1 || { echo "ERROR: '$tool' is required but not found." >&2; exit 1; }
done
[ -f .env ] || cp .env.example .env
LOCAL_IP="$(grep -E '^LOCAL_IP=' .env | tail -1 | cut -d= -f2 | tr -d '[:space:]')"
# Auto-detect from the default route if unset / left at a placeholder (macOS + Linux).
if [ -z "$LOCAL_IP" ] || [ "$LOCAL_IP" = "192.168.1.242" ]; then
  DET="$(detect_local_ip)"
  [ -n "$DET" ] && LOCAL_IP="$DET"
fi
echo "$LOCAL_IP" | grep -Eq '^[0-9]{1,3}(\.[0-9]{1,3}){3}$' || {
  echo "ERROR: set LOCAL_IP in .env to your LAN IP (auto-detect failed)." >&2; exit 1; }
echo "Using LOCAL_IP = $LOCAL_IP"
# Keep .env and the app's local.properties in sync with LOCAL_IP.
set_kv() { local f="$1" k="$2" v="$3"; if grep -qE "^${k}=" "$f" 2>/dev/null; then
  sed -i.bak -E "s|^${k}=.*|${k}=${v}|" "$f" && rm -f "$f.bak"; else printf '%s=%s\n' "$k" "$v" >> "$f"; fi; }
set_kv .env LOCAL_IP "$LOCAL_IP"
set_kv "$ROOT/local.properties" localIp "$LOCAL_IP"

# ---------------------------------------------------------------------------------------
step "1/10  Dev CA + TLS server cert (dev CA reused if it already exists)"
# ---------------------------------------------------------------------------------------
# generateLocalDevCa: creates the CA and copies it to res/raw for the app to bundle.
# generateLocalServerCert: leaf cert with the LAN IP in its SAN (dev CA preserved).
( cd "$ROOT" && "$GRADLEW" -q :network-logic:generateLocalDevCa \
                       && "$GRADLEW" -q :network-logic:generateLocalServerCert -PhostIp="$LOCAL_IP" -Pforce )

# ---------------------------------------------------------------------------------------
step "2/10  Wallet-provider ES256 signing keystore"
# ---------------------------------------------------------------------------------------
( cd "$ROOT" && "$GRADLEW" -q :network-logic:generateWalletProviderSigningKey )

# ---------------------------------------------------------------------------------------
step "3/10  Render service configs from templates/ (substitute LOCAL_IP)"
# ---------------------------------------------------------------------------------------
# Our known-good configs are committed under templates/ with a __LOCAL_IP__ placeholder,
# so setup is deterministic (no fetch-and-patch of upstream = no version-skew surprises).
render() { mkdir -p "$(dirname "$2")"; sed "s/__LOCAL_IP__/${LOCAL_IP}/g" "$1" > "$2"; echo "  $2"; }
render templates/issuer/config_issuer_backend.yaml       config/issuer/config_issuer_backend.yaml
render templates/issuer-oidc/config.json                 config/issuer-oidc/config.json
render templates/issuer-oidc/openid-configuration.json   config/issuer-oidc/openid-configuration.json
render templates/issuer-oidc/views.py                    config/issuer-oidc/views.py
render templates/issuer-frontend/frontend_config.yaml    config/issuer-frontend/frontend_config.yaml
: > config/issuer-oidc/oidc_trusted_attesters   # empty trust list is fine for the public-client flow

# ---------------------------------------------------------------------------------------
step "4/10  Issuer Document-Signer keys + global (nonce / credential-request) keys"
# ---------------------------------------------------------------------------------------
./gen-issuer-ds.sh    # writes config/issuer/{privKey,cert}/* signed by the dev CA

# ---------------------------------------------------------------------------------------
step "5/10  Issuer credential metadata overrides (add key_attestations_required + both proofs)"
# ---------------------------------------------------------------------------------------
# The app's OpenID4VCI SDK requires every proof type to declare key_attestations_required and
# to offer BOTH jwt + attestation proofs. Extract the metadata from the issuer image and patch.
if [ ! -d config/issuer/credentials_supported ] || [ -z "$(ls -A config/issuer/credentials_supported 2>/dev/null)" ]; then
  mkdir -p config/issuer/credentials_supported
  ISSUER_IMG=ghcr.io/eu-digital-identity-wallet/eudi-srv-web-issuing-eudiw-py:latest
  docker run --rm --entrypoint sh "$ISSUER_IMG" -c \
    'cd /app/app/metadata_config/credentials_supported && tar cf - .' | tar xf - -C config/issuer/credentials_supported
  python3 - <<'PY'
import json, glob
for p in glob.glob("config/issuer/credentials_supported/*.json"):
    d = json.load(open(p)); changed = False
    for cid, cfg in d.items():
        pts = cfg.get("proof_types_supported") if isinstance(cfg, dict) else None
        if isinstance(pts, dict) and pts:
            algs = next((m.get("proof_signing_alg_values_supported") for m in pts.values()
                         if isinstance(m, dict) and m.get("proof_signing_alg_values_supported")), ["ES256"])
            for t in ("jwt", "attestation"):
                m = pts.get(t)
                if not isinstance(m, dict): pts[t] = m = {}; changed = True
                m.setdefault("proof_signing_alg_values_supported", algs)
                if m.get("key_attestations_required") is None: m["key_attestations_required"] = {}; changed = True
    if changed: json.dump(d, open(p, "w"), indent=2)
print("  patched credential metadata")
PY
else
  echo "  credentials_supported already present — skipping"
fi

# ---------------------------------------------------------------------------------------
step "6/10  Wallet-provider source (pinned v0.2.1) + PostgreSQL schema"
# ---------------------------------------------------------------------------------------
# Cloned once here and reused in step 8 to build a host-native image. Pinned to a tag so
# the schema and the built image always match (v0.2.1 == ghcr :latest at time of writing).
WP=repos/eudi-srv-wallet-provider
WP_TAG=v0.2.1
if [ -d "$WP/.git" ]; then
  git -C "$WP" fetch --depth 1 origin "refs/tags/$WP_TAG:refs/tags/$WP_TAG" 2>/dev/null || true
  git -C "$WP" checkout -q "$WP_TAG"
else
  git clone --depth 1 --branch "$WP_TAG" \
    https://github.com/eu-digital-identity-wallet/eudi-srv-wallet-provider "$WP"
fi
if [ ! -d config/wallet-provider/schemas/postgresql ]; then
  mkdir -p config/wallet-provider/schemas
  cp -R "$WP/schemas/postgresql" config/wallet-provider/schemas/
  echo "  copied schemas/postgresql from $WP@$WP_TAG"
else
  echo "  DB schema already present — skipping"
fi

# ---------------------------------------------------------------------------------------
step "7/10  Status-list service (clone build-from-source, overlay config, generate key)"
# ---------------------------------------------------------------------------------------
SL=repos/eudi-srv-statuslist-py
[ -d "$SL" ] || git clone --depth 1 https://github.com/eu-digital-identity-wallet/eudi-srv-statuslist-py "$SL"
# Overlay our localized config (FC country, LOCAL_IP) and a build Dockerfile (none ships upstream).
render templates/statuslist/config_service.py "$SL/app/config_service.py"
cat > "$SL/Dockerfile" <<'DOCKER'
FROM python:3.10-slim
WORKDIR /app
COPY app/requirements.txt /app/app/requirements.txt
RUN pip install --no-cache-dir -r /app/app/requirements.txt
COPY . /app
ENV FLASK_APP=app
EXPOSE 5000
CMD ["flask", "run", "--host=0.0.0.0", "--port=5000"]
DOCKER
# Keep the clone's `git status` clean: our overlay + Dockerfile are byproducts, not edits
# to contribute upstream. skip-worktree hides the tracked config_service.py modification;
# .git/info/exclude (local-only) hides the untracked Dockerfile without adding a tracked
# .gitignore. Both are re-applied on every fresh clone, so this stays quiet for teammates.
git -C "$SL" update-index --skip-worktree app/config_service.py 2>/dev/null || true
grep -qxF 'Dockerfile' "$SL/.git/info/exclude" 2>/dev/null || echo 'Dockerfile' >> "$SL/.git/info/exclude"

# Status-list signing key (the FC country maps to the UT key), signed by the dev CA.
if [ ! -f config/statuslist/cert/PID-DS-0001_UT_cert.der ]; then
  mkdir -p config/statuslist/privKey config/statuslist/cert
  CA="$ROOT/network-logic/.local-dev-ca"
  openssl ecparam -name prime256v1 -genkey -noout -out config/statuslist/privKey/PID-DS-0001_UT.pem
  openssl req -new -key config/statuslist/privKey/PID-DS-0001_UT.pem \
    -subj "/CN=PID DS UT StatusList/O=Local Development/C=UT" -out /tmp/sl.csr
  openssl x509 -req -in /tmp/sl.csr -CA "$CA/ca.crt" -CAkey "$CA/ca.key" -CAcreateserial -days 825 -sha256 \
    -extfile <(printf "basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\n") -out /tmp/sl.crt
  openssl x509 -in /tmp/sl.crt -outform DER -out config/statuslist/cert/PID-DS-0001_UT_cert.der
  rm -f /tmp/sl.csr /tmp/sl.crt; echo "  generated status-list key"
else
  echo "  status-list key already present — skipping"
fi

# ---------------------------------------------------------------------------------------
step "8/10  Native wallet-provider image (build from source — no arm64 image is published)"
# ---------------------------------------------------------------------------------------
# Upstream publishes only a linux/amd64 image, which runs emulated (and slow) on Apple
# Silicon — and future macOS drops amd64 emulation. So we build a host-native image from
# the source cloned in step 6 with Jib. Jib defaults to linux/amd64 regardless of host, so
# we pass the platform explicitly (derived from `uname -m`) to get a native build for both
# Apple Silicon and Intel/Linux teammates. Toolchain JDK 25 is auto-provisioned by Gradle
# (foojay); the first build downloads dependencies and can take a few minutes.
WP_IMAGE=eudi-srv-wallet-provider:local
case "$(uname -m)" in
  arm64|aarch64) WP_PLATFORM=linux/arm64 ;;
  x86_64|amd64)  WP_PLATFORM=linux/amd64 ;;
  *) echo "ERROR: unsupported CPU arch '$(uname -m)' for the wallet-provider build." >&2; exit 1 ;;
esac
if docker image inspect "$WP_IMAGE" >/dev/null 2>&1 && [ "${FORCE_WP_BUILD:-0}" != "1" ]; then
  echo "  $WP_IMAGE already built — skipping (set FORCE_WP_BUILD=1 to rebuild)"
else
  echo "  building $WP_IMAGE for $WP_PLATFORM (Jib → local Docker daemon) ..."
  ( cd "$WP" && ./gradlew --no-daemon --console=plain :wallet-provider-service:jibDockerBuild \
      --image="$WP_IMAGE" -Djib.from.platforms="$WP_PLATFORM" )
  echo "  built $WP_IMAGE ($WP_PLATFORM)"
fi

# ---------------------------------------------------------------------------------------
step "9/10  Build & start the stack"
# ---------------------------------------------------------------------------------------
docker compose up -d --build

# ---------------------------------------------------------------------------------------
step "10/10  Android app (localDebug)"
# ---------------------------------------------------------------------------------------
if [ "$SKIP_APP" = "0" ]; then
  ( cd "$ROOT" && "$GRADLEW" :app:installLocalDebug ) \
    || echo "  WARN: app install failed (device connected?). Re-run: ./gradlew :app:installLocalDebug"
else
  echo "  skipped (--skip-app)"
fi

cat <<EOF

Done. Local EUDI stack is up and targeting ${LOCAL_IP}.

Remaining manual step (device trust for the in-browser auth page):
  adb push ../network-logic/.local-dev-ca/ca.crt /sdcard/Download/
  → Settings > Security > Install a certificate > CA certificate > pick it

Then run the localDebug app: Add document > PID > select "FormEU" > fill form > submit.
EOF
