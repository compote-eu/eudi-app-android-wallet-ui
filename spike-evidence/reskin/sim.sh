# Reskin probe simulator helpers (source me). Usage: shots <label>  -> spike-evidence/reskin/shots/<label>-{home,request,request-untrusted}.png
U=3C45E6C6-D0FD-4112-B3BA-CE9B9BDE60DA; B=eu.compote.euidi.dev; R=/Users/roman/code/eudi-kmp-reskin
EV=$R/spike-evidence/reskin; M=$R/.maestro/ux-spike; MA=~/.maestro/bin/maestro
LINK="openid4vp://?client_id=reskin&request_uri=https://example.invalid/r"
install_app() { xcrun simctl install $U $R/DerivedData/Build/Products/Debug-iphonesimulator/EudiWallet.app && echo "installed $(git -C $R rev-parse --short HEAD)"; }
launch() { xcrun simctl terminate $U $B 2>/dev/null; sleep 1; xcrun simctl launch --terminate-running-process --stdout=$1 --stderr=$1 $U $B "${@:2}"; }
unlock() { $MA --udid $U test $M/m4-unlock-to-home.yaml 2>&1 | tail -2; }
shot() { xcrun simctl io $U screenshot "$EV/shots/$1.png" >/dev/null 2>&1 && echo "shot $1"; }
openlink() { $MA --udid $U test -e LINK="$LINK" $EV/open-link.yaml 2>&1 | tail -2; }
shots() {
  local l=$1
  launch $EV/logs/$l-home.log --ux-spike reskin-request; sleep 5; unlock; sleep 3; shot $l-home
  openlink; sleep 4; shot $l-request
  launch $EV/logs/$l-untrusted.log --ux-spike reskin-request-untrusted; sleep 5; unlock; sleep 2; openlink; sleep 4; shot $l-request-untrusted
}
