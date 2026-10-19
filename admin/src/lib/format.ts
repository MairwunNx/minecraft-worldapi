import i18n from "i18next";

/** Numbers, sizes, and dates in the reader's language, through Intl. */
const locale = () => i18n.language || "en";

export const number = (n: number, digits = 0) => new Intl.NumberFormat(locale(), { maximumFractionDigits: digits }).format(n);

export const compact = (n: number) => new Intl.NumberFormat(locale(), { notation: "compact", maximumFractionDigits: 1 }).format(n);

export const percent = (n: number, digits = 1) => new Intl.NumberFormat(locale(), { style: "percent", maximumFractionDigits: digits }).format(n);

export const dateTime = (iso: string | null | undefined) =>
  iso ? new Intl.DateTimeFormat(locale(), { dateStyle: "medium", timeStyle: "short" }).format(new Date(iso)) : "—";

export const date = (iso: string | null | undefined) => (iso ? new Intl.DateTimeFormat(locale(), { dateStyle: "medium" }).format(new Date(iso)) : "—");

export const shortDate = (iso: string) => new Intl.DateTimeFormat(locale(), { month: "short", day: "numeric" }).format(new Date(iso));

export const hour = (iso: string) => new Intl.DateTimeFormat(locale(), { hour: "2-digit", minute: "2-digit" }).format(new Date(iso));

export const duration = (ms: number | null | undefined) => {
  if (ms === null || ms === undefined) return "—";
  if (ms < 1000) return `${number(ms)} ms`;
  if (ms < 60_000) return `${number(ms / 1000, 1)} s`;
  return `${number(Math.floor(ms / 60_000))} min ${number(Math.round((ms % 60_000) / 1000))} s`;
};

export const relative = (iso: string | null | undefined) => {
  if (!iso) return "—";
  const seconds = (new Date(iso).getTime() - Date.now()) / 1000;
  const format = new Intl.RelativeTimeFormat(locale(), { numeric: "auto" });
  const steps: Array<[Intl.RelativeTimeFormatUnit, number]> = [
    ["year", 31_536_000],
    ["month", 2_592_000],
    ["day", 86_400],
    ["hour", 3_600],
    ["minute", 60],
  ];
  for (const [unit, size] of steps) if (Math.abs(seconds) >= size) return format.format(Math.round(seconds / size), unit);
  return format.format(Math.round(seconds), "second");
};

/** Bytes in binary units, as Java and the OS count memory. */
export const bytes = (n: number | null | undefined) => {
  if (n === null || n === undefined) return "—";
  const units = ["B", "KiB", "MiB", "GiB", "TiB"];
  let value = n;
  let unit = 0;
  while (Math.abs(value) >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${number(value, unit === 0 ? 0 : value < 10 ? 2 : 1)} ${units[unit]}`;
};

/** Uptime and playtime: the two largest units, like “3 d 4 h”. */
export const span = (seconds: number | null | undefined) => {
  if (seconds === null || seconds === undefined) return "—";
  const s = Math.max(0, Math.floor(seconds));
  const parts: Array<[number, string]> = [
    [Math.floor(s / 86_400), "d"],
    [Math.floor((s % 86_400) / 3_600), "h"],
    [Math.floor((s % 3_600) / 60), "min"],
    [s % 60, "s"],
  ];
  const first = parts.findIndex(([value]) => value > 0);
  if (first < 0) return "0 s";
  return parts
    .slice(first, first + 2)
    .filter(([value]) => value > 0)
    .map(([value, unit]) => `${number(value)} ${unit}`)
    .join(" ");
};

export const time = (iso: string | number) => new Intl.DateTimeFormat(locale(), { hour: "2-digit", minute: "2-digit", second: "2-digit" }).format(new Date(iso));
