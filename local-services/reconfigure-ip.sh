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
# Repoint the whole local stack + app at a new LAN IP (after moving networks).
#
# It updates the IP in every per-developer file (local.properties, .env, and all backend
# config under config/ and repos/), regenerates the TLS server cert for the new IP (the
# dev CA is preserved, so the device keeps trusting it — no CA re-install needed),
# recreates the docker stack, and rebuilds/reinstalls the `localDebug` app.
#
# Usage:
#   ./reconfigure-ip.sh [<new-ip>] [--skip-app]
#     <new-ip>     target LAN IP; auto-detected from the default route if omitted
#     --skip-app   don't rebuild/reinstall the Android app
# ---------------------------------------------------------------------------------------
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"      # local-services/
ROOT="$(cd "$DIR/.." && pwd)"             # repo root
cd "$DIR"
. "$DIR/lib.sh"                           # detect_local_ip (cross-platform)

SKIP_APP=0; NEW=""
for a in "$@"; do
  case "$a" in
    --skip-app) SKIP_APP=1 ;;
    -*) echo "Unknown option: $a" >&2; exit 2 ;;
    *) NEW="$a" ;;
  esac
done

# Detect the new IP from the default route if not provided (macOS + Linux).
[ -z "$NEW" ] && NEW="$(detect_local_ip)"
echo "$NEW" | grep -Eq '^[0-9]{1,3}(\.[0-9]{1,3}){3}$' || {
  echo "Could not determine a valid LAN IP. Pass it explicitly: ./reconfigure-ip.sh <ip>" >&2; exit 1; }

[ -f .env ] || { echo "No .env found. Run: cp .env.example .env" >&2; exit 1; }
OLD="$(grep -E '^LOCAL_IP=' .env | tail -1 | cut -d= -f2 | tr -d '[:space:]')"

echo "Reconfiguring local stack: ${OLD:-<unset>} -> ${NEW}"

# 1. Replace the old IP across per-developer files (never touches committed README/.env.example).
if [ -n "$OLD" ] && [ "$OLD" != "$NEW" ]; then
  # config/ + repos/ files that contain the old IP
  while IFS= read -r f; do
    [ -f "$f" ] || continue
    sed -i.bak "s/${OLD}/${NEW}/g" "$f" && rm -f "$f.bak" && echo "  updated $f"
  done < <(grep -rlF "$OLD" config repos 2>/dev/null || true)
  # dotfiles
  for f in .env "$ROOT/local.properties"; do
    [ -f "$f" ] && grep -qF "$OLD" "$f" && sed -i.bak "s/${OLD}/${NEW}/g" "$f" && rm -f "$f.bak" && echo "  updated $f"
  done
fi

# Ensure the canonical knobs are set (covers OLD being unset or already == NEW).
set_kv() { # <file> <key> <value>
  local f="$1" k="$2" v="$3"
  [ -f "$f" ] || { echo "${k}=${v}" > "$f"; return; }
  if grep -qE "^${k}=" "$f"; then
    sed -i.bak -E "s|^${k}=.*|${k}=${v}|" "$f" && rm -f "$f.bak"
  else
    printf '%s=%s\n' "$k" "$v" >> "$f"
  fi
}
set_kv "$ROOT/local.properties" "localIp" "$NEW"
set_kv ".env" "LOCAL_IP" "$NEW"

# 2. Regenerate the TLS server cert for the new IP (CA is preserved).
echo "Regenerating TLS server cert for ${NEW} ..."
( cd "$ROOT" && ./gradlew -q :network-logic:generateLocalServerCert -PhostIp="$NEW" -Pforce )

# 3. Recreate the docker stack (--build picks up the statuslist config change).
echo "Recreating docker stack ..."
docker compose up -d --build --force-recreate

# 4. Rebuild + reinstall the app so BuildConfig.LOCAL_IP takes effect.
if [ "$SKIP_APP" = "0" ]; then
  echo "Rebuilding + installing the app (localDebug) ..."
  ( cd "$ROOT" && ./gradlew :app:installLocalDebug ) \
    || echo "WARN: app install failed (device connected?). Re-run: ./gradlew :app:installLocalDebug"
else
  echo "Skipping app rebuild (--skip-app). Reinstall localDebug so BuildConfig.LOCAL_IP updates."
fi

echo
echo "Done. Local stack + app now target ${NEW}."
echo "The dev CA is unchanged, so the device still trusts it — no CA re-install needed."
