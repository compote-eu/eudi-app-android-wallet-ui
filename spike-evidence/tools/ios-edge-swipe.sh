#!/usr/bin/env bash
# iOS interactive-pop edge swipe on the simulator via a standalone XCUITest runner (XCUICoordinate press+drag from x=0).
# Usage: ios-edge-swipe.sh [-u UDID] [-b BUNDLE_ID] [-e END_X 0..1] [-y Y 0..1] [-h HOLD_SECONDS] [-t TAP_ID]
# -t: tap the element with that accessibility id inside the same XCUITest, then swipe at once (for states that last about a second).
# Prereqs: Xcode, xcodegen (brew install xcodegen), booted simulator, app installed and running. Does not touch the repo.
set -euo pipefail
U=3C45E6C6-D0FD-4112-B3BA-CE9B9BDE60DA; B=eu.compote.euidi.dev; DX=0.7; Y=0.5; HOLD=0.05; TAP=-
while getopts u:b:e:y:h:t: o; do case $o in u) U=$OPTARG;; b) B=$OPTARG;; e) DX=$OPTARG;; y) Y=$OPTARG;; h) HOLD=$OPTARG;; t) TAP=$OPTARG;; *) exit 2;; esac; done
W=${EDGE_SWIPE_HOME:-/tmp/ios-edge-swipe-runner}
if [ ! -d "$W/dd/Build/Products" ]; then
  mkdir -p "$W/Tests"
  cat > "$W/project.yml" <<'YML'
name: EdgeSwipe
options: {bundleIdPrefix: eu.compote.tools}
targets:
  EdgeSwipeUITests:
    type: bundle.ui-testing
    platform: iOS
    deploymentTarget: "17.0"
    sources: [Tests]
    settings:
      base:
        GENERATE_INFOPLIST_FILE: YES
        PRODUCT_BUNDLE_IDENTIFIER: eu.compote.tools.EdgeSwipeUITests
        CODE_SIGNING_ALLOWED: NO
YML
  cat > "$W/Tests/EdgeSwipe.swift" <<'SWIFT'
import XCTest
final class EdgeSwipe: XCTestCase {
    func testSwipe() throws {
        // params file: "bundleId dxEnd(0..1) y(0..1) pressSeconds" ; defaults below
        var bid = "eu.compote.euidi.dev"; var dx = 0.7; var y = 0.5; var hold = 0.05; var tapId = "-"
        if let s = try? String(contentsOfFile: "/tmp/edge-swipe.params", encoding: .utf8) {
            let p = s.split(whereSeparator: { $0 == " " || $0 == "\n" }).map(String.init)
            if p.count > 0 { bid = p[0] }
            if p.count > 1 { dx = Double(p[1]) ?? dx }
            if p.count > 2 { y = Double(p[2]) ?? y }
            if p.count > 3 { hold = Double(p[3]) ?? hold }
            if p.count > 4 { tapId = p[4] }
        }
        let app = XCUIApplication(bundleIdentifier: bid)
        app.activate()
        if tapId != "-" { app.descendants(matching: .any).matching(identifier: tapId).firstMatch.tap() }
        let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: y))
        let end = app.coordinate(withNormalizedOffset: CGVector(dx: dx, dy: y))
        start.press(forDuration: hold, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0)
    }
}
SWIFT
  (cd "$W" && xcodegen generate >/dev/null && xcodebuild build-for-testing -project EdgeSwipe.xcodeproj -scheme EdgeSwipeUITests -destination "id=$U" -derivedDataPath "$W/dd" >"$W/build.log" 2>&1) || { echo "runner build failed, see $W/build.log" >&2; exit 1; }
fi
echo "$B $DX $Y $HOLD $TAP" > /tmp/edge-swipe.params
cd "$W"
xcodebuild test-without-building -project EdgeSwipe.xcodeproj -scheme EdgeSwipeUITests -destination "id=$U" -derivedDataPath "$W/dd" >"$W/run.log" 2>&1 \
  && echo "swipe done" || { echo "swipe failed, see $W/run.log" >&2; exit 1; }
