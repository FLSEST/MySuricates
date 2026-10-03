import { Hint } from '../components/hints/Hint';
import { CalendarDays } from 'lucide-react';
import { UnifiedCalendarGrid } from '../components/calendar/UnifiedCalendarGrid';
import { useT } from '../i18n';

export function Calendar() {
  const t = useT();

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-3">
        <CalendarDays className="w-6 h-6 text-accent" />
        <h1 className="text-2xl font-bold text-text">{t.calendar.title}</h1>
      </div>
      <p className="text-text-secondary">{t.calendar.subtitle}</p>

      <Hint id="calendar-overview" />

      <UnifiedCalendarGrid />
    </div>
  );
}
