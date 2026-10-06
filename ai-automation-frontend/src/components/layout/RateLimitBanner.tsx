import { Clock } from 'lucide-react';
import { useI18nStore, useT } from '../../i18n';
import { useRateLimitBackoffUntil } from '../../hooks/useRateLimitBackoff';
import { formatTimeOfDay } from '../../utils/localeFormat';

/**
 * Unobtrusive status line while a 429 backoff is running (c168293d). `errors.rateLimited`
 * otherwise appears only in `onError` toasts - pages that render queries without a toast showed, during the
 * lockout window, only empty states, with no hint what they are waiting for.
 */
export function RateLimitBanner() {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const until = useRateLimitBackoffUntil();

  if (until <= 0) return null;

  // Time format of the app language, not of the browser locale.
  const time = formatTimeOfDay(until, locale);

  return (
    <div
      role="status"
      aria-live="polite"
      className="flex items-center justify-center gap-2 px-4 py-1.5 text-sm bg-warning-subtle text-warning"
    >
      <Clock className="w-4 h-4 shrink-0" aria-hidden="true" />
      <span>{t.errors.rateLimitedUntil.replace('{time}', time)}</span>
    </div>
  );
}
