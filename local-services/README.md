# Local EU ARF services (for the `local` build flavor)

A full local EUDI issuer backend that issues PID (and other credentials) end-to-end into
the wallet's **`local`** flavor on a real device/emulator — no hosted `eudiw.dev` services
needed. Validated end-to-end: PID `mdoc`, `mdoc_deferred`, and `sd-jwt vc` onto a device.

> ⚠️ **Local development only.** Self-signed dev CA, relaxed trust, placeholder keys,
> non-production settings throughout. Never use any of it in production.

## Quick start (from a fresh clone)

```bash
cd local-services
cp .env.example .env            # optionally set LOCAL_IP (auto-detected from your route otherwise)
./bootstrap.sh                  # sets up + starts the whole stack (see "What bootstrap does")
```
Then, once:
```bash
adb push ../network-logic/.local-dev-ca/ca.crt /sdcard/Download/
# Settings > Security > Install a certificate > CA certificate > pick it   (needed for the browser step)
```
Select the **`localDebug`** variant in Android Studio (or `./gradlew :app:installLocalDebug`) and run.

Issue a credential: **Add document → PID → choose "FormEU" → fill the form → submit.**
(Pick **FormEU**, not Portugal/nodeEU — those need external IdPs.)

## Helper scripts

| Script | Purpose |
|---|---|
| `./bootstrap.sh [--skip-app]` | One-shot full setup + start (idempotent; safe to re-run) — use once per clone |
| `./start.sh [--force]` | Daily bring-up with strict preflight checks (setup complete? IP still matches?) then start |
| `./start.sh --reconfigure-ip [ip]` | Start after moving networks (delegates to `reconfigure-ip.sh`) |
| `./stop.sh [--volumes\|--reset]` | Stop services (`--volumes` wipes DB/status data; `--reset` also clears `config/`+`repos/`) |
| `./reconfigure-ip.sh [<ip>]` | Repoint the stack + app at a new LAN IP after moving networks |
| `./gen-issuer-ds.sh [--force]` | (Re)generate issuer Document-Signer + nonce/credential keys (used by bootstrap) |

Typical lifecycle: **`bootstrap.sh`** once → **`start.sh`** / **`stop.sh`** day to day →
**`reconfigure-ip.sh`** (or `start.sh --reconfigure-ip`) when you change networks.
`start.sh` is strict: it refuses to start on incomplete setup or a stale/mismatched IP and
tells you exactly what to run (`--force` bypasses the IP checks).

Gradle key/cert tasks (also invoked by bootstrap): `:network-logic:generateLocalDevCa`,
`generateLocalServerCert -PhostIp=<ip>`, `generateWalletProviderSigningKey`.

## Architecture

A [Caddy](https://caddyserver.com/) reverse proxy terminates TLS with the dev CA (which the
app bundles), fronting the services on `${LOCAL_IP}`:

```
app ─┬─ https://${LOCAL_IP}:8444  ─→ issuer-frontend   (the credential issuer: its metadata,
     │                                                  FormEU browser pages)
     ├─ https://${LOCAL_IP}:8443  ┌ /oidc/*                                        ─→ issuer-oidc
     │      (Caddy :8443 routes)  ├ /.well-known/oauth-authorization-server/oidc/* ─→ issuer-oidc
     │                            ├ /.well-known/openid-configuration/oidc/*       ─→ issuer-oidc
     │                            ├ /token_status_list/*, /identifier_list/*       ─→ statuslist
     │                            └ (everything else: /credential, /nonce, ...)    ─→ issuer
     └─ https://${LOCAL_IP}:8445  ─→ wallet-provider   (wallet + key attestation)

internal: issuer-frontend ─→ issuer (metadata, API key) ; issuer ─→ issuer-oidc (introspection),
          statuslist, issuer-db ; wallet-provider ─→ statuslist, wallet-db
```

The credential issuer identifier is the frontend, `https://${LOCAL_IP}:8444` (the app's
`issuerUrl`). Its metadata names the backend (`:8443`) for `/credential`, `/nonce` and
`/deferred_credential`, and the authorization server
`https://${LOCAL_IP}:8443/oidc/5d725b3c-6d42-448e-8bfd-1eff1fcf152d` (one per frontend id).

