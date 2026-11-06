import test from "node:test";
import assert from "node:assert/strict";
import { expiryTimestamp } from "./expiry.mjs";

test("unlimited entitlement ends after 31 December 2100 in UAE time", () => {
  const boundary = Date.parse("2101-01-01T00:00:00+04:00");
  assert.equal(expiryTimestamp("unlimited"), boundary);
  assert.equal(expiryTimestamp("UNLIMITED"), boundary);
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
