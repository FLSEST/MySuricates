import { Clock } from 'lucide-react';
import { useI18nStore, useT } from '../../i18n';
import { useRateLimitBackoffUntil } from '../../hooks/useRateLimitBackoff';

/** Uhrzeit-Format der App-Sprache, nicht der Browser-Locale. */
const TIME_LOCALE = { de: 'de-DE', en: 'en-US' } as const;

/**
 * Unaufdringliche Statuszeile, solange ein 429-Backoff läuft (c168293d). `errors.rateLimited`
 * erscheint sonst nur in `onError`-Toasts — Seiten, die Queries ohne Toast rendern, zeigten im
 * Sperrfenster nur leere Zustände, ohne Hinweis worauf man wartet.
 */
export function RateLimitBanner() {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const until = useRateLimitBackoffUntil();

  if (until <= 0) return null;

  const time = new Date(until).toLocaleTimeString(TIME_LOCALE[locale]);

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
