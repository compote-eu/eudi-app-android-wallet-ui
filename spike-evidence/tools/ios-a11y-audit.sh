#!/usr/bin/env bash
# Scripted Accessibility Inspector audit on the simulator (M7): a standalone XCUITest runner attaches to the running app,
# runs XCUIApplication.performAccessibilityAudit (the Inspector's audit, Xcode 15+) on the current screen and writes the
# issues plus the element tree (debugDescription: types, identifiers, labels, traits as XCUI types) to OUT.
# Usage: ios-a11y-audit.sh -o OUT [-l LABEL] [-u UDID] [-b BUNDLE_ID]
# Prereqs: Xcode, xcodegen, booted simulator, app running on the screen to audit. Does not touch the repo.
set -euo pipefail
U=3C45E6C6-D0FD-4112-B3BA-CE9B9BDE60DA; B=eu.compote.euidi.dev; OUT=""; LABEL=screen
while getopts u:b:o:l: o; do case $o in u) U=$OPTARG;; b) B=$OPTARG;; o) OUT=$OPTARG;; l) LABEL=$OPTARG;; *) exit 2;; esac; done
[ -n "$OUT" ] || { echo "-o OUT is required" >&2; exit 2; }
W=${A11Y_AUDIT_HOME:-/tmp/ios-a11y-audit-runner}
if [ ! -d "$W/dd/Build/Products" ]; then
  mkdir -p "$W/Tests"
  cat > "$W/project.yml" <<'YML'
name: A11yAudit
options: {bundleIdPrefix: eu.compote.tools}
targets:
  A11yAuditUITests:
    type: bundle.ui-testing
    platform: iOS
    deploymentTarget: "17.0"
    sources: [Tests]
    settings:
      base:
        GENERATE_INFOPLIST_FILE: YES
        PRODUCT_BUNDLE_IDENTIFIER: eu.compote.tools.A11yAuditUITests
        CODE_SIGNING_ALLOWED: NO
YML
  cat > "$W/Tests/A11yAudit.swift" <<SWIFT
import XCTest
final class A11yAudit: XCTestCase {
    func testAudit() throws {
        let p = try String(contentsOfFile: "$W/params", encoding: .utf8).split(separator: "\n").map(String.init)
        let app = XCUIApplication(bundleIdentifier: p[0])
        app.activate()
        var lines = ["# audit: \(p[2])", "## issues"]
        var n = 0
        try app.performAccessibilityAudit(for: .all) { issue in
            n += 1
            lines.append("ISSUE type=\(issue.auditType.rawValue) \(issue.compactDescription) | \(issue.detailedDescription) | element: \(issue.element.map { "\(\$0.elementType.rawValue) id='\(\$0.identifier)' label='\(\$0.label)'" } ?? "-")")
            return true
        }
        lines.append("## issue count: \(n)")
        lines.append("## element tree")
        lines.append(app.debugDescription)
        try lines.joined(separator: "\n").write(toFile: p[1], atomically: true, encoding: .utf8)
    }
}
SWIFT
  (cd "$W" && xcodegen generate >/dev/null && xcodebuild build-for-testing -project A11yAudit.xcodeproj -scheme A11yAuditUITests -destination "id=$U" -derivedDataPath "$W/dd" >"$W/build.log" 2>&1) || { echo "runner build failed, see $W/build.log" >&2; exit 1; }
fi
printf '%s\n%s\n%s\n' "$B" "$OUT" "$LABEL" > "$W/params"
cd "$W"
xcodebuild test-without-building -project A11yAudit.xcodeproj -scheme A11yAuditUITests -destination "id=$U" -derivedDataPath "$W/dd" >"$W/run.log" 2>&1 \
  && echo "audit written: $OUT ($(grep -c '^ISSUE' "$OUT") issues)" || { echo "audit failed, see $W/run.log" >&2; exit 1; }
