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

/** Date and time of day ("9/24/2026, 2:05:33 PM" / "24.9.2026, 14:05:33"), or the fields in `options`. */
export function formatTimestamp(value: DateInput, locale: Locale, options?: Intl.DateTimeFormatOptions): string {
  return toDate(value).toLocaleString(intlLocale(locale), options);
}

/** The date ("9/24/2026" / "24.9.2026"), or the fields in `options`. */
export function formatDate(value: DateInput, locale: Locale, options?: Intl.DateTimeFormatOptions): string {
  return toDate(value).toLocaleDateString(intlLocale(locale), options);
}

/** The time of day ("2:05:33 PM" / "14:05:33"), or the fields in `options`. */
export function formatTimeOfDay(value: DateInput, locale: Locale, options?: Intl.DateTimeFormatOptions): string {
  return toDate(value).toLocaleTimeString(intlLocale(locale), options);
}

/** A number with the separators of the app language ("1,234.5" / "1.234,5"). */
export function formatNumber(value: number, locale: Locale, options?: Intl.NumberFormatOptions): string {
  return value.toLocaleString(intlLocale(locale), options);
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
