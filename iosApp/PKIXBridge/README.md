# PKIXBridge — upstream's binary, not ours

A local Swift package whose only target is the prebuilt `PKIXBridge.xcframework` that
[`eudi-lib-kmp-etsi-1196x2`](https://github.com/eu-digital-identity-wallet/eudi-lib-kmp-etsi-1196x2)
attaches to each release (since `v0.4.0-alpha.4`). `Package.swift` names it by URL and SHA-256; SwiftPM
downloads it and refuses a zip whose checksum differs. Nothing here is compiled.

**Why the app has to supply it.** The ETSI consultation library reaches iOS certificate path validation
through **cinterop**: the published klib records the Swift symbols (`PKIXValidator`,
`PKIXConfiguration`, `PKIXCertificateInspector`) but carries no implementation, so the final link has to
supply them — the same shape as `-lsqlite3` for multipaz's SQLite. Upstream documents this in
[`docs/iOS-PKIXBridge.md`](https://github.com/eu-digital-identity-wallet/eudi-lib-kmp-etsi-1196x2/blob/main/docs/iOS-PKIXBridge.md).
Until alpha.4 the only way to get it was to copy the Swift sources from the tag, which is what this
directory held; a copy that drifted from the klib version linked silently with the old behaviour.

**Why a local package rather than a remote one.** Upstream publishes the framework as a release asset,
not as a package a remote SwiftPM dependency could name. A local manifest with a `binaryTarget(url:)` is
the smallest thing Xcode can consume, and both targets that need it (the app and the document-provider
extension) declare it in `iosApp/project.yml`. The framework is a static archive, so each links its own
copy. Xcode still copies a `PKIXBridge.framework` into the app's `Frameworks/`, with the archive
replaced by an empty stub — its standard handling of a static framework from a package; nothing loads it.

**Two consumers, one zip.** Kotlin/Native **test** binaries have no Xcode target to borrow from, so
`shared-logic/build.gradle.kts` reads the URL and checksum from this `Package.swift`, downloads the
same zip, checks the same checksum, and links the matching slice — see `downloadPkixBridge`.

**Moving it.** Change `eudiLibKmpEtsi1196x2Ios` in `gradle/libs.versions.toml`, then the version and
checksum here. The Gradle build fails if the URL's version and the catalogue disagree, so the klib and
the binary cannot drift apart again.

**Revocation is switched off by the caller.** Since `v0.4.0-alpha.2` `PKIXConfiguration()` enables
revocation checking by default. `IosEtsiTrust` passes `PKIXConfiguration(isRevocationEnabled = false)`
explicitly — Android's flavours call `relaxPkixRevocation()` — so the setting does not depend on the
default.
