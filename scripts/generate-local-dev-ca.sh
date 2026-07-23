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
# Generates a local development Certificate Authority (CA) and, optionally, a TLS server
# certificate signed by it, for use with the `local` build flavor.
#
# The CA certificate (public) is copied to
#   network-logic/src/local/res/raw/local_dev_ca.pem
# so the app can bundle and trust it (see the `local` network_security_config.xml).
# Private keys stay under network-logic/.local-dev-ca/ (git-ignored) — never commit them.
#
# Usage:
#   scripts/generate-local-dev-ca.sh ca [--force]
#   scripts/generate-local-dev-ca.sh server [<host-or-ip>] [--force]
#
# `server` resolves the host from, in order: the argument, the LOCAL_IP environment
# variable, `localIp` in local.properties, then 10.0.2.2 (emulator host alias).
# ---------------------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CA_DIR="$ROOT/network-logic/.local-dev-ca"
RAW_DIR="$ROOT/network-logic/src/local/res/raw"
CA_KEY="$CA_DIR/ca.key"
CA_CRT="$CA_DIR/ca.crt"
CA_BUNDLE="$RAW_DIR/local_dev_ca.pem"
DAYS_CA=3650
DAYS_LEAF=825   # keep <= 825 days: some clients reject longer-lived leaf certs

command -v openssl >/dev/null 2>&1 || { echo "ERROR: openssl not found on PATH." >&2; exit 1; }

resolve_host() {
  local h="${1:-}"
  if [ -n "$h" ]; then echo "$h"; return; fi
  if [ -n "${LOCAL_IP:-}" ]; then echo "$LOCAL_IP"; return; fi
  local lp="$ROOT/local.properties"
  if [ -f "$lp" ]; then
    local v
    v="$(grep -E '^[[:space:]]*localIp[[:space:]]*=' "$lp" | tail -1 | sed -E 's/^[^=]*=[[:space:]]*//; s/[[:space:]]*$//')"
    if [ -n "$v" ]; then echo "$v"; return; fi
  fi
  echo "10.0.2.2"
}

gen_ca() {
  local force="${1:-}"
  mkdir -p "$CA_DIR" "$RAW_DIR"
  if [ -f "$CA_KEY" ] && [ -f "$CA_CRT" ] && [ "$force" != "--force" ]; then
    echo "CA already exists at $CA_CRT (pass --force to regenerate)."
  else
    echo "Generating local dev CA ..."
    openssl req -x509 -newkey rsa:4096 -sha256 -nodes \
      -keyout "$CA_KEY" -out "$CA_CRT" -days "$DAYS_CA" \
      -subj "/CN=EUDI Wallet Local Dev CA/O=Local Development/C=EU" \
      -addext "basicConstraints=critical,CA:TRUE" \
      -addext "keyUsage=critical,keyCertSign,cRLSign"
    chmod 600 "$CA_KEY"
  fi
  cp "$CA_CRT" "$CA_BUNDLE"
  echo "CA certificate bundled at : $CA_BUNDLE"
  echo "CA private key            : $CA_KEY  (git-ignored — do not commit)"
  echo
  echo "Next: enable the bundled anchor by uncommenting <certificates src=\"@raw/local_dev_ca\" />"
  echo "in network-logic/src/local/res/xml/network_security_config.xml, OR install the CA on the"
  echo "device (Settings > Security > Install a certificate > CA certificate)."
}

gen_server() {
  local host="" force=""
  for a in "$@"; do
    if [ "$a" = "--force" ]; then force="--force"; else host="$a"; fi
  done
  host="$(resolve_host "$host")"
  [ -f "$CA_KEY" ] && [ -f "$CA_CRT" ] || { echo "ERROR: CA missing. Run '$0 ca' first." >&2; exit 1; }
  mkdir -p "$CA_DIR"
  local key="$CA_DIR/server.key" csr="$CA_DIR/server.csr" crt="$CA_DIR/server.crt" full="$CA_DIR/server-fullchain.crt"

  if [ -f "$crt" ] && [ "$force" != "--force" ]; then
    echo "Server cert already exists at $crt (pass --force to regenerate)."
    return
  fi

  # SAN: the requested host (IP or DNS) plus common local aliases (emulator + loopback).
  local san="DNS:localhost,IP:127.0.0.1,IP:10.0.2.2"
  if echo "$host" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$'; then
    san="IP:$host,$san"
  else
    san="DNS:$host,$san"
  fi

  echo "Generating server certificate for host '$host'"
  echo "  SAN: $san"
  openssl req -newkey rsa:2048 -sha256 -nodes -keyout "$key" -out "$csr" \
    -subj "/CN=$host/O=Local Development/C=EU"
  openssl x509 -req -in "$csr" -CA "$CA_CRT" -CAkey "$CA_KEY" -CAcreateserial \
    -out "$crt" -days "$DAYS_LEAF" -sha256 \
    -extfile <(printf "subjectAltName=%s\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n" "$san")
  rm -f "$csr"
  chmod 600 "$key"
  cat "$crt" "$CA_CRT" > "$full"
  echo "Server private key   : $key"
  echo "Server certificate   : $crt"
  echo "Server full chain    : $full"
  echo
  echo "Configure your local issuer / wallet-provider TLS with the key + certificate above."
}

CMD="${1:-}"; shift || true
case "$CMD" in
  ca)     gen_ca "${1:-}" ;;
  server) gen_server "$@" ;;
  *) echo "Usage: $0 {ca | server [<host-or-ip>]} [--force]" >&2; exit 2 ;;
esac
