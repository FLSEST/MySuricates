import { Clock } from 'lucide-react';
import { useT } from '../../i18n';
import { useRateLimitBackoffUntil } from '../../hooks/useRateLimitBackoff';

/**
 * Unaufdringliche Statuszeile, solange ein 429-Backoff läuft (c168293d). `errors.rateLimited`
 * erscheint sonst nur in `onError`-Toasts — Seiten, die Queries ohne Toast rendern, zeigten im
 * Sperrfenster nur leere Zustände, ohne Hinweis worauf man wartet.
 */
export function RateLimitBanner() {
  const t = useT();
  const until = useRateLimitBackoffUntil();

  if (until <= 0) return null;

  return (
    <div
      role="status"
      aria-live="polite"
      className="flex items-center justify-center gap-2 px-4 py-1.5 text-sm bg-warning-subtle text-warning"
    >
      <Clock className="w-4 h-4 shrink-0" aria-hidden="true" />
      <span>{t.errors.rateLimitedUntil.replace('{time}', new Date(until).toLocaleTimeString())}</span>
    </div>
  );
}
