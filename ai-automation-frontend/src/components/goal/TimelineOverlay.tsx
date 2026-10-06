import { HelpCircle } from 'lucide-react';
import { BAR_HEIGHT, CHIP_WIDTH, DIAMOND_ANCHOR, isOverdue } from './timelineLayout';
import { ChipFace } from './TimelineChip';
import { MilestoneDiamond } from './MilestoneDiamond';
import { WorkPackageMark } from './WorkPackageMark';
import type { Dragged, LiveLine } from './timelineDrag';

/** Size of the dragged item when lifted - the image under the pointer stays exactly as large. */
export interface DragFace {
  width: number | null;
  flipped: boolean;
  compact: boolean;
}

/**
 * The image under the pointer while dragging (c60bf914): the entry as large as it stood on the timeline
 * - chip, bar, bar entry or diamond -, below it the live line where it would land right now
 * (Flo: „beim Reinziehen … unten drunter Arbeitspaket und Datum“). Where it must not go, it says
 * so, in the warning colour.
 */
export function TimelineOverlay({ dragged, face, live, today }: {
  dragged: NonNullable<Dragged>;
  face: DragFace;
  live: LiveLine | null;
  today: number;
}) {
  return (
    <div className="relative" style={{ width: face.width ?? defaultWidth(dragged) }} data-testid="timeline-overlay">
      {dragged.kind === 'todo' && (
        <ChipFace todo={dragged.todo} overdue={isOverdue(dragged.todo, today)} flipped={face.flipped} compact={face.compact} lifted />
      )}
      {dragged.kind === 'package' && dragged.mode === 'move' && (
        <span className="flex w-full items-center overflow-hidden rounded-md border border-info/45 bg-info-subtle text-[11px] shadow-lg ring-1 ring-accent"
          style={{ height: BAR_HEIGHT }}>
          <span className="min-w-0 flex-1 truncate pl-2 font-medium text-text">{dragged.workPackage.title}</span>
          <span className="shrink-0 px-1.5 tabular-nums text-text-secondary">{dragged.workPackage.done}/{dragged.workPackage.total}</span>
        </span>
      )}
      {dragged.kind === 'package' && dragged.mode === 'schedule' && (
        <span className="flex h-7 w-full items-center gap-1.5 rounded-md border border-dashed border-border-strong bg-surface-raised px-2 text-xs shadow-lg ring-1 ring-accent">
          <WorkPackageMark state={dragged.workPackage.state} />
          <span className="min-w-0 truncate text-text">{dragged.workPackage.title}</span>
          {dragged.workPackage.openQuestions > 0 && <HelpCircle className="h-3 w-3 shrink-0 text-human" aria-hidden="true" />}
        </span>
      )}
      {dragged.kind === 'milestone' && (
        <span className="flex h-5 w-max max-w-[12rem] items-center rounded bg-surface-raised pr-1.5 shadow-lg ring-1 ring-accent">
          <span className="flex h-5 w-5 shrink-0 items-center justify-center"><MilestoneDiamond state={dragged.milestone.state} size="lg" /></span>
          <span className="min-w-0 truncate text-[11px] font-medium text-text">{dragged.milestone.title}</span>
        </span>
      )}
      {live && live.text && (
        <span data-testid="timeline-live" data-refused={live.refused ? 'true' : undefined}
          className={`absolute left-0 top-full mt-1 whitespace-nowrap rounded px-1.5 py-0.5 text-[11px] font-medium shadow ${
            live.refused ? 'border border-danger/40 bg-danger-subtle text-danger' : 'bg-accent text-text-inverse'}`}>
          {live.arrow ? `→ ${live.text}` : live.text}
        </span>
      )}
    </div>
  );
}

function defaultWidth(dragged: NonNullable<Dragged>): number | undefined {
  if (dragged.kind === 'todo') return CHIP_WIDTH;
  if (dragged.kind === 'milestone') return 2 * DIAMOND_ANCHOR;
  return undefined;
}
