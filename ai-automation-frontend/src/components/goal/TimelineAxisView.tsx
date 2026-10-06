import type { RefObject } from 'react';
import { Flag } from 'lucide-react';
import { useI18nStore, useT } from '../../i18n';
import { formatDate } from '../../utils/localeFormat';
import {
  TODAY_LABEL_OVERLAP, TODAY_LABEL_WIDTH, dateOfDay, dayAtX, todayLabelPlacement, xOfDay, type TimelineAxis,
  type TodayLabelPlacement,
} from './timelineLayout';

/** Mindestabstand zweier beschrifteter Rasterlinien in px; dazwischen bleiben Linien unbeschriftet. */
const MIN_LABEL_PX = 30;
/**
 * Space of a month or year label: at the start of the axis it is omitted if the next month
 * (or the next year) follows that closely, at the end if not that much space remains to the edge.
 */
const MIN_BAND_PX = 70;
const TICK_DAYS = { day: 1, week: 7, month: 30.4 } as const;
/** Space a left-aligned tick label („28.10.“, „Nov.“) needs on the right; less at the axis end → none. */
const TICK_LABEL_PX = 36;
/** Position of the „heute“ label per evasion direction; sideways it reaches a bit past today's line. */
const TODAY_LABEL: Record<TodayLabelPlacement, { className: string; offset: number }> = {
  center: { className: 'bottom-1 -translate-x-1/2', offset: 0 },
  right: { className: 'bottom-1', offset: -TODAY_LABEL_OVERLAP },
  left: { className: 'bottom-1 -translate-x-full', offset: TODAY_LABEL_OVERLAP },
  raised: { className: 'top-5 z-10 -translate-x-1/2', offset: 0 },
};

interface TimelineAxisViewProps {
  axis: TimelineAxis;
  width: number;
  targetDay: number | null;
  /** The axis is measured - its width is that of all rows. */
  measureRef: RefObject<HTMLDivElement | null>;
  onCreateAt: (day: number, x: number) => void;
}

/**
 * The axis of the timeline (TODO e192df18): months or years, below them the grid, the flag of the
 * goal date and „heute“. A click creates a milestone on that day (c60bf914) - like a
 * click in the milestone row directly below.
 */
export function TimelineAxisView({
  axis, width, targetDay, measureRef, onCreateAt,
}: TimelineAxisViewProps) {
  const t = useT();
  const tt = t.goals.timeline;
  const locale = useI18nStore((s) => s.locale);
  const perDay = width / axis.days;
  const labelEvery = Math.max(1, Math.ceil(MIN_LABEL_PX / (perDay * TICK_DAYS[axis.scale])));
  const x = (day: number) => xOfDay(axis, day, width);
  const start = (day: number) => (day - axis.startDay) * perDay;
  /** At the right end a label stuck out past the card - then better none. */
  const fitsTick = (day: number) => (axis.scale === 'day' ? width - x(day) >= 8 : width - start(day) >= TICK_LABEL_PX);

  const tickLabel = (day: number) => {
    const date = dateOfDay(day);
    if (axis.scale === 'day') return String(date.getDate());
    if (axis.scale === 'week') return formatDate(date, locale, { day: 'numeric', month: 'numeric' });
    return formatDate(date, locale, { month: 'short' });
  };
  const bandLabel = (day: number) =>
    formatDate(dateOfDay(day), locale, axis.scale === 'month' ? { year: 'numeric' } : { month: 'short', year: 'numeric' });
  const todayPlacement = todayLabelPlacement(x(axis.today), targetDay !== null ? [x(targetDay)] : [], width);
  const todayLabel = TODAY_LABEL[todayPlacement];

  return (
    <div
      ref={measureRef}
      data-testid="timeline-axis"
      title={tt.milestoneRowHint}
      onClick={(event) => {
        const px = event.clientX - event.currentTarget.getBoundingClientRect().left;
        onCreateAt(dayAtX(axis, px, width), px);
      }}
      className="relative h-16 cursor-copy select-none"
    >
      {axis.bands.map((band, index) => {
        const next = axis.bands[index + 1];
        if (index === 0 && next !== undefined && (next - band) * perDay < MIN_BAND_PX) return null;
        if (width - start(band) < MIN_BAND_PX) return null;
        return (
          <span key={band} className="absolute top-0 whitespace-nowrap pl-1 text-[11px] font-semibold text-text-secondary"
            style={{ left: start(band) }}>
            {bandLabel(band)}
          </span>
        );
      })}
      {axis.ticks.map((tick, index) => index % labelEvery === 0 && fitsTick(tick) && (
        <span key={tick}
          className={`absolute top-5 whitespace-nowrap text-[11px] tabular-nums ${axis.scale === 'day' ? '-translate-x-1/2' : 'pl-1'} ${
            tick === axis.today ? 'font-semibold text-info' : 'text-text-tertiary'}`}
          style={{ left: axis.scale === 'day' ? x(tick) : start(tick) }}>
          {tickLabel(tick)}
        </span>
      ))}
      <span data-testid="timeline-today" data-placement={todayPlacement}
        className={`absolute whitespace-nowrap rounded border border-info/40 bg-info-subtle text-center text-[10px] font-medium text-info ${todayLabel.className}`}
        style={{ left: x(axis.today) + todayLabel.offset, width: TODAY_LABEL_WIDTH }}>
        {tt.today}
      </span>
      {targetDay !== null && (
        <span data-testid="timeline-target" title={tt.target.replace('{date}', formatDate(dateOfDay(targetDay), locale))}
          className="absolute bottom-1 -translate-x-1/2 text-accent" style={{ left: x(targetDay) }}>
          <Flag className="h-4 w-4" aria-hidden="true" />
        </span>
      )}
    </div>
  );
}
