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
# Start the already-bootstrapped local EUDI stack, with strict preflight checks.
#
#   ./start.sh                       preflight-check, then `docker compose up -d`
#   ./start.sh --reconfigure-ip [ip] repoint to a new LAN IP first (delegates to reconfigure-ip.sh)
#   ./start.sh --force               skip the IP-consistency checks (multi-homed / intentional)
#
# STRICT: refuses to start if the stack was never bootstrapped, or if the LAN IP no longer
# matches (stale cert / you changed networks) — and tells you exactly what to run.
# For first-time setup use ./bootstrap.sh; to stop use ./stop.sh.
# ---------------------------------------------------------------------------------------
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$DIR/.." && pwd)"; cd "$DIR"
. "$DIR/lib.sh"                           # detect_local_ip (cross-platform)

# --reconfigure-ip: hand off entirely to reconfigure-ip.sh (regenerates cert, recreates, starts).
if [ "${1:-}" = "--reconfigure-ip" ]; then
  shift; exec ./reconfigure-ip.sh "$@"
fi
FORCE=0
for a in "$@"; do
  case "$a" in
    --force) FORCE=1 ;;
    -h|--help) sed -n '19,27p' "$0"; exit 0 ;;
    *) echo "Unknown option: $a" >&2; exit 2 ;;
  esac
done

fail=0
ok()   { printf '  OK      %s\n' "$*"; }
bad()  { printf '  MISSING %s\n' "$*"; fail=1; }

echo "Preflight checks:"

[ -f .env ] || { echo "  MISSING .env — run ./bootstrap.sh"; exit 1; }
LOCAL_IP="$(grep -E '^LOCAL_IP=' .env | tail -1 | cut -d= -f2 | tr -d '[:space:]')"
echo "$LOCAL_IP" | grep -Eq '^[0-9]{1,3}(\.[0-9]{1,3}){3}$' || { echo "  MISSING valid LOCAL_IP in .env"; exit 1; }
ok "LOCAL_IP = $LOCAL_IP"

CA="$ROOT/network-logic/.local-dev-ca"
for f in \
  "$CA/ca.crt" "$CA/server.crt" "$CA/server.key" \
  config/issuer/config_issuer_backend.yaml \
  config/issuer-oidc/config.json config/issuer-oidc/openid-configuration.json \
  config/issuer-oidc/views.py config/issuer-frontend/frontend_config.yaml \
  config/wallet-provider/signing.p12 \
  config/issuer/privKey/PID-DS-03.key \
  config/statuslist/privKey/PID-DS-0001_UT.pem \
  repos/eudi-srv-statuslist-py/Dockerfile ; do
  [ -e "$f" ] && ok "$f" || bad "$f"
done
for d in config/issuer/credentials_supported config/wallet-provider/schemas/postgresql ; do
  [ -n "$(ls -A "$d" 2>/dev/null || true)" ] && ok "$d/" || bad "$d/ (empty)"
done

# Host-native wallet-provider image (built from source by bootstrap; not pulled).
if docker image inspect eudi-srv-wallet-provider:local >/dev/null 2>&1; then
  ok "wallet-provider image (eudi-srv-wallet-provider:local)"
else
  bad "wallet-provider image (eudi-srv-wallet-provider:local) — build with FORCE_WP_BUILD=1 ./bootstrap.sh --skip-app"
fi

if [ "$fail" = 1 ]; then
  echo; echo "Setup is incomplete. Run:  ./bootstrap.sh"; exit 1
fi

# --- IP consistency (strict; suggest the fix; --force to bypass) ------------------------
ip_problem=0
SAN="$(openssl x509 -in "$CA/server.crt" -noout -ext subjectAltName 2>/dev/null || true)"
if echo "$SAN" | grep -q "$LOCAL_IP"; then ok "TLS cert SAN includes $LOCAL_IP"
else echo "  MISMATCH TLS cert SAN does not include $LOCAL_IP (stale certificate)"; ip_problem=1; fi

CUR="$(detect_local_ip)"
if [ -n "$CUR" ] && [ "$CUR" != "$LOCAL_IP" ]; then
  echo "  MISMATCH this machine's IP is $CUR but LOCAL_IP is $LOCAL_IP (changed networks?)"; ip_problem=1
elif [ -n "$CUR" ]; then ok "machine IP matches LOCAL_IP"; fi

if [ "$ip_problem" = 1 ] && [ "$FORCE" = 0 ]; then
  echo
  echo "IP mismatch — refusing to start with a stale configuration."
  echo "Fix it:   ./start.sh --reconfigure-ip${CUR:+ $CUR}     (or ./reconfigure-ip.sh)"
  echo "Override: ./start.sh --force"
  exit 1
fi

# --- Start ------------------------------------------------------------------------------
echo; echo "Starting stack ..."
docker compose up -d

echo "Health:"
for u in ":8443/.well-known/openid-credential-issuer" ":8445/" ":8444/"; do
  code="$(curl -s -o /dev/null -w '%{http_code}' --cacert "$CA/ca.crt" "https://${LOCAL_IP}${u}" --max-time 8 2>/dev/null || echo 000)"
  printf '  %-45s -> %s\n' "https://${LOCAL_IP}${u}" "$code"
done
echo "Up. Open the localDebug app to issue (Add document > PID > FormEU)."