| Service | Image / build | Role |
|---|---|---|
| `proxy` | `caddy:2-alpine` | TLS termination + routing (`caddy/Caddyfile`) |
| `issuer` | ghcr `eudi-srv-web-issuing-eudiw-py:0.11.0` | Issuer backend (`/credential`, `/nonce`, FormEU flow) |
| `issuer-db` | `postgres:18.7-alpine` | issuer backend database (attestation status records) |
| `issuer-oidc` | ghcr `eudi-srv-issuer-oidc-py:0.10.0` | OpenID4VCI authorization server (`/oidc/*`) |
| `issuer-frontend` | ghcr `eudi-srv-web-issuing-frontend-eudiw-py:0.11.0` | Credential issuer metadata + FormEU web UI |
| `wallet-provider` | **built** (native) from `repos/eudi-srv-wallet-provider` (v0.2.1) | Wallet-instance + key attestation; needs Postgres |
| `statuslist` | ghcr `eudi-srv-statuslist-py:0.10.0` | Status-token minting, revocation, the public status lists |
| `wallet-db` | `postgres:18.7-alpine` | wallet-provider database |

Every image is pinned to a release tag (the wallet-provider to a git tag). `:latest` once moved
under this stack: the 2026-11 issuer releases changed every config file, and a `docker compose
pull` silently broke it. To move a service, read its release's config changes, update the pin in
`compose.yaml` and its template under `templates/` together, then re-run `./bootstrap.sh --skip-app`.

Optional profiles (`verifier`, `trustedlist`, `rqes`) — see the end of this file.

## What `bootstrap.sh` does

0. **Prereqs & LOCAL_IP** — checks `docker/openssl/curl/python3/git`; resolves `LOCAL_IP`
   (from `.env`, else auto-detects); syncs it into `.env` and `../local.properties`.
1. **Dev CA + TLS cert** — `generateLocalDevCa` + `generateLocalServerCert` (CA reused if present).
2. **Wallet-provider signing keystore** — `generateWalletProviderSigningKey`.
   It also generates the per-developer secrets into `.env` (random, 64 hex characters): the
   status-list API key, the issuer's session key, API keys and payload key, and the issuer
   database password. The services refuse short keys and placeholders such as `test`.
3. **Render service configs** — substitutes `__LOCAL_IP__` and the secrets in the committed
   **`templates/`** into `config/` (issuer backend, frontend, authorization server, status
   list). Deterministic — no fetch-and-patch of upstream, so no version-skew surprises.
4. **Issuer DS + global keys** — `gen-issuer-ds.sh` (dev-CA-signed).
5. **Attestation trust** — copies the wallet-provider's signing certificate (step 2) into
   the issuer's key-attestation trust anchors and the authorization server's trusted attesters.
6. **Wallet-provider source + DB schema** — clones `eudi-srv-wallet-provider` (pinned to
   `v0.2.1`) and copies `schemas/postgresql` from it.
7. **Status-list service** — renders its `config_statuslist.yaml` and generates its signing
   key (the image is pulled, pinned in `compose.yaml`).
8. **Native wallet-provider image** — builds `eudi-srv-wallet-provider:local` from the
   step-6 source with Jib, for the **host arch** (`-Djib.from.platforms`). Upstream ships
   only `linux/amd64` (emulated/slow on Apple Silicon); this is native. Skipped if the
   image already exists — force a rebuild with `FORCE_WP_BUILD=1`.
9. **Build & start** — `docker compose up -d --build --force-recreate` (so every service re-reads
   its config; volumes are kept).
10. **App** — `installLocalDebug` (unless `--skip-app`), then prints the CA-on-device reminder.

## Changing your LAN / IP

The IP is baked into `BuildConfig.LOCAL_IP`, `.env`, the backend config files, and the TLS
cert SAN. To move networks:

```bash
./reconfigure-ip.sh [<new-ip>]     # auto-detects if omitted; --skip-app to leave the app
```
The **dev CA is preserved**, so the device keeps trusting it — no CA re-install.

