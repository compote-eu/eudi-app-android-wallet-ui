# helpers for the D8 attempt-2 drills (source me; set N=<1|2|3|4|5-unseen|baseline> first). sk locale, Light.
# Based on spike-evidence/D8/tools/drill-helpers.sh; only the evidence directory differs.
U=3C45E6C6-D0FD-4112-B3BA-CE9B9BDE60DA; B=eu.compote.euidi.dev; R=/Users/roman/code/eudi-kmp-spike
EV=$R/spike-evidence/M9/$N; M=$R/.maestro/ux-spike; T=$R/spike-evidence/M9/tools
LINK="openid4vp://?client_id=spike&request_uri=https://example.invalid/r"
MA=~/.maestro/bin/maestro
mkdir -p $EV/shots $EV/logs
install_app() { xcrun simctl install $U $R/DerivedData/Build/Products/Debug-iphonesimulator/EudiWallet.app && echo "installed $(git -C $R rev-parse --short HEAD) ($(git -C $R rev-parse --abbrev-ref HEAD))"; }
launch() { xcrun simctl terminate $U $B 2>/dev/null; sleep 1; xcrun simctl launch --terminate-running-process --stdout=$1 --stderr=$1 $U $B -AppleLanguages "(sk)" -AppleLocale sk_SK "${@:2}"; }
unlock() { $MA --udid $U test $M/m4-unlock-to-home.yaml 2>&1 | tail -3; }
shot() { xcrun simctl io $U screenshot "$EV/shots/$1.png" >/dev/null 2>&1 && echo "shot $1"; }
mflow() { local f=$1; shift; $MA --udid $U test -e LINK="$LINK" "$@" $f 2>&1 | tail -8; }
hier() { $MA --udid $U hierarchy > "$EV/logs/$1.json" 2>/dev/null; echo "hierarchy $1"; }
rec_start() { xcrun simctl io $U recordVideo --codec=h264 --force "$EV/$1.mp4" >/dev/null 2>&1 & REC=$!; sleep 2; }
rec_stop() { kill -INT $REC; wait $REC 2>/dev/null; echo "video $1"; }
stop_app() { xcrun simctl terminate $U $B 2>/dev/null; true; }
