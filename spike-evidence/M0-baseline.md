# M0 baseline (untouched branch)
SHA: b23f6a1f5eec29158923da1ad62bc31f4c0c8b2d (spike/ux-runtime)

Tools: openjdk version "21.0.11" 2026-04-21; Welcome to Gradle 9.8.0!
Gradle 9.8.0; Android platform android-37.0; build-tools 37.0.0; platform-tools (latest); Maestro 2.11.0; SDK at /opt/homebrew/share/android-commandlinetools (local.properties sdk.dir, git-ignored). Xcode: NOT installed (CLT only).

| Command | Wall | Result |
|---|---|---|
| ./gradlew detekt ktlintCheck --continue | 2m51s | PASS |
| ./gradlew test :shared-ui:testAndroidHostTest :shared-logic:testAndroidHostTest :androidApp:assembleDevDebug --continue | 5m53s | PASS |
| ./gradlew :shared-logic:iosSimulatorArm64Test :shared-ui:iosSimulatorArm64Test --continue | 1m29s | FAIL (no Xcode): linkDebugTestIosSimulatorArm64 fails in both modules |

## Per-task tests (tests / failures / errors / skipped)
Parsed from */build/test-results/**/*.xml. No task with tests executed 0.
| Task | Tests | Fail | Err | Skip |
|---|---|---|---|---|
| shared-ui:testAndroidHostTest | 560 | 0 | 0 | 0 |
| shared-logic:testAndroidHostTest | 166 | 0 | 0 | 0 |
| authentication-logic:test{Dev,Demo}DebugUnitTest | 45 each | 0 | 0 | 0 |
| business-logic | 80 each | 0 | 0 | 0 |
| common-feature | 139 each | 0 | 0 | 0 |
| core-logic | 176 each | 0 | 0 | 0 |
| dashboard-feature | 255 each | 0 | 0 | 0 |
| issuance-feature | 56 each | 0 | 0 | 0 |
| network-logic | 1 each | 0 | 0 | 0 |
| presentation-feature | 54 each | 0 | 0 | 0 |
| proximity-feature | 62 each | 0 | 0 | 0 |
| startup-feature | 9 each | 0 | 0 | 0 |
| ui-logic | 20 each | 0 | 0 | 0 |
| shared-logic/shared-ui iosSimulatorArm64Test | not run (link failed, no results) | | | |

## Named failing tests
None. (Baseline exemptions: none.)

## Xcode-missing error excerpt
Execution failed for task ':shared-logic:linkDebugTestIosSimulatorArm64' ... Compilation finished with errors
org.jetbrains.kotlin.konan.MissingXcodeException: An error occurred during an xcrun execution. Make sure that Xcode and its command line tools are properly installed.
Failed command: /usr/bin/xcrun xcodebuild -version
(same for :shared-ui:linkDebugTestIosSimulatorArm64)

## Notes
- compileSdk=37 resolves to sdkmanager package platforms;android-37.0 (no "android-37" id exists).
- The demo-flavor iOS gate and generateIosProject/xcodebuild gate were not run (need Xcode).

## iOS baseline
- Xcode 27.1 (27A9275). Runtime installed via `xcodebuild -downloadPlatform iOS`: iOS 27.1 (24A94425); iOS 27.0 (24A434) also present. No sudo needed.
- Simulator: "iPhone 17" UDID 3C45E6C6-D0FD-4112-B3BA-CE9B9BDE60DA (iOS 27.0 runtime), booted.
- Build with Xcode 27.1 SDK succeeds (app iOS 17 / extension 26.2 targets OK).

| Step | Result | Wall time |
|---|---|---|
| a. iosSimulatorArm64Test (default flavor) | BUILD SUCCESSFUL. shared-logic 832 tests (88 suites), shared-ui 646 tests (71 suites); 0 failures/errors/skipped | 4m21s (cold) |
| b. same, -PappFlavor=demo | BUILD SUCCESSFUL. Identical counts: 832 / 646, 0 failures/errors/skipped | 40s |
| c. generateIosProject (needed `brew install xcodegen`, 2.46.0) | OK | 2s |
| c. resolvePackageDependencies | OK | <5s |
| c. Debug simulator build | SUCCEEDED, only with `-skipMacroValidation -skipPackagePluginValidation` (SwiftCopyableMacro "CopyablePlugin" must be enabled otherwise -> first attempt failed) | 93s |
| d. Release simulator build (derivedData ../DerivedData-Release) | SUCCEEDED, same flags | 377s |
| e. Install + launch Debug (eu.compote.euidi.dev) | Running (pid alive after 10s); welcome screen shown, screenshot spike-evidence/M0-baseline-launch.png. `-AppleLanguages "(sk)"` shown in English (app only has en.lproj) | - |

Failing tests: none (test XMLs parsed from build/test-results/iosSimulatorArm64Test only).

### Release baseline (for release-isolation check)
- Framework: DerivedData-Release/Build/Products/Release-iphonesimulator/SharedKit.framework
- `nm -gU .../SharedKit | wc -l` = 114164
- EudiWallet.app top level: compose-resources/ (contains composeResources/eu.europa.ec.shared.resources/{drawable,font,values}), Extensions/EudiWalletDocumentProvider.appex, en.lproj, Frameworks, Assets.car, plus SPM resource bundles (RQESLib, EudiRQESUi, swift-crypto*, SwiftyJSON, Swinject).
- Untracked build outputs: DerivedData/, DerivedData-Release/ (not committed).
