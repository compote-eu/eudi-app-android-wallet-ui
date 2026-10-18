# helpers for the M8 D8 drills (source me; set N=<1..4> first). sk locale, Light appearance. Based on M6b/drill-helpers.sh.
U=3C45E6C6-D0FD-4112-B3BA-CE9B9BDE60DA; B=eu.compote.euidi.dev; R=/Users/roman/code/eudi-kmp-spike; EV=$R/spike-evidence/D8/$N; M=$R/.maestro/ux-spike
LINK="openid4vp://?client_id=spike&request_uri=https://example.invalid/r"
MA=~/.maestro/bin/maestro
mkdir -p $EV/shots $EV/logs
install_app() { xcrun simctl install $U $R/DerivedData/Build/Products/Debug-iphonesimulator/EudiWallet.app && echo "installed $(git -C $R rev-parse --short HEAD)"; }
launch() { xcrun simctl terminate $U $B 2>/dev/null; sleep 1; xcrun simctl launch --terminate-running-process --stdout=$1 --stderr=$1 $U $B -AppleLanguages "(sk)" -AppleLocale sk_SK "${@:2}"; }
shot() { xcrun simctl io $U screenshot "$EV/shots/$1.png" >/dev/null 2>&1 && echo "shot $1"; }
mflow() { local f=$1; shift; $MA --udid $U test -e LINK="$LINK" "$@" $f 2>&1 | tail -6; }
rec_start() { xcrun simctl io $U recordVideo --codec=h264 --force "$EV/$1.mp4" >/dev/null 2>&1 & REC=$!; sleep 2; }
rec_stop() { kill -INT $REC; wait $REC 2>/dev/null; echo "video $1"; }
