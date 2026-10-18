#!/bin/bash
# ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
# navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
# attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
#

# TC-32 helper — prints the number of Keychain generic-password (genp) rows
# for this app's keychain-access-group, queried directly from the iOS
# Simulator's own keychain database via sqlite3.
#
# Why this has to be a real shell script, not a Maestro runScript: Maestro's
# JS engine (used by runScript:/evalScript:) only exposes four bindings -
# confirmed by decompiling maestro-client.jar's GraalJsEngine - http, output,
# env, and an internal maestro binding. There is no process/exec/shell
# capability at all, so a Maestro flow file cannot invoke sqlite3, xcrun
# simctl, or any other external command itself. This script exists precisely
# to be the shell-level piece that a pure-YAML flow structurally cannot be.
#
# The Simulator's keychain is a plain, unencrypted SQLite database (unlike a
# real device, where it's Secure-Enclave-protected and not directly
# queryable) - this only works against a Simulator, which is exactly our
# CI/local target here.
#
# Usage: check-keychain-count.sh <UDID>
# Prints a single integer to stdout: the row count for this access group.
set -euo pipefail

UDID="${1:?Usage: check-keychain-count.sh <UDID>}"

# Confirmed via direct sqlite3 query during TC-32's exploration - the
# keychain-access-group actually written for this app's entries. Matches
# "$(AppIdentifierPrefix)$(PRODUCT_BUNDLE_IDENTIFIER)" from
# EudiWallet.entitlements, where AppIdentifierPrefix resolves to
# DEVELOPMENT_TEAM (AZXQE7588Y, hardcoded in project.pbxproj's Debug Dev
# config) followed by a dot - not something CODE_SIGN_IDENTITY="-" ad-hoc
# signing overrides at the entitlements level, confirmed empirically against
# the actual CI/local build pipeline as it exists today.
ACCESS_GROUP="AZXQE7588Y.eu.europa.ec.euidi.dev"

DB="/Users/$(whoami)/Library/Developer/CoreSimulator/Devices/$UDID/data/Library/Keychains/keychain-2-debug.db"

if [ ! -f "$DB" ]; then
  echo "Keychain database not found at $DB - is the simulator booted and has it been used at least once?" >&2
  exit 1
fi

# Force a WAL checkpoint first - confirmed during exploration that without
# this, a query run immediately after a write (e.g. right after app launch)
# can read stale data from before the write, since SQLite's WAL file hasn't
# been merged into the main db file yet.
sqlite3 "$DB" "PRAGMA wal_checkpoint(FULL);" > /dev/null

COUNT=$(sqlite3 "$DB" "SELECT count(*) FROM genp WHERE agrp = '$ACCESS_GROUP';")
echo "$COUNT"
