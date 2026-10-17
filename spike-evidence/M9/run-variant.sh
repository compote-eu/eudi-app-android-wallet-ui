#!/usr/bin/env bash
# One variant on the simulator: install the Debug app in DerivedData, run the full pres-cross-device flow (script `slow`,
# every state drawn) with Maestro (shots per state) and a video, then the request step alone for the sheet/page shot.
# Usage: run-variant.sh <evidence-subdir>   (uses spike-evidence/M9/<subdir>/)
set -uo pipefail
N=$1; source /Users/roman/code/eudi-kmp-spike/spike-evidence/M9/drill-helpers.sh
mkdir -p $EV/shots $EV/logs
install_app
xcrun simctl ui $U appearance light
launch $EV/logs/flow.log --ux-spike slow; sleep 4; unlock
rec_start flow
$MA --udid $U test --test-output-dir $EV/shots -e LINK="$LINK" $M/m5b-happy.yaml > $EV/logs/maestro-happy.txt 2>&1; echo "happy rc=$?"; tail -3 $EV/logs/maestro-happy.txt
rec_stop flow
launch $EV/logs/request.log --ux-spike slow; sleep 4; unlock
mflow $M/m5b-open-wait.yaml -e STATE=request; sleep 2
shot request; hier request
xcrun simctl terminate $U $B 2>/dev/null; true
