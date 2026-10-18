#!/usr/bin/env bash
# M12: D2-Android am kill re-run at HEAD (T9 changed in 88a79d4ec after the M11c D2 run), both task roots, 3 hard states.
# Reuses spike-evidence/M11/helpers.sh (d2c_run); evidence goes to spike-evidence/M12/d2/.
# Tool-timing change only: the cold start before the PIN needs ~10 s on this emulator run (the M11 helpers wait 6 s, so the
# PIN flow found no PIN field and every run timed out before `request`; those void runs are kept in d2/void/). pin_wait runs
# M12/pin-strict.yaml (waits for the PIN field, enters the PIN, waits until it is gone; up to 3 tries).
source "$(dirname "$0")/../M11/helpers.sh"
EV=$R/spike-evidence/M12/d2
pin_wait() { for t in 1 2 3; do maestro --udid emulator-5554 test $R/spike-evidence/M12/pin-strict.yaml >/dev/null 2>&1 && { echo "pin done (try $t)"; return 0; }; done; echo "PIN FAILED"; }
reach_request() { adb shell am force-stop $P; setscript ${1:-slow}; adb logcat -c; adb shell am start -a android.intent.action.VIEW -d "'$LINK'" $P >/dev/null; pin_wait; waitstate request 60; sleep 1; }
reach_request2() { adb shell am force-stop $P; setscript ${1:-slow}; adb logcat -c; adb shell am start -n $P/eu.europa.ec.assemblylogic.ui.MainActivity >/dev/null; pin_wait; sleep 3; adb shell am start -a android.intent.action.VIEW -d "'$LINK'" $P >/dev/null; waitstate request 60; sleep 1; }
echo "# M12 D2 re-run, HEAD $(git -C $R rev-parse --short HEAD), APK $(ls -l $R/androidApp/build/outputs/apk/dev/debug/androidApp-dev-debug.apk | awk '{print $6,$7,$8}')"
for root in launcher link; do for st in request auth sending; do d2c_run $root $st a; done; done
