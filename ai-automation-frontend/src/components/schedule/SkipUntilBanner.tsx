import { CalendarOff } from 'lucide-react';
import { useT, useI18nStore } from '../../i18n';
import { formatDateTime } from '../../utils/formatTime';

/**
 * The skip-until pause from the configuration (a776fd2a): until its end the tick skips every due
 * schedule instead of starting it. Renders nothing while no pause is active.
 */
export function SkipUntilBanner({ pausedUntil }: { pausedUntil: string | null | undefined }) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  if (!pausedUntil) return null;

  return (
    <div
      role="status"
      data-testid="skip-until-banner"
      className="flex items-start gap-3 px-4 py-3 rounded-lg bg-info-subtle border border-info/30 text-info"
    >
      <CalendarOff className="w-4 h-4 shrink-0 mt-0.5" />
      <span className="text-sm">
        {t.schedules.skipUntilActive.replace('{until}', () => formatDateTime(pausedUntil, locale))}
      </span>
    </div>
  );
}
