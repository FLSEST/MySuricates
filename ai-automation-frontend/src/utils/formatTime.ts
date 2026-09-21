export function formatDuration(ms: number | null): string {
  if (!ms) return '-';
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  return `${(ms / 60_000).toFixed(1)}m`;
}

export function formatRelativeTime(dateStr: string, locale: string = 'en'): string {
  const diff = Date.now() - new Date(dateStr).getTime();
  const seconds = Math.floor(diff / 1000);
  const minutes = Math.floor(seconds / 60);
  const hours = Math.floor(minutes / 60);
  const days = Math.floor(hours / 24);

  const rtf = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' });

  if (seconds < 60) return rtf.format(-seconds, 'second');
  if (minutes < 60) return rtf.format(-minutes, 'minute');
  if (hours < 24) return rtf.format(-hours, 'hour');
  return rtf.format(-days, 'day');
}

/** A point in the future relative to now ("in 3 hours"); past points read like {@link formatRelativeTime}. */
export function formatTimeUntil(dateStr: string, locale: string = 'en'): string {
  const diff = new Date(dateStr).getTime() - Date.now();
  if (diff <= 0) return formatRelativeTime(dateStr, locale);
  const minutes = Math.round(diff / 60_000);
  const hours = Math.round(minutes / 60);
  const rtf = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' });

  if (minutes < 1) return rtf.format(Math.round(diff / 1000), 'second');
  if (minutes < 90) return rtf.format(minutes, 'minute');
  if (hours < 48) return rtf.format(hours, 'hour');
  return rtf.format(Math.round(hours / 24), 'day');
}

export function formatDateTime(dateStr: string, locale: string = 'en'): string {
  const d = new Date(dateStr);
  const intlLocale = locale === 'de' ? 'de-DE' : 'en-US';
  const datePart = new Intl.DateTimeFormat(intlLocale, { day: '2-digit', month: '2-digit' }).format(d);
  const timePart = new Intl.DateTimeFormat(intlLocale, { hour: '2-digit', minute: '2-digit', hour12: false }).format(d);
  return `${datePart} ${timePart}`;
}
