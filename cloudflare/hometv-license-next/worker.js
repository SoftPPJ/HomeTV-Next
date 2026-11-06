import { expiryTimestamp } from "./expiry.mjs";

// Next V1 signing gateway. V6 remains the authoritative licensing backend.
// This Worker delegates all activation, device, admin and playlist operations to
// the existing V6 Worker. It only re-signs successful validation leases with the
// separate public key pinned in the Next V1 APK. No licence database is copied.
const json = (data, status = 200) => new Response(JSON.stringify(data), {
  status,
  headers: { "content-type": "application/json", "cache-control": "no-store" }
});
const enc = value => new TextEncoder().encode(value);
const hex = bytes => [...new Uint8Array(bytes)].map(x => x.toString(16).padStart(2, "0")).join("");
const sha256 = async value => hex(await crypto.subtle.digest("SHA-256", enc(value)));
const base64url = bytes => btoa(String.fromCharCode(...new Uint8Array(bytes)))
  .replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");

async function signedLease(env, body, upstreamData) {
  const device = String(body.device || "").trim();
  const token = String(body.token || "");
  if (!/^[a-zA-Z0-9_-]{16,128}$/.test(device) || token.length < 20 || token.length > 200) {
    return json({ error: "Invalid device or token" }, 400);
  }
  const expiry = String(upstreamData.expiry || "").trim();
  const now = Date.now();
  const expiresAt = expiryTimestamp(expiry);
  if (expiresAt == null) {
    return json({ error: "Licensing backend returned an invalid expiry" }, 502);
  }
  if (expiresAt <= now) return json({ error: "License expired" }, 403);

  const tokenHash = await sha256(token);
  const payload = {
    version: 1,
    licenseId: tokenHash.slice(0, 16),
    device,
    tokenHash,
    issuedAt: now,
    serverTime: now,
    expiresAt
  };
  if (!env.LICENSE_SIGNING_PRIVATE_JWK) {
    console.error("Next V1 signing key is not configured");
    return json({ error: "Licensing service unavailable" }, 503);
  }
  const jwk = JSON.parse(env.LICENSE_SIGNING_PRIVATE_JWK);
  const key = await crypto.subtle.importKey(
    "jwk", jwk, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]
  );
  const lease = base64url(enc(JSON.stringify(payload)));
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, enc(lease));
  return json({ ok: true, expiry, lease, signature: base64url(signature), serverTime: now });
}

async function handle(request, env) {
  const incoming = new URL(request.url);
  const upstreamBase = String(env.UPSTREAM_BASE || "").replace(/\/$/, "");
  if (!upstreamBase.startsWith("https://")) return json({ error: "Upstream is not configured" }, 503);

  if (incoming.pathname === "/health" && request.method === "GET") {
    const upstream = await fetch(upstreamBase + "/health", { headers: { "cache-control": "no-store" } });
    return json({ ok: upstream.ok, service: "next-v1-license-gateway" }, upstream.ok ? 200 : 503);
  }

  let validationBody = null;
  if (incoming.pathname === "/validate" || incoming.pathname === "/validate-lease") {
    try { validationBody = await request.clone().json(); }
    catch { return json({ error: "Invalid JSON" }, 400); }
  }

  const target = upstreamBase + incoming.pathname + incoming.search;
  const headers = new Headers();
  for (const name of ["content-type", "authorization"]) {
    const value = request.headers.get(name);
    if (value) headers.set(name, value);
  }
  const init = { method: request.method, headers, redirect: "follow" };
  if (request.method !== "GET" && request.method !== "HEAD") init.body = await request.arrayBuffer();
  const upstream = await fetch(target, init);

  if (incoming.pathname === "/validate" || incoming.pathname === "/validate-lease") {
    const raw = await upstream.text();
    if (!upstream.ok) {
      return new Response(raw, {
        status: upstream.status,
        headers: { "content-type": "application/json", "cache-control": "no-store" }
      });
    }
    let body;
    try { body = JSON.parse(raw); } catch { return json({ error: "Invalid licensing backend response" }, 502); }
    if (!body || body.ok !== true) return json({ error: "License validation failed" }, 502);
    return signedLease(env, validationBody, body);
  }

  const responseHeaders = new Headers();
  const contentType = upstream.headers.get("content-type");
  if (contentType) responseHeaders.set("content-type", contentType);
  responseHeaders.set("cache-control", "no-store");
  return new Response(upstream.body, { status: upstream.status, headers: responseHeaders });
}

export default {
  async fetch(request, env) {
    try { return await handle(request, env); }
    catch (error) {
      console.error("Next V1 licensing gateway error", error);
      return json({ error: "Licensing service unavailable" }, 503);
    }
  }
};
