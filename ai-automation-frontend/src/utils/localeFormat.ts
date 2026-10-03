import type { Locale } from '../i18n';

/**
 * The one place that turns dates and numbers into text — always in the language chosen in the app,
 * never in the browser's (e86f6544: without a locale the dashboard clock read „Donnerstag,
 * 24.09.2026" in the English UI). `localeFormat.guard.test.ts` keeps `Intl` and the
 * toLocale…String methods out of every other file.
 */
const INTL_LOCALE: Record<Locale, string> = { de: 'de-DE', en: 'en-US' };

type DateInput = string | number | Date;

/** BCP 47 tag of the app language. */
export function intlLocale(locale: Locale): string {
  return INTL_LOCALE[locale];
}

function toDate(value: DateInput): Date {
  return value instanceof Date ? value : new Date(value);
}

/**
 * The time of day always on a 24-hour clock, in English too (571524a5: `formatTimestamp` read "2:05 PM",
 * `formatDateTime` "14:05"). `hourCycle` instead of `hour12: false`, which some engines turn into "24:07" after
 * midnight; a caller's `hour12` would override `hourCycle`, so it goes. Without time fields it changes nothing.
 */
function clock24(options?: Intl.DateTimeFormatOptions): Intl.DateTimeFormatOptions {
  const { hour12: _ignored, ...rest } = options ?? {};
  return { ...rest, hourCycle: 'h23' };
}

/** Date and time of day ("9/24/2026, 14:05:33" / "24.9.2026, 14:05:33"), or the fields in `options`. */
export function formatTimestamp(value: DateInput, locale: Locale, options?: Intl.DateTimeFormatOptions): string {
  return toDate(value).toLocaleString(intlLocale(locale), clock24(options));
}

/** The date ("9/24/2026" / "24.9.2026"), or the fields in `options`. */
export function formatDate(value: DateInput, locale: Locale, options?: Intl.DateTimeFormatOptions): string {
  return toDate(value).toLocaleDateString(intlLocale(locale), clock24(options));
}

/** Short weekday names from Monday to Sunday ("Mon" … "Sun" / „Mo.“ … „So.“), for calendar column heads. */
export function weekdayNames(locale: Locale): string[] {
  // 1 January 2024 was a Monday
  return Array.from({ length: 7 }, (_, i) => formatDate(new Date(2024, 0, 1 + i), locale, { weekday: 'short' }));
}

/**
 * A span of dates as the app language writes it, shared parts once ("September 21 – 27, 2026" /
 * „21.–27. September 2026“).
 */
export function formatDateRange(
  start: DateInput, end: DateInput, locale: Locale, options: Intl.DateTimeFormatOptions,
): string {
  return new Intl.DateTimeFormat(intlLocale(locale), clock24(options)).formatRange(toDate(start), toDate(end));
}

/** The time of day on a 24-hour clock ("14:05:33" in both languages), or the fields in `options`. */
export function formatTimeOfDay(value: DateInput, locale: Locale, options?: Intl.DateTimeFormatOptions): string {
  return toDate(value).toLocaleTimeString(intlLocale(locale), clock24(options));
}

/** A number with the separators of the app language ("1,234.5" / "1.234,5"). */
export function formatNumber(value: number, locale: Locale, options?: Intl.NumberFormatOptions): string {
  return value.toLocaleString(intlLocale(locale), options);
}

/** Exactly `digits` decimals like `toFixed`, but with the separators of the app language ("1.5" / "1,5"). */
export function formatDecimal(value: number, locale: Locale, digits = 1): string {
  return formatNumber(value, locale, { minimumFractionDigits: digits, maximumFractionDigits: digits });
}

/**
 * A US-dollar amount ("$0.12" / "0,12 $"). Below a cent with four decimals, so the fractions of a
 * cent an AI call costs do not read as zero; `fractionDigits` fixes the decimals instead.
 */
export function formatUsd(value: number, locale: Locale, fractionDigits?: number): string {
  const digits = fractionDigits ?? (value !== 0 && Math.abs(value) < 0.01 ? 4 : 2);
  return formatNumber(value, locale, {
    style: 'currency', currency: 'USD', minimumFractionDigits: digits, maximumFractionDigits: digits,
  });
}

/**
 * Decimals for every tick of a cost axis, from its largest value: the same for all ticks, and only as many as
 * that value needs ("$0.25" / "$0.025" / "$0.0025"). Per-tick decimals from `formatUsd` mix widths and cut labels.
 */
export function usdAxisDigits(max: number): number {
  const top = Math.abs(max);
  return top === 0 || top >= 0.1 ? 2 : top >= 0.01 ? 3 : 4;
}

/** A token count, shortened above a thousand ("1.2k", "3.4M" / "1,2k", "3,4M"), below with separators. */
export function formatTokenCount(count: number, locale: Locale): string {
  if (count >= 1_000_000) return `${formatDecimal(count / 1_000_000, locale)}M`;
  if (count >= 1_000) return `${formatDecimal(count / 1_000, locale)}k`;
  return formatNumber(count, locale);
}

/** `value` units away from now ("in 3 hours", "vor 5 Minuten"); negative values lie in the past. */
export function formatRelativeUnit(
  value: number,
  unit: Intl.RelativeTimeFormatUnit,
  locale: Locale,
  options: Intl.RelativeTimeFormatOptions = { numeric: 'auto' },
): string {
  return new Intl.RelativeTimeFormat(intlLocale(locale), options).format(value, unit);
}
