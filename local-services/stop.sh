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
# Stop the local EUDI services (counterpart to bootstrap.sh).
#
#   ./stop.sh              stop containers; keep DB/status-list data and config
#   ./stop.sh --volumes    also remove the docker volumes (wipes DB + issued status data)
#   ./stop.sh --reset      volumes + generated config/ and repos/ (clean slate;
#                          keeps .env and the dev CA so device trust survives)
#
# Restart after a plain stop with:  docker compose up -d   (or ./bootstrap.sh)
# ---------------------------------------------------------------------------------------
set -euo pipefail
cd "$(cd "$(dirname "$0")" && pwd)"

MODE=stop
for a in "$@"; do
  case "$a" in
    --volumes|-v) MODE=volumes ;;
    --reset)      MODE=reset ;;
    *) echo "Usage: ./stop.sh [--volumes | --reset]" >&2; exit 2 ;;
  esac
done

case "$MODE" in
  stop)
    docker compose down
    echo "Stopped. Data + config kept. Restart with: docker compose up -d" ;;
  volumes)
    docker compose down -v
    echo "Stopped; volumes removed (DB + status-list wiped). Config kept." ;;
  reset)
    docker compose down -v
    rm -rf config repos
    echo "Reset: volumes + generated config/ and repos/ removed."
    echo "Kept: .env and the dev CA (network-logic/.local-dev-ca). Re-run ./bootstrap.sh to rebuild." ;;
esac
