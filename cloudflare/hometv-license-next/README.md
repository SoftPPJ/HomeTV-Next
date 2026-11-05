# HomeTV Next V1 licence gateway

This is a separate signing gateway for the Next V1 Android app.

- Worker name: `hometv-license-next`
- It delegates activation, device release, admin management and playlist requests to the existing `hometv-license` Worker.
- It re-signs only successful `/validate` and `/validate-lease` responses with the Next V1 RSA key.
- The matching private JWK is stored only as the Cloudflare secret `LICENSE_SIGNING_PRIVATE_JWK`.
- `UPSTREAM_BASE` points to the existing licensing Worker.
- No licence records, activations, device sessions, GitHub credentials, admin token or playlist secret are duplicated into this Worker.

Do not change or delete the existing V6 Worker. Do not commit the private JWK.