## Updating an existing setup (2026-11 issuer releases)

A stack bootstrapped before November 2026 needs one re-run: `./bootstrap.sh --skip-app`. It
generates the new secrets, renders the new configs, removes the files nothing reads any more
(`config/issuer-oidc/{config.json,openid-configuration.json,views.py}`,
`config/issuer/credentials_supported/`) and recreates the containers. Then:

- Reinstall the app (`./gradlew :app:installLocalDebug`): its issuer moved from `:8443` to `:8444`.
- Documents issued by the old stack refer to the old issuer; delete them in the app and issue again.
- The v0.9.0 status-list data is not reused (a new `statuslist-state` volume; the image no longer
  runs as root). Remove the old one: `docker volume rm eudi-local-services_statuslist-data`.
- `repos/eudi-srv-statuslist-py` (the old clone) is no longer used; delete it.

## Gotchas learned the hard way

- **Backend-to-backend HTTPS needs the dev CA.** `issuer` and `issuer-frontend` call other
  services over `https://${LOCAL_IP}:8443` — they mount `ca.crt` and set `REQUESTS_CA_BUNDLE`/
  `SSL_CERT_FILE`/`CURL_CA_BUNDLE`, plus `extra_hosts: ${LOCAL_IP}:host-gateway`.
- **Every service reads its config at start-up** — after editing a file under `config/` or
  `caddy/Caddyfile`, restart that service (`docker compose restart <service>`); `bootstrap.sh`
  recreates them all.
- **The issuer images run as uid 10001, not root** — bootstrap makes the bind-mounted private
  keys readable (`chmod 644`; local dev keys only), or Linux hosts fail to start the issuer.
- **Access tokens live 1 h locally** (upstream 300 s): an emulator creating a batch of 30 keys per
  credential can take more than 5 minutes between `/token` and its last `/credential`.
- **The authorization server's routes include `/oidc`** (since v0.10.0): Caddy passes the path
  through unchanged; stripping it, as before, gives 404s.
- **The proxy mounts the whole `.local-dev-ca` dir** (not individual cert files) so a
  regenerated cert propagates — single-file bind mounts go stale on Docker Desktop.
- **Postgres 18** stores data in a version subdir → mount at `/var/lib/postgresql`.
- **Chrome** uses the Android system trust store, not the app's bundled CA → install the CA on
  the device for the browser auth pages.
- In the issuer country picker choose **"FormEU"** — the form country, enabled by
  `test_features.form_countries` in the backend config (off by default since v0.10). Other
  entries route to an external OAuth provider and fail with `invalid_client`.
- **The wallet-provider is built from source**, not pulled: upstream publishes only a
  `linux/amd64` image (emulated and slow on Apple Silicon — and future macOS drops that
  emulation). Bootstrap builds a host-native image with Jib. `stop.sh --reset` clears
  `repos/` but keeps the built image, so the next bootstrap reuses it; rebuild explicitly
  with `FORCE_WP_BUILD=1 ./bootstrap.sh --skip-app`.

## Optional services (profiles)

```bash
docker compose --profile verifier up -d       # verifier API (:8447) + UI (:8448)
docker compose --profile trustedlist up -d    # trusted-list manager (:8446)
docker compose --profile rqes up -d           # TrustProvider Signer (:8449, software keys)
```
See each service's block in `compose.yaml` for its clone/config requirements.

## What's committed vs. generated

Committed: `compose.yaml`, `caddy/Caddyfile`, `.env.example`, the scripts (`bootstrap.sh`,
`start.sh`, `stop.sh`, `reconfigure-ip.sh`, `gen-issuer-ds.sh`, `lib.sh`), the `templates/` configs, and this README.
The scripts run on macOS and Linux (LAN-IP auto-detection covers both; see `lib.sh`).
Git-ignored (per-developer): `.env` (LAN IP + generated secrets), `config/` (all rendered configs
+ keys), `repos/` (the cloned wallet-provider), and the dev CA/TLS material under
`../network-logic/.local-dev-ca/`.
