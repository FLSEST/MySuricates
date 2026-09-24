import type { RefObject } from 'react';
import { Flag } from 'lucide-react';
import { useI18nStore, useT } from '../../i18n';
import { formatDate } from '../../utils/localeFormat';
import { dateOfDay, dayAtX, dayOf, xOfDay, type TimelineAxis } from './timelineLayout';
import { SHORT_DATE } from './planDisplay';
import { MilestoneDiamond } from './MilestoneDiamond';
import type { GoalMilestone } from '../../types';

/** Mindestabstand zweier beschrifteter Rasterlinien in px; dazwischen bleiben Linien unbeschriftet. */
const MIN_LABEL_PX = 30;
/** Die Beschriftung am Anfang der Achse entfällt, wenn der nächste Monat (bzw. das nächste Jahr) so nah folgt. */
const MIN_BAND_PX = 70;
const TICK_DAYS = { day: 1, week: 7, month: 30.4 } as const;

interface TimelineAxisViewProps {
  axis: TimelineAxis;
  width: number;
  milestones: GoalMilestone[];
  targetDay: number | null;
  /** Tag, auf dem ein gezogenes TODO landen würde. */
  preview: number | null;
  /** Die Achse wird gemessen – ihre Breite ist die aller Zeilen. */
  measureRef: RefObject<HTMLDivElement | null>;
  onCreateAt: (day: number, x: number) => void;
  onOpenMilestone: (id: string) => void;
}

/**
 * Die Achse des Zeitstrahls (TODO e192df18): Monate bzw. Jahre, darunter das Raster, unten die
 * Rauten der Meilensteine (Farbe nach Zustand), die Fahne des Zieldatums und „heute“. Ein Klick
 * daneben legt an diesem Tag einen Meilenstein an.
 */
export function TimelineAxisView({
  axis, width, milestones, targetDay, preview, measureRef, onCreateAt, onOpenMilestone,
}: TimelineAxisViewProps) {
  const t = useT();
  const tt = t.goals.timeline;
  const tm = t.goals.milestones;
  const locale = useI18nStore((s) => s.locale);
  const perDay = width / axis.days;
  const labelEvery = Math.max(1, Math.ceil(MIN_LABEL_PX / (perDay * TICK_DAYS[axis.scale])));
  const x = (day: number) => xOfDay(axis, day, width);
  const start = (day: number) => (day - axis.startDay) * perDay;

  const tickLabel = (day: number) => {
    const date = dateOfDay(day);
    if (axis.scale === 'day') return String(date.getDate());
    if (axis.scale === 'week') return formatDate(date, locale, { day: 'numeric', month: 'numeric' });
    return formatDate(date, locale, { month: 'short' });
  };
  const bandLabel = (day: number) =>
    formatDate(dateOfDay(day), locale, axis.scale === 'month' ? { year: 'numeric' } : { month: 'short', year: 'numeric' });

  return (
    <div
      ref={measureRef}
      data-testid="timeline-axis"
      title={tt.axisHint}
      onClick={(event) => {
        const px = event.clientX - event.currentTarget.getBoundingClientRect().left;
        onCreateAt(dayAtX(axis, px, width), px);
      }}
      className="relative h-16 cursor-copy select-none"
    >
      {axis.bands.map((band, index) => {
        const next = axis.bands[index + 1];
        if (index === 0 && next !== undefined && (next - band) * perDay < MIN_BAND_PX) return null;
        return (
          <span key={band} className="absolute top-0 whitespace-nowrap pl-1 text-[11px] font-semibold text-text-secondary"
            style={{ left: start(band) }}>
            {bandLabel(band)}
          </span>
        );
      })}
      {axis.ticks.map((tick, index) => index % labelEvery === 0 && (
        <span key={tick}
          className={`absolute top-5 whitespace-nowrap text-[11px] tabular-nums ${axis.scale === 'day' ? '-translate-x-1/2' : 'pl-1'} ${
            tick === axis.today ? 'font-semibold text-info' : 'text-text-tertiary'}`}
          style={{ left: axis.scale === 'day' ? x(tick) : start(tick) }}>
          {tickLabel(tick)}
        </span>
      ))}
      <span className="absolute bottom-1 -translate-x-1/2 rounded border border-info/40 bg-info-subtle px-1 text-[10px] font-medium text-info"
        style={{ left: x(axis.today) }}>
        {tt.today}
      </span>
      {targetDay !== null && (
        <span data-testid="timeline-target" title={tt.target.replace('{date}', formatDate(dateOfDay(targetDay), locale))}
          className="absolute bottom-1 -translate-x-1/2 text-accent" style={{ left: x(targetDay) }}>
          <Flag className="h-4 w-4" aria-hidden="true" />
        </span>
      )}
      {milestones.map((milestone) => {
        const label = tm.diamond.replace('{title}', milestone.title).replace('{state}', tm.states[milestone.state])
          .replace('{date}', formatDate(milestone.dueDate, locale));
        return (
          <button key={milestone.id} type="button" aria-label={label} title={label}
            onClick={(event) => {
              event.stopPropagation();
              onOpenMilestone(milestone.id);
            }}
            className="absolute bottom-0.5 z-10 -translate-x-1/2 rounded p-1 hover:bg-surface-hover focus-ring"
            style={{ left: x(dayOf(milestone.dueDate)) }}>
            <MilestoneDiamond state={milestone.state} size="lg" />
          </button>
        );
      })}
      {preview !== null && (
        <span className="absolute top-0 z-20 -translate-x-1/2 whitespace-nowrap rounded bg-accent px-1.5 py-0.5 text-[11px] font-medium text-text-inverse shadow"
          style={{ left: x(preview) }}>
          {tt.dropOn.replace('{date}', formatDate(dateOfDay(preview), locale, SHORT_DATE))}
        </span>
      )}
    </div>
  );
}
