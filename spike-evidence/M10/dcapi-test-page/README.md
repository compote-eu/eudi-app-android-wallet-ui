# DC API test page (M10, D10)

`index.html` is a single static file. Its button calls
`navigator.credentials.get({digital:{requests:[{protocol:'org-iso-mdoc', data}]}})`, with a PID request
(`eu.europa.ec.eudi.pid.1`, `family_name`, `given_name`) built in the page: a CBOR `DeviceRequest` without reader
authentication and an `EncryptionInfo` `["dcapi", {nonce, recipientPublicKey}]` with a fresh P-256 key. This is the shape
`DcApiHarness` (`buildDcApiProbeRequest`) produces and `IosDcApiPresenter` parses. The page counts the runs in the tab.

The Digital Credentials API needs **HTTPS** (a secure context) and Safari on iOS 26 or later.

## Hosting (any HTTPS host)

- GitHub Pages: put `index.html` in a repository (for example a fork), enable Pages, open `https://<user>.github.io/<repo>/`.
- LAN with a local certificate:
  1. `brew install mkcert && mkcert -install` and `mkcert <lan-ip> localhost` (for example `192.168.1.20`).
  2. Install the mkcert root CA on the iPhone: AirDrop `$(mkcert -CAROOT)/rootCA.pem`, install the profile, then
     Settings > General > About > Certificate Trust Settings > enable it.
  3. `npx http-server spike-evidence/M10/dcapi-test-page -S -C <ip>+1.pem -K <ip>+1-key.pem -p 8443`
  4. Open `https://<lan-ip>:8443/` in Safari on the iPhone.
- Any other static host with a valid certificate works (Netlify, Cloudflare Pages, a tunnel such as `cloudflared`).

The wallet's extension must be selected in the system sheet that Safari shows. The flow's steps are in `HUMAN-QUEUE.md`, Q-D10.
