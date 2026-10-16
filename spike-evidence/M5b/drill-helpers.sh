# helpers for M5b drills (source me)
U=3C45E6C6-D0FD-4112-B3BA-CE9B9BDE60DA; B=eu.compote.euidi.dev; R=/Users/roman/code/eudi-kmp-spike; EV=$R/spike-evidence/M5b; M=$R/.maestro/ux-spike
LINK="openid4vp://?client_id=spike&request_uri=https://example.invalid/r"
MA=~/.maestro/bin/maestro
launch() { xcrun simctl terminate $U $B 2>/dev/null; sleep 1; xcrun simctl launch --terminate-running-process --stdout=$1 --stderr=$1 $U $B "${@:2}"; }
shot() { xcrun simctl io $U screenshot "$EV/$1.png" >/dev/null 2>&1 && echo "shot $1"; }
mflow() { local f=$1; shift; $MA --udid $U test -e LINK="$LINK" "$@" $M/$f 2>&1 | tail -6; }
# raw Maestro output goes to ~/.maestro/tests; takeScreenshot pngs were copied to shots/
