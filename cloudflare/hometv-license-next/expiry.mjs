export function expiryTimestamp(expiryValue) {
  const expiry = String(expiryValue || "").trim();
  if (expiry.toLowerCase() === "unlimited") {
    // Android stores expiresAt as a signed 64-bit integer. This far-future
    // finite timestamp represents a non-expiring entitlement.
    return Date.parse("9999-12-31T23:59:59Z");
  }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(expiry)) return null;
  const calendarCheck = Date.parse(expiry + "T00:00:00Z");
  if (!Number.isFinite(calendarCheck) ||
      new Date(calendarCheck).toISOString().slice(0, 10) !== expiry) return null;
  const midnight = Date.parse(expiry + "T00:00:00+04:00");
  if (!Number.isFinite(midnight)) return null;
  return midnight + 24 * 60 * 60 * 1000;
}
