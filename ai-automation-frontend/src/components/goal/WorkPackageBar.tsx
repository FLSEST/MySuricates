import type { KeyboardEvent } from 'react';
import { useIsSelected } from './goalSelection';
import { useDraggable } from '@dnd-kit/core';
import { Check } from 'lucide-react';
import { useI18nStore, useT } from '../../i18n';
import {
  BAR_HEIGHT, BAR_TOP, barBox, barLabelFit, movedRange, resizedRange, sameRange, type DayRange, type TimelineAxis,
} from './timelineLayout';
import type { ChipDragData } from './timelineKeyboard';
import { rangeText } from './dragLabel';
import { pointerOnly, useDragState } from './timelineDrag';
import { useKeyboardNudge } from './useKeyboardNudge';
import type { GoalWorkPackage, WorkPackageState } from '../../types';

/** Frame by state; background and fill stay calm - the state should stand out, not the bar. */
const FRAME: Record<WorkPackageState, string> = {
  OPEN: 'border border-info/45 bg-info-subtle',
  DUE_SOON: 'border-2 border-warning bg-info-subtle',
  OVERDUE: 'border-2 border-danger bg-danger-subtle',
  REACHED: 'border border-success bg-success-subtle',
};

const DRAG_DATA: ChipDragData = { anchor: 0, flipped: false, kind: 'package' };

/**
 * A work package as a bar (c60bf914) from start to end, progress as fill, state at the
 * frame, the title in the bar (if it does not fit, next to it). Dragging the bar moves it (the duration
 * stays), the handles at the ends change start or end. Keyboard: ← → move by one day,
 * Shift with ← → changes the end, Enter opens the card, Escape discards; it is saved after
 * a short pause (`useKeyboardNudge`).
 */
