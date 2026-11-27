// Backend ISO metadata is authoritative; browser CLDR defaults can differ (for example MMK).
const scales = new Map([["THB", 2]]);
export function configureCurrencies(metadata) {
  const next = new Map();
  for (const item of metadata) {
    if (!/^[A-Z]{3}$/.test(item.code) || !Number.isInteger(item.fractionDigits) || item.fractionDigits < 0 || item.fractionDigits > 2)
      throw new Error("Invalid currency metadata");
    if (next.has(item.code)) throw new Error("Duplicate currency metadata");
    next.set(item.code, item.fractionDigits);
  }
  if (!next.has("THB")) throw new Error("Primary currency metadata is missing");
  scales.clear(); next.forEach((scale, code) => scales.set(code, scale));
}
export function formatMoney(value, currency = "THB") {
  const scale = scales.get(currency);
  if (scale === undefined) throw new Error("Currency metadata is unavailable");
  const match = /^(-?)([0-9]+)(?:[.]([0-9]+))?$/.exec(String(value ?? "0"));
  if (!match) throw new Error("Invalid monetary value");
  const [, sign, whole, fraction = ""] = match;
  if (/[1-9]/.test(fraction.slice(scale))) throw new Error("Amount exceeds currency precision");
  return sign + whole.replace(/\B(?=(\d{3})+(?!\d))/g, ",") + (scale ? "." + fraction.padEnd(scale, "0").slice(0, scale) : "");
}
