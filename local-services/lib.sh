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
# Shared helpers for the local-services scripts. Sourced, not executed.
# ---------------------------------------------------------------------------------------

# detect_local_ip: print the machine's primary LAN IPv4 (best effort), or nothing.
# Cross-platform: macOS (route/ipconfig), then Linux (iproute2), then a last-resort fallback.
detect_local_ip() {
  local ip="" iface
  # macOS: the interface of the default route, then its IPv4 address.
  # (`route -n get default` is macOS syntax; on Linux it fails and we fall through.)
  if route -n get default >/dev/null 2>&1; then
    iface="$(route -n get default 2>/dev/null | awk '/interface:/{print $2}')"
    [ -n "$iface" ] && ip="$(ipconfig getifaddr "$iface" 2>/dev/null || true)"
  fi
  # Linux (iproute2): the source address the kernel would use to reach the internet.
  # More reliable than `hostname -I` on hosts with Docker/VPN bridge interfaces.
  if [ -z "$ip" ] && command -v ip >/dev/null 2>&1; then
    ip="$(ip route get 1.1.1.1 2>/dev/null \
          | awk '{for (i=1;i<=NF;i++) if ($i=="src") {print $(i+1); exit}}')"
  fi
  # Last resort: first address from `hostname -I` (may be a bridge IP; least reliable).
  if [ -z "$ip" ] && command -v hostname >/dev/null 2>&1; then
    ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
  fi
  printf '%s' "$ip"
}
