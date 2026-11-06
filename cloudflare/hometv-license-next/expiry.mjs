export function expiryTimestamp(expiryValue) {
  const expiry = String(expiryValue || "").trim();
  if (expiry.toLowerCase() === "unlimited") {
    // "Unlimited" means through 31 December 2100 in UAE local time.
    // Use the exclusive boundary at midnight after that final day.
    return Date.parse("2101-01-01T00:00:00+04:00");
  }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(expiry)) return null;
  const calendarCheck = Date.parse(expiry + "T00:00:00Z");
  if (!Number.isFinite(calendarCheck) ||
      new Date(calendarCheck).toISOString().slice(0, 10) !== expiry) return null;
  const midnight = Date.parse(expiry + "T00:00:00+04:00");
  if (!Number.isFinite(midnight)) return null;
  return midnight + 24 * 60 * 60 * 1000;
}