export function WorkPackageBar({ workPackage, range, axis, width, helpId, onOpen, onReschedule, announce }: {
  workPackage: GoalWorkPackage;
  /** The stored period. */
  range: DayRange;
  axis: TimelineAxis;
  width: number;
  /** ID of the help text on the keyboard (once per timeline). */
  helpId: string;
  onOpen: (trigger: HTMLElement) => void;
  onReschedule: (range: DayRange) => void;
  /** Announcement for screen readers when the keyboard moves the bar. */
  announce: (text: string) => void;
}) {
  const t = useT();
  const tw = t.goals.workPackages;
  const locale = useI18nStore((s) => s.locale);
  const { dragged, preview, live } = useDragState();
  const nudge = useKeyboardNudge(range, sameRange, onReschedule);
  const id = workPackage.id;
  const mine = dragged?.kind === 'package' && dragged.workPackage.id === id ? dragged.mode : null;
  const resizing = mine === 'start' || mine === 'end';
  const shown = resizing && preview?.workPackageId === id && preview.range ? preview.range : nudge.value;
  const box = barBox(axis, shown, width);
  const { setNodeRef: setBodyRef, listeners: bodyListeners } = useDraggable({ id: `wp:${id}`, data: DRAG_DATA });
  const { setNodeRef: setStartRef, listeners: startListeners } = useDraggable({ id: `wp-start:${id}`, data: DRAG_DATA });
  const { setNodeRef: setEndRef, listeners: endListeners } = useDraggable({ id: `wp-end:${id}`, data: DRAG_DATA });
  const percent = workPackage.state === 'REACHED' ? 100 : workPackage.total > 0 ? (workPackage.done / workPackage.total) * 100 : 0;
  const fit = barLabelFit(workPackage.title, box.width);
  const fits = fit !== 'none';
  // If the title does not fit in, it stands to the right of it - at the right edge, to the left of it
  const titleBefore = !fits && box.left + box.width + workPackage.title.length * 6.2 + 12 > width;
  const label = tw.bar.replace('{title}', workPackage.title).replace('{range}', rangeText(shown, locale))
    .replace('{done}', String(workPackage.done)).replace('{total}', String(workPackage.total));

  // Open in the side bar (c60bf914): highlighted
  const selected = useIsSelected('workpackage', id);
  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
      const step = event.key === 'ArrowRight' ? 1 : -1;
      // Shift changes the end, Alt the start (c60bf914) - without either the whole bar moves
      const next = event.shiftKey ? resizedRange(nudge.value, 'end', step)
        : event.altKey ? resizedRange(nudge.value, 'start', step) : movedRange(nudge.value, step);
      nudge.nudge(next);
      announce(t.goals.timeline.announce.moved.replace('{title}', workPackage.title).replace('{when}', rangeText(next, locale)));
    } else if (event.key === 'Enter') {
      nudge.flush();
      onOpen(event.currentTarget);
    } else if (event.key === 'Escape' && nudge.active) {
      nudge.cancel();
    } else {
      return;
    }
    event.preventDefault();
  };

  return (
    <div className="absolute z-[5]" style={{ left: box.left, width: box.width, top: BAR_TOP, height: BAR_HEIGHT }}
      data-testid={`timeline-bar-${id}`} data-start={shown.start} data-end={shown.end}>
      <span ref={setStartRef} {...pointerOnly(startListeners)} aria-hidden="true" data-testid={`timeline-bar-start-${id}`}
        className="group absolute inset-y-0 -left-1 z-10 flex w-2.5 cursor-ew-resize items-center justify-center" style={{ touchAction: 'none' }}>
        <span className="h-3 w-0.5 rounded bg-text-tertiary opacity-0 transition-opacity group-hover:opacity-100" />
      </span>
      <button ref={setBodyRef} type="button" {...pointerOnly(bodyListeners)}
        aria-label={label} aria-describedby={helpId} title={label}
        onKeyDown={onKeyDown} onBlur={nudge.flush} onClick={(event) => onOpen(event.currentTarget)}
        className={`relative flex h-full w-full cursor-grab items-center overflow-hidden rounded-md text-left text-[11px] focus-ring active:cursor-grabbing ${
          FRAME[workPackage.state]} ${mine === 'move' || mine === 'schedule' ? 'opacity-40' : ''} ${selected ? 'ring-2 ring-accent' : ''}`}
        aria-current={selected || undefined} data-selected={selected || undefined}
        style={{ touchAction: 'none' }}>
        <span aria-hidden="true" className="absolute inset-y-0 left-0 bg-success/30" style={{ width: `${percent}%` }} />
        {fits && (
          <>
            <span className="relative min-w-0 flex-1 truncate px-2 font-medium text-text">{workPackage.title}</span>
            {fit === 'full' && (
              <span className="relative shrink-0 pr-1.5 tabular-nums text-text-secondary">
                {workPackage.state === 'REACHED'
                  ? <Check className="h-3 w-3 text-success" aria-hidden="true" />
                  : `${workPackage.done}/${workPackage.total}`}
              </span>
            )}
          </>
        )}
      </button>
      <span ref={setEndRef} {...pointerOnly(endListeners)} aria-hidden="true" data-testid={`timeline-bar-end-${id}`}
        className="group absolute inset-y-0 -right-1 z-10 flex w-2.5 cursor-ew-resize items-center justify-center" style={{ touchAction: 'none' }}>
        <span className="h-3 w-0.5 rounded bg-text-tertiary opacity-0 transition-opacity group-hover:opacity-100" />
      </span>
      {!fits && (
        <span aria-hidden="true" className="pointer-events-none absolute top-0 whitespace-nowrap text-[11px] font-medium text-text-secondary"
          style={{ lineHeight: `${BAR_HEIGHT}px`, ...(titleBefore ? { right: '100%', marginRight: 6 } : { left: '100%', marginLeft: 6 }) }}>
          {workPackage.title}
        </span>
      )}
      {resizing && live?.text && (
        <span data-testid="timeline-live" className="pointer-events-none absolute left-0 top-full z-20 mt-1 whitespace-nowrap rounded bg-accent px-1.5 py-0.5 text-[11px] font-medium text-text-inverse shadow">
          {live.text}
        </span>
      )}
    </div>
  );
}
