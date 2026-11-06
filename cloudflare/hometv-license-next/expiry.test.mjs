import test from "node:test";
import assert from "node:assert/strict";
import { expiryTimestamp } from "./expiry.mjs";

test("unlimited entitlement gets a far-future expiry timestamp", () => {
  assert.equal(expiryTimestamp("unlimited"), Date.parse("9999-12-31T23:59:59Z"));
  assert.equal(expiryTimestamp("UNLIMITED"), Date.parse("9999-12-31T23:59:59Z"));
});
test("date expiry ends at the UAE local day boundary", () => {
  assert.equal(expiryTimestamp("2026-11-06"), Date.parse("2026-11-06T00:00:00+04:00") + 86400000);
});
test("invalid and impossible calendar dates are rejected", () => {
  assert.equal(expiryTimestamp(""), null);
  assert.equal(expiryTimestamp("forever"), null);
  assert.equal(expiryTimestamp("2026-02-30"), null);
  assert.equal(expiryTimestamp("2026/11/06"), null);
});
