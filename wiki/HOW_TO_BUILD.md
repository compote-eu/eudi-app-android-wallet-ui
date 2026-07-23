# Building The Reference Apps To Interact With Issuing And Verifying Services

## Table of contents

* [Overview](#overview)
* [Prerequisites](#prerequisites)
* [Build variants](#build-variants)
* [Build commands](#build-commands)
* [Running with remote services](#running-with-remote-services)
* [Running with local services](#running-with-local-services)
* [The local flavor](#the-local-flavor)
* [Why 10.0.2.2?](#why-10022)
* [How to work with self-signed certificates](#how-to-work-with-self-signed-certificates)
* [Production note](#production-note)

## Overview

This guide helps developers build the Android Wallet application and connect it to either the
hosted reference services or locally running issuer/verifier services.

For production deployment, use this file only as a build reference. Production teams must also
follow [the go-live guide](GO_LIVE.md).

## Prerequisites

Install or prepare:

* Android Studio, preferably the current stable version.
* Android SDK Platform 37, because the project currently compiles with `compileSdk = 37`.
* Android SDK Build Tools compatible with Android Gradle Plugin used in `gradle/libs.versions.toml`.
* JDK 17. The build logic configures Java/Kotlin target 17.
* Git.
* A physical Android device or emulator running Android 10/API 29 or higher.

You do not need to install Gradle separately. Use the checked-in Gradle wrapper:

```powershell
.\gradlew.bat --version
```

On macOS/Linux:

```bash
./gradlew --version
```

Clone and open the project:

```bash
git clone https://github.com/eu-digital-identity-wallet/eudi-app-android-wallet-ui.git
cd eudi-app-android-wallet-ui
```

Then open the folder in Android Studio.

## Build variants

The application currently has three product flavors:

* `dev`: development/reference service configuration. The application ID has the `.dev` suffix.
* `demo`: demo/reference service configuration.
* `local`: services running on your own machine. The application ID has the `.local` suffix, so it
  installs side-by-side with `dev`/`demo`, and its launcher icon carries a red `LOCAL` badge. See
  [The local flavor](#the-local-flavor) for details.

The application has two build types:

* `debug`: debuggable, no minification, verbose network logging.
* `release`: not debuggable, minified, network logging disabled, signed with the configured release
  signing config.

The resulting app build variants are:

* `devDebug`
* `devRelease`
* `demoDebug`
* `demoRelease`
* `localDebug`
* `localRelease`

To select a variant in Android Studio, open **Build > Select Build Variant** and choose the active
variant for the `:app` module. Android Studio will apply the matching variants to the dependent
modules.

## Build commands

From the project root on Windows:

```powershell
.\gradlew.bat :app:assembleDevDebug
.\gradlew.bat :app:assembleDemoDebug
```

Release builds:

```powershell
.\gradlew.bat :app:assembleDevRelease
.\gradlew.bat :app:assembleDemoRelease
```

On macOS/Linux, replace `.\gradlew.bat` with `./gradlew`.

Run tests and checks:

```powershell
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat dependencyCheckAnalyze
```

APK outputs are created under:

```text
app/build/outputs/apk/<flavor>/<build-type>/
```

Examples:

```text
app/build/outputs/apk/dev/debug/app-dev-debug.apk
app/build/outputs/apk/demo/release/app-demo-release.apk
```

Release signing is configured in `app/build.gradle.kts`. The current reference setup expects a
keystore file at the project root named `sign` and reads signing values from `local.properties` or
environment variables. In the current Gradle file, `storePassword` is read from the same
`androidKeyPassword` / `ANDROID_KEY_PASSWORD` value used for `keyPassword`; if your keystore uses a
separate store password, update the Gradle signing config before building release artifacts. For
production signing, see [GO_LIVE.md](GO_LIVE.md#release-signing).

## Running with remote services

The app is configured through `WalletCoreConfigImpl.kt` files in the `core-logic` module:

* `core-logic/src/dev/java/eu/europa/ec/corelogic/config/WalletCoreConfigImpl.kt`
* `core-logic/src/demo/java/eu/europa/ec/corelogic/config/WalletCoreConfigImpl.kt`

The current `dev` flavor uses reference development services similar to:

```kotlin
issuerUrl = "https://ec.dev.issuer.eudiw.dev"
issuerUrl = "https://dev.issuer-backend.eudiw.dev"

override val walletProviderHost: String
    get() = "https://dev.wallet-provider.eudiw.dev"
```

The current `demo` flavor uses reference demo services similar to:

```kotlin
issuerUrl = "https://issuer.eudiw.dev"
issuerUrl = "https://issuer-backend.eudiw.dev"

override val walletProviderHost: String
    get() = "https://wallet-provider.eudiw.dev"
```

These values are suitable for reference/demo testing only. They are not production values.

To run the app against the hosted services:

1. Select `devDebug` or `demoDebug`.
2. Connect a device or start an emulator.
3. Run the `:app` configuration from Android Studio, or install the APK with `adb install`.
4. Follow the user flows in the root `README.md`.

## Running with local services

To test against services running on your own workstation, start the required services first:

* [Issuer](https://github.com/eu-digital-identity-wallet/eudi-srv-web-issuing-eudiw-py)
* [Web Verifier UI](https://github.com/eu-digital-identity-wallet/eudi-web-verifier)
* [Web Verifier Endpoint](https://github.com/eu-digital-identity-wallet/eudi-srv-web-verifier-endpoint-23220-4-kt)

Then update the selected flavor's `WalletCoreConfigImpl.kt`.

For an emulator, use `10.0.2.2` to reach services running on the host machine. Include the scheme
and port used by the local service:

```kotlin
override val issuersConfig: List<VciConfig>
    get() = listOf(
        VciConfig(
            issuerUrl = "https://10.0.2.2:8443",
            config = OpenId4VciManager.Config.Builder()
                .withClientAuthenticationType(
                    OpenId4VciManager.ClientAuthenticationType.AttestationBased(
                        clientId = "eudiw-abca"
                    )
                )
                .withAuthFlowRedirectionURI(BuildConfig.ISSUE_AUTHORIZATION_DEEPLINK)
                .withParUsage(OpenId4VciManager.Config.ParUsage.IF_SUPPORTED)
                .withDPopConfig(DPopConfig.Default)
                .build(),
            order = 0
        )
    )

override val walletProviderHost: String
    get() = "https://10.0.2.2:8445"
```

For a physical device, use the host computer's LAN IP address instead:

```kotlin
issuerUrl = "https://192.168.1.50:8443"
```

The issuer/verifier metadata, redirect URIs, and wallet deep links must match the app's configured
schemes and hosts. See [CONFIGURATION.md](CONFIGURATION.md) for those values.

## The local flavor

Instead of editing the `dev`/`demo` config by hand as described above, use the dedicated **`local`**
flavor. It is pre-wired to talk to services on your workstation, installs alongside `dev`/`demo`
(application ID `eu.europa.ec.euidi.local`), and shows a red `LOCAL` badge on its launcher icon so
you can tell the three apps apart.

Select the **`localDebug`** variant in **Build > Select Build Variant**, or build from the command
line:

```bash
./gradlew :app:assembleLocalDebug
```

### Configuring the host IP

The `local` flavor reads the host address from a single, per-developer property so you never edit
Kotlin when you move between networks. The value is exposed to the app as `BuildConfig.LOCAL_IP`.

Set it in `local.properties` (this file is per-machine and git-ignored):

```properties
localIp=192.168.1.50
```

Resolution order (first match wins):

1. `localIp` in `local.properties`
2. the `LOCAL_IP` environment variable
3. `10.0.2.2` (the Android emulator's alias for the host — the default when nothing is set)

For a **physical device**, set `localIp` to your workstation's LAN IP as seen by the device. For the
**emulator**, leave it unset to use `10.0.2.2` (see [Why 10.0.2.2?](#why-10022)).

### Which services point where

Only the services that are strictly required to issue a credential point at your machine by default;
the optional ones stay on the `dev` reference infrastructure. Every entry has the alternative one
comment-toggle away, in `core-logic/src/local/.../WalletCoreConfigImpl.kt` and
`business-logic/src/local/.../RQESConfigImpl.kt`:

| Service | Necessary? | Default in `local` | Port |
|---|---|---|---|
| Issuer (OpenID4VCI) | Yes | `https://${LOCAL_IP}` (dev URL commented) | `:8443` |
| Wallet Provider | Yes | `https://${LOCAL_IP}` (dev URL commented) | `:8445` |
| Trusted list (LoTE) | No | dev reference list (local URLs commented) | `:8446` |
| RQES signer | No | dev reference signer (local URL commented) | `:8449` |

The ports are a suggested scheme — change them to whatever your local stack binds to.

### Trust settings for local development

Because the local issuer is not present in the dev trusted list, the `local` flavor relaxes issuer
trust so issuance is not blocked out of the box:

* `configureIssuerTrust` uses `TrustPolicy.Action.INFORM` (not `ENFORCE`).
* `requireSignedMetadata()` is commented out (many local issuers do not serve signed metadata).

If you run your own trusted-list server that lists your local issuer, uncomment the local LoTE URLs
and switch these two back to `ENFORCE` / `requireSignedMetadata()`. Both alternatives are present as
comments at the relevant lines.

### HTTPS and the local dev CA

The `local` flavor ships its own `network-logic/src/local/res/xml/network_security_config.xml`.
Cleartext HTTP stays disabled; the config trusts **system** and **user-installed** CAs so that HTTPS
services signed by your development CA work on any LAN without a rebuild.

#### Generating the CA and server certificates

Two Gradle tasks (backed by `scripts/generate-local-dev-ca.sh`, which uses `openssl`) automate this:

```bash
# 1. Create the local dev CA (once). Bundles the CA cert at
#    network-logic/src/local/res/raw/local_dev_ca.pem; the private key stays in
#    network-logic/.local-dev-ca/ (git-ignored).
./gradlew :network-logic:generateLocalDevCa

# 2. Mint a TLS server certificate signed by that CA for your services. The host is
#    taken from -PhostIp, else the LOCAL_IP env var, else `localIp` in local.properties,
#    else 10.0.2.2. The SAN also includes localhost / 127.0.0.1 / 10.0.2.2.
./gradlew :network-logic:generateLocalServerCert -PhostIp=192.168.1.50
```

Pass `-Pforce` to `generateLocalServerCert` to overwrite the existing server cert (e.g. when you
change LAN). Regenerating the CA is separate and destructive — use `-PforceCa` on
`generateLocalDevCa` only when you deliberately want a new CA (it invalidates any copy already
installed on a device). Configure your local issuer / wallet-provider TLS with the generated
`network-logic/.local-dev-ca/server.key` and `server.crt` (or `server-fullchain.crt`). All private
keys under `network-logic/.local-dev-ca/` are git-ignored — never commit them.

You can also run the script directly: `scripts/generate-local-dev-ca.sh ca` /
`scripts/generate-local-dev-ca.sh server <host-or-ip>`.

#### Making the app trust the CA

The `local` flavor **bundles the CA in the APK by default** — its
`network_security_config.xml` trusts `@raw/local_dev_ca` (plus `system` and `user`). The cert at
`network-logic/src/local/res/raw/local_dev_ca.pem` is written by `generateLocalDevCa` and is
git-ignored, so:

> **You must run `./gradlew :network-logic:generateLocalDevCa` before building the `local` flavor.**
> A fresh checkout has no `local_dev_ca.pem`, so the build will fail to resolve `@raw/local_dev_ca`
> until you generate it. (If you prefer not to bundle a CA, remove the `@raw/local_dev_ca` trust
> anchor and install the CA on the device instead — push `network-logic/.local-dev-ca/ca.crt` and
> add it via **Settings > Security > Encryption & credentials > Install a certificate > CA
> certificate**.)

See [How to work with self-signed certificates](#how-to-work-with-self-signed-certificates) for the
general guidance this builds on.

## Why 10.0.2.2?

When using the Android emulator, `10.0.2.2` is a special alias that routes to `localhost` on your
development machine. If your issuer is running locally on your host, the emulator can access it via
`https://10.0.2.2:<port>`.

Physical devices do not understand `10.0.2.2`. Use your workstation's reachable LAN IP address or a
controlled development DNS name.

## How to work with self-signed certificates

For local development, prefer trusting a local development CA over disabling TLS validation.

Do not add a trust-all `X509TrustManager`, do not set
`HostnameVerifier { _, _ -> true }`, and do not permit cleartext traffic in production or in code
that can be accidentally built into a release artifact.

Recommended local-development approach:

1. Create or obtain a local development CA certificate.
2. Sign the local issuer/verifier/wallet-provider TLS certificates with that CA.
3. Add the CA certificate to a debug-only resource, for example:

   ```text
   network-logic/src/debug/res/raw/local_dev_ca.cer
   ```

4. Add a debug-only network security config, for example:

   ```text
   network-logic/src/debug/res/xml/network_security_config.xml
   ```

5. Configure that debug-only file to trust the local CA:

   ```xml
   <?xml version="1.0" encoding="utf-8"?>
   <network-security-config>
       <domain-config cleartextTrafficPermitted="false">
           <domain includeSubdomains="true">10.0.2.2</domain>
           <trust-anchors>
               <certificates src="@raw/local_dev_ca" />
           </trust-anchors>
       </domain-config>
       <base-config cleartextTrafficPermitted="false">
           <trust-anchors>
               <certificates src="system" />
           </trust-anchors>
       </base-config>
   </network-security-config>
   ```

6. Keep the release network security config strict. The existing production-facing base config should
   keep `cleartextTrafficPermitted="false"` and should not trust debug-only anchors.

If you need an emergency trust-all client for a short local experiment, keep it outside committed
source or behind an explicit debug-only source set that cannot be compiled into release. Remove it
before opening a pull request.

## Production note

The hosted reference values, local IP addresses, self-signed certificates, debug CAs, and demo trust
anchors described here are not production configuration.

Before launching a real wallet, create a dedicated production flavor, replace all endpoints and
trust anchors, verify signing and release checks, and follow [GO_LIVE.md](GO_LIVE.md).
