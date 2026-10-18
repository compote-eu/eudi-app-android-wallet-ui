// swift-tools-version: 5.9
/*
 * Copyright (c) 2026 European Commission
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the European
 * Commission - subsequent versions of the EUPL (the "Licence"); You may not use this work
 * except in compliance with the Licence.
 *
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the Licence for the specific language
 * governing permissions and limitations under the Licence.
 */

// PKIXBridge as the ETSI library publishes it: a prebuilt static xcframework attached to each
// release. See README.md for why the app has to supply it at all.
//
// ⚠️ This file is also read by :shared-logic's build, which downloads the same zip for the
// Kotlin/Native test binaries. The version in the URL must equal `eudiLibKmpEtsi1196x2Ios` in
// gradle/libs.versions.toml, and that build fails when it does not. The checksum is the zip's SHA-256
// (`swift package compute-checksum`, or the digest GitHub lists for the asset); both sides check it.
import PackageDescription

let package = Package(
    name: "PKIXBridge",
    platforms: [
        .iOS(.v13),
    ],
    products: [
        .library(name: "PKIXBridge", targets: ["PKIXBridge"]),
    ],
    targets: [
        .binaryTarget(
            name: "PKIXBridge",
            url: "https://github.com/eu-digital-identity-wallet/eudi-lib-kmp-etsi-1196x2/releases/download/v0.4.0-alpha.4/PKIXBridge.xcframework.zip",
            checksum: "d3dadeefc8ab539c7eadcd5892cef623cf72392b676e830a8595edae21393fbb"
        ),
    ]
)
