#!/usr/bin/env bash
# M5d: screenshot one probe page. Usage: shoot.sh <script, e.g. gallery-home-offline> <out name>  (simulator iPhone 17, app installed)
U=3C45E6C6-D0FD-4112-B3BA-CE9B9BDE60DA; B=eu.compote.euidi.dev; D=$(dirname "$0")
xcrun simctl terminate $U $B 2>/dev/null; sleep 1
xcrun simctl launch --terminate-running-process $U $B --ux-spike "$1" >/dev/null; sleep 3
xcrun simctl openurl $U "openid4vp://?client_id=spike&request_uri=https://example.invalid/r"; sleep 3
~/.maestro/bin/maestro --udid $U test $D/pin.yaml >/dev/null 2>&1; sleep 3
xcrun simctl io $U screenshot "$D/../$2.png"
