# M11 helpers (source me). emulator spike_pixel API 37, sk app locale, Light.
export PATH=/opt/homebrew/share/android-commandlinetools/platform-tools:~/.maestro/bin:$PATH
P=eu.compote.euidi.dev; R=/Users/roman/code/eudi-kmp-spike; EV=$R/spike-evidence/M11
LINK='openid4vp://?client_id=spike&request_uri=https://example.invalid/r'
setscript() { adb shell "run-as $P sh -c 'echo $1 > files/ux-spike.on'"; }
shot() { adb exec-out screencap -p > "$EV/shots/$1.png"; echo "shot $1"; }
rec_start() { adb shell "screenrecord --time-limit 180 /sdcard/$1.mp4" >/dev/null 2>&1 & REC=$!; sleep 2; }
rec_stop() { adb shell pkill -INT screenrecord; sleep 2; adb pull /sdcard/$1.mp4 "$EV/$1.mp4" >/dev/null; echo "video $1"; }
dumpui() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb exec-out cat /sdcard/ui.xml; }
state() { dumpui | grep -o 'resource-id="ux-state[^"]*"' | head -1; }
waitstate() { for i in $(seq 1 ${2:-40}); do s=$(state); [[ "$s" == *"ux-state-$1\""* ]] && { echo "at $1"; return 0; }; sleep 0.5; done; echo "TIMEOUT waiting $1 (got $s)"; return 1; }
pin() { maestro --udid emulator-5554 test $EV/pin.yaml >/dev/null 2>&1; }
cold_link() { adb shell am force-stop $P; sleep 1; adb shell am start -a android.intent.action.VIEW -d "'$LINK'" $P; }
reach_request() { adb shell am force-stop $P; setscript ${1:-slow}; adb logcat -c; adb shell am start -a android.intent.action.VIEW -d "'$LINK'" $P >/dev/null; sleep 6; pin; waitstate request 60; sleep 1; }
CONT="input tap 540 2069"; DECL="input tap 540 2221"; CONF="input tap 540 2069"
BACK="input keyevent KEYCODE_BACK"; EDGE="input swipe 2 1200 600 1200 150"
logs() { adb logcat -d -v time | grep -E "UX-SPIKE" | sed 's/I\/System.out( *[0-9]*): //'; }
# D2(a): HOME, am kill (waits until the process is cached), then relaunch from recents (APP_SWITCH, tap the card)
kill_a() { adb shell input keyevent KEYCODE_HOME; sleep 2; echo "pid before kill: $(adb shell pidof $P)"; for i in 1 2 3 4 5; do adb shell am kill $P; sleep 1; [ -z "$(adb shell pidof $P)" ] && break; done; echo "pid after am kill: '$(adb shell pidof $P)'"; }
recents_open() { adb shell input keyevent KEYCODE_APP_SWITCH; sleep 2; adb shell input tap 540 960; }
# task root = launcher intent (MAIN), then the link as onNewIntent: the recreated activity is not started by the deep link
reach_request2() { adb shell am force-stop $P; setscript ${1:-slow}; adb logcat -c; adb shell am start -n $P/eu.europa.ec.assemblylogic.ui.MainActivity >/dev/null; sleep 6; pin; sleep 3; adb shell am start -a android.intent.action.VIEW -d "'$LINK'" $P >/dev/null; waitstate request 60; sleep 1; }
# D2(b) approximation of a low-memory kill: memory-factor CRITICAL, trim COMPLETE delivered to the cached app, then SIGKILL
# from root, which is what lmkd does (no callbacks, AMS only sees the death). `cmd activity kill-all` did not reclaim the
# wallet (probed twice, see logs/d2b-probe.log), and tmpfs/hog attempts cannot run as shell. Real lmkd kill: queued.
kill_b() { adb shell input keyevent KEYCODE_HOME; sleep 2; pid=$(adb shell pidof $P); echo "pid before: $pid"; adb shell cmd activity memory-factor set CRITICAL; adb shell cmd activity memory-factor show;
  adb shell am send-trim-memory $P COMPLETE; echo "send-trim-memory rc=$?"; sleep 1; adb shell kill -9 $pid; sleep 1; echo "pid after SIGKILL: '$(adb shell pidof $P)'"; adb shell cmd activity memory-factor reset; }
d2b_run(){ n=$1; pre=$2; { echo "## D2b $n $(date -Iseconds)"; reach_request2; [ $n != request ] && { adb shell "$CONT"; [ $n = sending ] && waitstate verification-code 30; }; rec_start d2b-$n; [ $n != request ] && { adb shell "${pre}input keyevent KEYCODE_HOME"; sleep 0.2; }; kill_b; echo "-- relaunch (recents)"; recents_open; sleep 7; shot d2b-$n-after; echo "pid: $(adb shell pidof $P) gate=$(dumpui | grep -c pin_text_field_0) state=$(state)"; logs | grep -E "enter|restore|send#|authenticate|snapshot|hook module|start |exit" | cut -c1-200; adb logcat -d -b all | grep -E "am_kill.*$P" | tail -1; rec_stop d2b-$n; } 2>&1 | tee $EV/logs/d2b-$n.log; }
# M11c: D2 re-run after the F-D2-1 fix. $1 = root (launcher|link), $2 = request|auth|sending, $3 = kill method (a|b)
d2c_run(){ root=$1; n=$2; k=${3:-a}; { echo "## D2c root=$root state=$n kill=$k $(date -Iseconds)";
  if [ $root = launcher ]; then reach_request2; else reach_request; fi
  case $n in auth) adb shell "$CONT; input keyevent KEYCODE_HOME";; sending) adb shell "$CONT"; waitstate verification-code 30; adb shell "$CONF; input keyevent KEYCODE_HOME";; *) ;; esac
  adb logcat -c; [ $n = request ] || true
  [ $k = a ] && { [ $n = request ] && { kill_a; } || { sleep 2; echo "pid before kill: $(adb shell pidof $P)"; for i in 1 2 3 4 5; do adb shell am kill $P; sleep 1; [ -z "$(adb shell pidof $P)" ] && break; done; echo "pid after: '$(adb shell pidof $P)'"; }; } || kill_b
  echo "-- relaunch (recents)"; recents_open; sleep 7; shot d2c-$root-$n-$k; echo "pid: $(adb shell pidof $P) gate=$(dumpui | grep -c pin_text_field_0) state=$(state)"; logs | cut -c1-200; } 2>&1 | tee $EV/logs/d2c-$root-$n-$k.log; }
