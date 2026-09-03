# PKIXBridge — vendored, not ours

Copied from
[`eudi-lib-kmp-etsi-1196x2`](https://github.com/eu-digital-identity-wallet/eudi-lib-kmp-etsi-1196x2)
at tag **`v0.4.0-alpha.1`**, path `ios/cinterop/Sources/PKIXBridge`. Apache-2.0, same as upstream.

**One local change:** the `PKIXBridgeTests` target was removed from `Package.swift`. Upstream's manifest
declares it at `Tests/PKIXBridgeTests`, which is not vendored, and SPM refuses to resolve a manifest
naming a path that does not exist (`invalid custom path 'Tests/PKIXBridgeTests'`). The Swift sources
themselves are untouched.

**Why it is here rather than an SPM dependency.** The ETSI consultation library reaches iOS certificate
path validation through **cinterop**: the published klib records the Swift symbols
(`PKIXValidator`, `PKIXConfiguration`, `PKIXCertificateInspector`) but carries no implementation, so the
consuming Xcode target has to supply them — the same shape as `-lsqlite3` for multipaz's SQLite. It
cannot be an ordinary SPM dependency because upstream's `Package.swift` for it sits in a
*subdirectory* (`ios/cinterop/`), which a remote SPM dependency cannot address. Upstream's `-SPM` tag
publishes the whole Kotlin library as a binary xcframework instead, which is for Swift-only consumers
and is no use when the klib already arrives through Gradle.

**The module name is load-bearing.** The cinterop `.def` says `modules = PKIXBridge`, and the mangled
symbols are `_TtC10PKIXBridge…`, so these files must compile as a Swift module called exactly
`PKIXBridge`. Compiling them into the app target instead would not match.

**Two consumers, one copy of the sources.** Xcode builds this as an SPM package for the app *and* for
the document-provider extension (both declare it in `iosApp/project.yml`). Kotlin/Native **test**
binaries cannot borrow an Xcode target, so `shared-logic/build.gradle.kts` compiles the same files
into `libPKIXBridge.a` with `swiftc` and puts them on the test linker's path — see
`registerPkixBridgeBuild`. Both paths must produce a Swift module named exactly `PKIXBridge`.

**Keep in step with the klib version.** If `eudiLibKmpEtsi1196x2` moves in `libs.versions.toml`, re-copy
from the matching tag.
