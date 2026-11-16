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
# Per-developer secrets, kept in .env and rendered into the configs (step 3). The services
# refuse keys shorter than 32 characters or known placeholders such as "test" / "secret"
# (status list v0.10.0+, issuer v0.11.0+), so a random value replaces a short or missing one.
SECRETS="STATUSLIST_API_KEY ISSUER_SECRET_KEY ISSUER_BACKEND_API_KEY ISSUER_ADMIN_API_KEY ISSUER_AS_API_KEY ISSUER_PAYLOAD_KEY ISSUER_DB_PASSWORD"
for name in $SECRETS; do
  val="$(grep -E "^${name}=" .env | tail -1 | cut -d= -f2 | tr -d '[:space:]' || true)"   # absent: grep fails
  if [ "${#val}" -lt 32 ]; then
    val="$(openssl rand -hex 32)"
    set_kv .env "$name" "$val"
    echo "Generated $name in .env"
  fi
  printf -v "$name" "%s" "$val"
done

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
step "3/10  Render service configs from templates/ (substitute LOCAL_IP + secrets)"
# ---------------------------------------------------------------------------------------
# Our known-good configs are committed under templates/ with __LOCAL_IP__ and __<SECRET>__
# placeholders, so setup is deterministic (no fetch-and-patch of upstream = no version-skew
# surprises; the images they fit are pinned in compose.yaml).
render() { mkdir -p "$(dirname "$2")"
  local args=(-e "s/__LOCAL_IP__/${LOCAL_IP}/g") name
  for name in $SECRETS; do args+=(-e "s/__${name}__/${!name}/g"); done
  sed "${args[@]}" "$1" > "$2"; echo "  $2"; }
render templates/issuer/config_issuer_backend.yaml       config/issuer/config_issuer_backend.yaml
render templates/issuer-oidc/config.yaml                 config/issuer-oidc/config.yaml
render templates/issuer-frontend/frontend_config.yaml    config/issuer-frontend/frontend_config.yaml
# Left by setups older than the 2026-11 issuer releases; nothing reads them any more.
rm -f config/issuer-oidc/config.json config/issuer-oidc/openid-configuration.json config/issuer-oidc/views.py
rm -rf config/issuer/credentials_supported
[ -f config/issuer-oidc/oidc_trusted_attesters ] && rm -f config/issuer-oidc/oidc_trusted_attesters

# ---------------------------------------------------------------------------------------
step "4/10  Issuer Document-Signer keys + global (nonce / credential-request) keys"
# ---------------------------------------------------------------------------------------
./gen-issuer-ds.sh    # writes config/issuer/{privKey,cert}/* signed by the dev CA
# The issuer images run as uid 10001, not root, so the bind-mounted keys must be readable by
# others (openssl writes them 0600). Local dev keys; on Linux a 0600 key stops the issuer starting.
chmod 644 config/issuer/privKey/*

# ---------------------------------------------------------------------------------------
step "5/10  Trust anchors for the wallet-provider's attestations"
# ---------------------------------------------------------------------------------------
# The wallet-provider signs wallet attestations (WIA) and key attestations with the self-signed
# certificate of step 2. The issuer checks key attestations against it (trusted_CAs_paths) and
# the authorization server checks WIAs against it (oidc_trusted_attesters). Both read *.pem only.
# (The credential metadata needs no patch since issuer v0.11.0: every configuration it publishes
# offers both the jwt and the attestation proof with key_attestations_required.)
for d in config/issuer/trust/wallet-providers config/issuer-oidc/oidc_trusted_attesters; do
  mkdir -p "$d"
  openssl x509 -in config/wallet-provider/signing.crt -out "$d/wallet-provider.pem"
  echo "  $d/wallet-provider.pem"
done

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
step "7/10  Status-list service (config + signing key; the image is pulled, pinned in compose.yaml)"
# ---------------------------------------------------------------------------------------
# Since v0.10.0 upstream publishes a multi-arch image and reads a YAML config, so there is
# nothing to clone or overlay any more: render the config and generate the signing key.
# An old v0.9.0 clone under repos/ is no longer used and can be deleted.
render templates/statuslist/config_statuslist.yaml config/statuslist/config_statuslist.yaml

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
# The image runs as uid 10001, not root, so the bind-mounted key must be readable by others
# (openssl writes it 0600). A local dev key; on Linux a 0600 file stops the service starting.
chmod 644 config/statuslist/privKey/PID-DS-0001_UT.pem

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
# --force-recreate: every service reads its bind-mounted config (and Caddy its Caddyfile) only
# at start-up, and compose does not recreate a container whose mounted file changed, so a
# re-run would otherwise keep serving the old config. Volumes (databases, keys) are kept.
docker compose up -d --build --force-recreate

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
