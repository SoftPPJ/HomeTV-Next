# HomeTV licensing Worker setup

Worker: https://hometv-license.prasanthpjoy.workers.dev/

Deploy `cloudflare/hometv-license.js` in the Cloudflare Workers editor (replace the default starter code).

Create a **separate private** GitHub repository for licensing and the protected default playlist. Set Worker secrets:
- `GITHUB_TOKEN`: fine-grained token with **Contents: Read and write** for that private repository only.
- `ADMIN_TOKEN`: long random administrator token; never embed in APK.

Set Worker variables:
- `GITHUB_REPO`: `owner/private-license-repo`
- `LICENSE_PATH`: `licenses.json`
- `PLAYLIST_PATH`: `playlist.m3u`

Create `licenses.json` in the private repository as a JSON array, e.g.
```json
[
  {"id":"USER001","passwordHash":"SHA256_HEX_OF_PASSWORD","expiry":"2027-12-31","device":null,"tokenHash":null},
  {"id":"USER002","passwordHash":"SHA256_HEX_OF_ANOTHER_PASSWORD","expiry":"unlimited","device":null,"tokenHash":null}
]
```
Generate password hashes locally; do not commit plain-text passwords. Use random, unique passwords of 12+ characters.

Endpoints (all POST except GET /health):
- `/activate`: `{"password":"...","device":"random-installation-uuid"}` → `token`, `expiry`
- `/validate`: `{"token":"...","device":"..."}`
- `/playlist`: `{"token":"...","device":"..."}` → protected M3U
- `/surrender`: `{"token":"...","device":"..."}` → releases license
- `/admin/release`: Authorization Bearer ADMIN_TOKEN; `{"id":"USER001"}` → releases lost license

**Limitations:** GitHub Contents API uses optimistic concurrency, so simultaneous activations of the same password cannot both succeed; conflicting writes fail closed and may require retry. GitHub-hosted files are not an ideal high-volume transactional database. For production-scale licensing, Cloudflare D1 with transactional activation is recommended. Installation IDs are not hardware-bound and may be cloned on compromised devices. The app must never access a cached protected playlist before successful online validation; offline mode should remain locked. Android cannot intercept uninstall. Do not distribute protected M3U through a public GitHub URL.
