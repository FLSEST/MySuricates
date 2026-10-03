import type { Locale } from '../i18n';
import { formatDate, formatDecimal, formatRelativeUnit, formatTimeOfDay } from './localeFormat';

/** Run or step duration: "500ms", "1.5s" / "1,5s", "2.5m" / "2,5m"; nothing measured reads "-". */
export function formatDuration(ms: number | null, locale: Locale): string {
  if (!ms) return '-';
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${formatDecimal(ms / 1000, locale)}s`;
  return `${formatDecimal(ms / 60_000, locale)}m`;
}

export function formatRelativeTime(dateStr: string, locale: Locale): string {
  const diff = Date.now() - new Date(dateStr).getTime();
  const seconds = Math.floor(diff / 1000);
  const minutes = Math.floor(seconds / 60);
  const hours = Math.floor(minutes / 60);
  const days = Math.floor(hours / 24);

  if (seconds < 60) return formatRelativeUnit(-seconds, 'second', locale);
  if (minutes < 60) return formatRelativeUnit(-minutes, 'minute', locale);
  if (hours < 24) return formatRelativeUnit(-hours, 'hour', locale);
  return formatRelativeUnit(-days, 'day', locale);
}

/** A point in the future relative to now ("in 3 hours"); past points read like {@link formatRelativeTime}. */
export function formatTimeUntil(dateStr: string, locale: Locale): string {
  const diff = new Date(dateStr).getTime() - Date.now();
  if (diff <= 0) return formatRelativeTime(dateStr, locale);
  const minutes = Math.round(diff / 60_000);
  const hours = Math.round(minutes / 60);

  if (minutes < 1) return formatRelativeUnit(Math.round(diff / 1000), 'second', locale);
  if (minutes < 90) return formatRelativeUnit(minutes, 'minute', locale);
  if (hours < 48) return formatRelativeUnit(hours, 'hour', locale);
  return formatRelativeUnit(Math.round(hours / 24), 'day', locale);
}

/**
 * Compact distance to now for narrow rows, past or future ("5m ago", "in 3h", „vor 2 Std.“);
 * a month or more away shows the date instead.
 */
export function formatRelativeShort(dateStr: string, locale: Locale): string {
  const diff = new Date(dateStr).getTime() - Date.now();
  const minutes = Math.trunc(diff / 60_000);
  const hours = Math.trunc(minutes / 60);
  const days = Math.trunc(hours / 24);
  const narrow = { style: 'narrow', numeric: 'always' } as const;

  if (minutes === 0) return formatRelativeUnit(0, 'second', locale);
  if (Math.abs(minutes) < 60) return formatRelativeUnit(minutes, 'minute', locale, narrow);
  if (Math.abs(hours) < 24) return formatRelativeUnit(hours, 'hour', locale, narrow);
  if (Math.abs(days) < 30) return formatRelativeUnit(days, 'day', locale, narrow);
  return formatDate(dateStr, locale);
}

/** Day, month and 24-hour time ("09/24 14:05" / "24.09. 14:05"). */
export function formatDateTime(dateStr: string, locale: Locale): string {
  const datePart = formatDate(dateStr, locale, { day: '2-digit', month: '2-digit' });
  const timePart = formatTimeOfDay(dateStr, locale, { hour: '2-digit', minute: '2-digit' });
  return `${datePart} ${timePart}`;
}
