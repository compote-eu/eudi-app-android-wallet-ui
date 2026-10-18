## ios-edge-swipe.sh

Performs an iOS interactive-pop edge swipe on the simulator. It is a generated XCUITest runner that targets the app by
bundle id and calls `XCUICoordinate(0, y).press(forDuration: 0.05, thenDragTo: (0.7, y), withVelocity: .slow)`.

    spike-evidence/tools/ios-edge-swipe.sh [-u UDID] [-b BUNDLE_ID] [-e END_X] [-y Y] [-h HOLD_S]

Defaults: iPhone 17 UDID, `eu.compote.euidi.dev`, end x 0.7, y 0.5.

- Prerequisites: Xcode, `xcodegen` (brew), booted simulator, app running in the foreground on a pushed screen.
- The first call builds the runner into `/tmp/ios-edge-swipe-runner` (about 1 min). Later calls take about 5 s.
  Set `EDGE_SWIPE_HOME` to move the cache. Nothing is written into the repo.
- Reliability: 5 of 5 pops (menu back to Home), screenshots in `evidence/tryN-{before,after}.png`.
- Not needed: idb. Maestro `swipe` (Q-006) does not trigger back; XCUITest synthesizes the touch at the true screen edge.
