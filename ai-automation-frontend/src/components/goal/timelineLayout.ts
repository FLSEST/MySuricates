import type { GoalMilestone, GoalTodoItem, GoalWorkPackage, TodoStatus } from '../../types';

/**
 * Layout of a goal's timeline (TODO e192df18, c60bf914), pure and testable: axis, grid,
 * x ↔ date, milestones on top, one band per project with one row per work package (bar from
 * start to end, due TODOs below), stacked TODO chips and what may be dropped where.
 *
 * Calculation is in whole **days** of the local calendar (`dayOf`), not in milliseconds - so
 * the switch to summer or winter time shifts nothing. A due date and the end of a
 * package count until 23:59:59 local time (`dueDateOfDay`), a start from 00:00 (`startDateOfDay`).
 */

export const DAY_MS = 86_400_000;
/** Width of a TODO chip in px; the stacking calculates with it. */
export const CHIP_WIDTH = 152;
/** Distance from a chip's edge to the center of its status dot - the dot sits on the date. */
export const CHIP_ANCHOR = 12;
/** Height of a lane for TODO chips (narrow chip plus gap). */
export const LANE_HEIGHT = 28;
/** Width as long as none is measured (first render, tests). */
export const FALLBACK_WIDTH = 960;
/** Key of the "without work package" row in a band. */
export const NO_PACKAGE = 'none';
/** Key of the "without project" band - project IDs are UUIDs, so this does not collide. */
export const NO_PROJECT_BAND = 'none';
/** Half the width of a draggable diamond: that is how far its center (the date) is from the left edge. */
export const DIAMOND_ANCHOR = 10;
/** A new package or one dragged onto the axis gets a week: start plus six days. */
export const WEEK_DAYS = 7;
/** Bar of a package: offset from the top and height in px; below it begin the lanes of the TODO chips. */
export const BAR_TOP = 9;
export const BAR_HEIGHT = 22;
export const BAR_ZONE = BAR_TOP + BAR_HEIGHT + 6;

const CHIP_GAP = 6;
const MIN_SPAN_DAYS = 14;
/** Without any date: today until four weeks from now. */
const EMPTY_SPAN_DAYS = 28;
/** Smallest distance between two grid lines in px that can still be labeled legibly. */
const MIN_DAY_PX = 24;
const MIN_WEEK_PX = 36;

export type TimelineScale = 'day' | 'week' | 'month';

export interface TimelineAxis {
  /** First and last shown day (inclusive). */
  startDay: number;
  endDay: number;
  days: number;
  today: number;
  scale: TimelineScale;
  /** Days with a grid line and label: every day, every Monday or every first of the month. */
  ticks: number[];
  /** Start of the sections of the upper labeling: months above day/week, years above month. */
  bands: number[];
}

/** A period in whole days, both inclusive. */
export interface DayRange {
  start: number;
  end: number;
}

export interface ChipSlot {
  id: string;
  left: number;
  lane: number;
  /** Chip sits left of the date, dot at the right edge - otherwise it would run out of the timeline on the right. */
  flipped: boolean;
  /** Distance from the left edge of the chip to the date (center of the status dot). */
  anchor: number;
}

export interface PlacedTodo extends ChipSlot {
  todo: GoalTodoItem;
  day: number;
}

/** A row in the band: a work package (or "without work package") with bar and its TODOs. */
export interface PackageRow {
  /** ID of the package or {@link NO_PACKAGE}. */
  key: string;
  /** Band (project) the row belongs to. */
  band: string;
  workPackage: GoalWorkPackage | null;
  /** Period of the bar; null without a period. */
  range: DayRange | null;
  /** Due TODOs, stacked under the bar. */
  items: PlacedTodo[];
  /** Lanes for the chips; 0 without due TODOs - then the row stays flat. */
  lanes: number;
  /** TODOs without a due date - collapsed as a counter on the label. */
  undated: GoalTodoItem[];
}

/** A package without a period in the strip - with its TODOs without a date, which can be expanded there. */
export interface UnscheduledPackage {
  workPackage: GoalWorkPackage;
  undated: GoalTodoItem[];
}

/** A project band: header row, one row per work package, "without work package" and the "without period" strip. */
export interface TimelineBand {
  /** Project ID or {@link NO_PROJECT_BAND}. */
  key: string;
  /** Project name; null = no project. */
  name: string | null;
  /** Belongs to the goal's projects - only then can packages be created in it. */
  linked: boolean;
  /** Packages with a period in their order - plus one without a period only if one of its TODOs is already due. */
  rows: PackageRow[];
  /** TODOs of the project without a package - the row appears only if something is in it (or while dragging). */
  noPackage: PackageRow;
  /** Packages without a period, in their order: the „ohne Zeitraum“ strip. */
  unscheduled: UnscheduledPackage[];
}

/** A diamond in the milestone row with its title next to it, in lanes so that nothing overlaps. */
export interface MilestoneMark {
  milestone: GoalMilestone;
  day: number;
  x: number;
  lane: number;
  /** Space for the title next to the diamond. */
  labelWidth: number;
  /** The title sits left of the diamond - on the right edge it would be cut off. */
  flipped: boolean;
}

/**
 * Where something can fall: onto the axis of a package row (day and package), into its label
 * (TODOs only: package, date removed), into a band's header row (day), into the "without period" strip
 * (bars only: period removed), onto a package of this strip (TODOs only: the package, the day stays) or
 * into the milestone row (milestones only).
 */
export type DropTarget =
  | { kind: 'row'; band: string; workPackageId: string | null }
  | { kind: 'undated'; band: string; workPackageId: string | null }
  | { kind: 'strip'; band: string }
  | { kind: 'tray'; band: string }
  | { kind: 'pill'; band: string; workPackageId: string }
  | { kind: 'milestones' };

/** What a drop changes on a TODO: `dueDate` as ISO timestamp (`''` clears), `workPackageId` (`''` removes it). */
export interface PlanChanges {
  dueDate?: string;
  workPackageId?: string;
}

// ── Tage ──────────────────────────────────────────────────────────────────────

/** Day of the local calendar as an integer (days since 1970-01-01). */
export function dayOf(value: string | number | Date): number {
  const date = value instanceof Date ? value : new Date(value);
  return Math.round(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()) / DAY_MS);
}

/** Midnight (local time) of the day. */
export function dateOfDay(day: number): Date {
  const utc = new Date(day * DAY_MS);
  return new Date(utc.getUTCFullYear(), utc.getUTCMonth(), utc.getUTCDate());
}

/** `YYYY-MM-DD` – the value of an `<input type="date">`. */
export function isoDateOfDay(day: number): string {
  return new Date(day * DAY_MS).toISOString().slice(0, 10);
}

export function dayOfIsoDate(value: string): number | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return null;
  return Math.round(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])) / DAY_MS);
}

/** Due date or end of a package: end of the day, 23:59:59 local time, as ISO timestamp. */
export function dueDateOfDay(day: number): string {
  const date = dateOfDay(day);
  date.setHours(23, 59, 59, 0);
  return date.toISOString();
}

/** Start of a package: beginning of the day, 00:00 local time, as an ISO timestamp. */
export function startDateOfDay(day: number): string {
  return dateOfDay(day).toISOString();
}

/** Due before today and neither done nor archived. */
export function isOverdue(todo: { dueDate: string | null; status: TodoStatus }, today: number): boolean {
  return !!todo.dueDate && todo.status !== 'DONE' && todo.status !== 'ARCHIVED' && dayOf(todo.dueDate) < today;
}

/** Period of a package in days - only with start and end; a reversed one is put in order. */
export function packageRange(item: Pick<GoalWorkPackage, 'startDate' | 'endDate'>): DayRange | null {
  if (!item.startDate || !item.endDate) return null;
  const start = dayOf(item.startDate);
  const end = dayOf(item.endDate);
  return { start: Math.min(start, end), end: Math.max(start, end) };
}

// ── Achse ─────────────────────────────────────────────────────────────────────

const weekday = (day: number) => new Date(day * DAY_MS).getUTCDay();
const dayOfMonth = (day: number) => new Date(day * DAY_MS).getUTCDate();
const monthOf = (day: number) => new Date(day * DAY_MS).getUTCMonth();

function scaleFor(days: number, width: number): TimelineScale {
  const perDay = width / days;
  if (perDay >= MIN_DAY_PX) return 'day';
  if (perDay * 7 >= MIN_WEEK_PX) return 'week';
  return 'month';
}

function ticksFor(start: number, end: number, scale: TimelineScale): number[] {
  const ticks: number[] = [];
  for (let day = start; day <= end; day++) {
    if (scale === 'day' || (scale === 'week' && weekday(day) === 1) || (scale === 'month' && dayOfMonth(day) === 1)) {
      ticks.push(day);
    }
  }
  return ticks;
}

function bandsFor(start: number, end: number, scale: TimelineScale): number[] {
  const bands = [start];
  for (let day = start + 1; day <= end; day++) {
    if (dayOfMonth(day) === 1 && (scale !== 'month' || monthOf(day) === 0)) bands.push(day);
  }
  return bands;
}

/**
 * From min(today, earliest date) to max(goal date, latest date), at least two weeks, plus
 * margin. If nothing is dated, today until four weeks from now. The grid depends on the span per
 * pixel: days as long as a day is wide enough for its number, otherwise weeks, otherwise months.
 */
export function buildAxis(input: { today: number; days: number[]; target: number | null }, width: number): TimelineAxis {
  const nothingDated = input.days.length === 0 && input.target === null;
  const all = [input.today, ...input.days, ...(input.target !== null ? [input.target] : [])];
  const min = Math.min(...all);
  const max = Math.max(Math.max(...all), min + (nothingDated ? EMPTY_SPAN_DAYS : MIN_SPAN_DAYS) - 1);
  const margin = Math.max(2, Math.round((max - min + 1) * 0.05));
  const startDay = min - margin;
  const endDay = max + margin;
  const days = endDay - startDay + 1;
  const scale = scaleFor(days, width);
  return {
    startDay, endDay, days, today: input.today, scale,
    ticks: ticksFor(startDay, endDay, scale),
    bands: bandsFor(startDay, endDay, scale),
  };
}

const shownOnTimeline = (todo: GoalTodoItem) => todo.status !== 'ARCHIVED';

/**
 * The axis for a goal: today, start and end of its packages, its milestones, the
 * due dates of its TODOs and the goal date.
 */
export function axisFor(input: {
  todos: GoalTodoItem[];
  workPackages: GoalWorkPackage[];
  milestones: GoalMilestone[];
  targetDate: string | null;
  today: number;
}, width: number): TimelineAxis {
  const days = [
    ...input.todos.filter((todo) => shownOnTimeline(todo) && todo.dueDate).map((todo) => dayOf(todo.dueDate!)),
    ...input.workPackages.flatMap((item) => {
      const range = packageRange(item);
      return range ? [range.start, range.end] : [];
    }),
    ...input.milestones.map((milestone) => dayOf(milestone.dueDate)),
  ];
  return buildAxis({ today: input.today, days, target: input.targetDate ? dayOf(input.targetDate) : null }, width);
}

/** Center of a day's column, in px from the left edge of the time axis. */
export function xOfDay(axis: TimelineAxis, day: number, width: number): number {
  return (day - axis.startDay + 0.5) * (width / axis.days);
}

/** The day under `x` - snapped to whole days, outside the axis its first or last day. */
export function dayAtX(axis: TimelineAxis, x: number, width: number): number {
  const index = Math.floor(x / (width / axis.days));
  return axis.startDay + Math.min(axis.days - 1, Math.max(0, index));
}

/** By how many whole days a movement by `deltaX` px shifts. */
export function dayShift(axis: TimelineAxis, deltaX: number, width: number): number {
  return Math.round(deltaX / (width / axis.days));
}

/** Bar of a period: from the start of the first day's column to the end of the last day's column. */
export function barBox(axis: TimelineAxis, range: DayRange, width: number): { left: number; width: number } {
  const perDay = width / axis.days;
  return { left: (range.start - axis.startDay) * perDay, width: (range.end - range.start + 1) * perDay };
}

/**
 * What fits in the bar - roughly estimated (11 px font): title and status, only the title or
 * nothing (then the title sits next to it).
 */
export function barLabelFit(title: string, barWidth: number): 'full' | 'title' | 'none' {
  const text = title.length * 6.2;
  if (text + 44 <= barWidth) return 'full';
  return text + 16 <= barWidth ? 'title' : 'none';
}

/** Where the "heute" label sits: centered above the day, to the right or left of it, or one row higher. */
export type TodayLabelPlacement = 'center' | 'right' | 'left' | 'raised';

/** Width of the "heute" label (text 10 px, margin) and half width of a diamond or the goal flag, in px. */
export const TODAY_LABEL_WIDTH = 44;
const MARK_HALF_WIDTH = 10;
/** A label standing at the side still reaches this far beyond today's line. */
export const TODAY_LABEL_OVERLAP = 3;

/**
 * The "heute" label avoids the diamonds and the goal flag (TODO ff6f08fd - at 768 px it lay on
 * the neighboring day's diamond): centered if it is free there, otherwise right or left of today's
 * line, otherwise one row higher at the day numbers. It stays within the axis.
 */
export function todayLabelPlacement(todayX: number, marks: number[], width: number): TodayLabelPlacement {
  const free = (from: number, to: number) => from >= 0 && to <= width
    && marks.every((x) => x + MARK_HALF_WIDTH <= from || x - MARK_HALF_WIDTH >= to);
  if (free(todayX - TODAY_LABEL_WIDTH / 2, todayX + TODAY_LABEL_WIDTH / 2)) return 'center';
  if (free(todayX - TODAY_LABEL_OVERLAP, todayX - TODAY_LABEL_OVERLAP + TODAY_LABEL_WIDTH)) return 'right';
  if (free(todayX + TODAY_LABEL_OVERLAP - TODAY_LABEL_WIDTH, todayX + TODAY_LABEL_OVERLAP)) return 'left';
  return 'raised';
}

// ── Meilensteine ──────────────────────────────────────────────────────────────

/** The title sits at most this wide next to its diamond; longer ones are shortened (with tooltip). */
const MILESTONE_LABEL_MAX = 150;

/**
 * Diamonds of the milestones with their title to the right, placed from left to right in the first lane
 * where they cover nothing. If the title does not fit at the right edge and there is more room on the left,
 * it flips to the left (`flipped`, c60bf914) - instead of being cut off.
 */
export function milestoneMarks(milestones: GoalMilestone[], axis: TimelineAxis, width: number): { marks: MilestoneMark[]; lanes: number } {
  const laneEnds: number[] = [];
  const marks = [...milestones]
    .sort((a, b) => Date.parse(a.dueDate) - Date.parse(b.dueDate) || a.title.localeCompare(b.title))
    .map((milestone): MilestoneMark => {
      const day = dayOf(milestone.dueDate);
      const x = xOfDay(axis, day, width);
      const wanted = Math.min(MILESTONE_LABEL_MAX, milestone.title.length * 6.2 + 8);
      const right = width - x - DIAMOND_ANCHOR - 4;
      const left = x - DIAMOND_ANCHOR - 4;
      const flipped = wanted > right && left > right;
      const labelWidth = Math.max(0, Math.min(wanted, flipped ? left : right));
      const from = flipped ? x - DIAMOND_ANCHOR - labelWidth : x - DIAMOND_ANCHOR;
      const to = flipped ? x + DIAMOND_ANCHOR : x + DIAMOND_ANCHOR + labelWidth;
      let lane = laneEnds.findIndex((end) => end + 4 <= from);
      if (lane < 0) {
        lane = laneEnds.length;
        laneEnds.push(0);
      }
      laneEnds[lane] = to;
      return { milestone, day, x, lane, labelWidth, flipped };
    });
  return { marks, lanes: Math.max(1, laneEnds.length) };
}

// ── Zeilen ────────────────────────────────────────────────────────────────────

/**
 * Chips of a row, placed from left to right in the first free lane. The status dot sits on
 * the date; at the right edge the chip sits left of it (`flipped`).
 */
export function stackChips(items: { id: string; x: number }[], width: number): { slots: ChipSlot[]; lanes: number } {
  const laneEnds: number[] = [];
  const slots = [...items].sort((a, b) => a.x - b.x).map(({ id, x }): ChipSlot => {
    const flipped = x - CHIP_ANCHOR + CHIP_WIDTH > width && x + CHIP_ANCHOR - CHIP_WIDTH >= 0;
    const wanted = flipped ? x + CHIP_ANCHOR - CHIP_WIDTH : x - CHIP_ANCHOR;
    const left = Math.max(0, Math.min(wanted, width - CHIP_WIDTH));
    let lane = laneEnds.findIndex((end) => end + CHIP_GAP <= left);
    if (lane < 0) {
      lane = laneEnds.length;
      laneEnds.push(0);
    }
    laneEnds[lane] = left + CHIP_WIDTH;
    return { id, left, lane, flipped, anchor: x - left };
  });
  return { slots, lanes: Math.max(1, laneEnds.length) };
}

/** Band of a TODO or package: its project, otherwise "without project". */
export const bandOfTodo = (todo: Pick<GoalTodoItem, 'projectId'>) => todo.projectId ?? NO_PROJECT_BAND;
export const bandOfPackage = (item: Pick<GoalWorkPackage, 'projectId'>) => item.projectId ?? NO_PROJECT_BAND;

function placeRow(
  key: string, band: string, workPackage: GoalWorkPackage | null, todos: GoalTodoItem[], axis: TimelineAxis, width: number,
): PackageRow {
  const dated = todos.filter((todo) => todo.dueDate);
  const byId = new Map(dated.map((todo) => [todo.id, todo]));
  const { slots, lanes } = stackChips(dated.map((todo) => ({ id: todo.id, x: xOfDay(axis, dayOf(todo.dueDate!), width) })), width);
  const items = slots.map((slot): PlacedTodo => {
    const todo = byId.get(slot.id)!;
    return { ...slot, todo, day: dayOf(todo.dueDate!) };
  });
  return {
    key, band, workPackage,
    range: workPackage ? packageRange(workPackage) : null,
    items,
    lanes: dated.length === 0 ? 0 : lanes,
    undated: todos.filter((todo) => !todo.dueDate),
  };
}

/**
 * The timeline's bands (c60bf914): one per project of the goal in its order, after that
 * projects that come in only via a TODO or package (legacy data), last "without project" if
 * something is in it. One row per package with a period, in the order of the packages; packages without a
 * period wait in the strip (with their TODOs without a date) - they get a row only if
 * one of their TODOs is already due. A TODO sits at its package, otherwise in "without work package"
 * of its project - due ones under the bar, those without a date as a counter on the label.
 * Archived ones are not on the timeline.
 */
export function buildBands(
  todos: GoalTodoItem[], workPackages: GoalWorkPackage[], projects: { projectId: string; name: string }[],
  axis: TimelineAxis, width: number,
): TimelineBand[] {
  const shown = todos.filter(shownOnTimeline);
  const packageById = new Map(workPackages.map((item) => [item.id, item]));
  const rowOf = (todo: GoalTodoItem) => {
    const item = todo.workPackageId ? packageById.get(todo.workPackageId) : undefined;
    return item ? { band: bandOfPackage(item), key: item.id } : { band: bandOfTodo(todo), key: NO_PACKAGE };
  };

  const names = new Map(projects.map((project) => [project.projectId, project.name]));
  const keys = projects.map((project) => project.projectId);
  const addStray = (band: string, name: string | null) => {
    if (band === NO_PROJECT_BAND || keys.includes(band)) return;
    keys.push(band);
    names.set(band, name ?? band);
  };
  shown.forEach((todo) => addStray(rowOf(todo).band, todo.projectName));
  workPackages.forEach((item) => addStray(bandOfPackage(item), null));
  if (shown.some((todo) => rowOf(todo).band === NO_PROJECT_BAND)
    || workPackages.some((item) => bandOfPackage(item) === NO_PROJECT_BAND)) keys.push(NO_PROJECT_BAND);
  const linked = new Set(projects.map((project) => project.projectId));

  return keys.map((band): TimelineBand => {
    const inRow = (key: string) => shown.filter((todo) => {
      const row = rowOf(todo);
      return row.band === band && row.key === key;
    });
    const own = workPackages.filter((item) => bandOfPackage(item) === band)
      .map((item, index) => ({ item, index }))
      .sort((a, b) => a.item.position - b.item.position || a.index - b.index)
      .map(({ item }) => item);
    const rows = own
      .filter((item) => packageRange(item) !== null || inRow(item.id).some((todo) => todo.dueDate))
      .map((item) => placeRow(item.id, band, item, inRow(item.id), axis, width));
    const hasRow = new Set(rows.map((row) => row.key));
    return {
      key: band,
      name: band === NO_PROJECT_BAND ? null : names.get(band) ?? band,
      linked: linked.has(band),
      rows,
      noPackage: placeRow(NO_PACKAGE, band, null, inRow(NO_PACKAGE), axis, width),
      unscheduled: own.filter((item) => packageRange(item) === null)
        .map((workPackage) => ({
          workPackage,
          // If the package already has a row, its TODOs without a date are there - not twice
          undated: hasRow.has(workPackage.id) ? [] : inRow(workPackage.id).filter((todo) => !todo.dueDate),
        })),
    };
  });
}

// ── Ablegen ───────────────────────────────────────────────────────────────────

export const rowDropId = (band: string, key: string) => `row:${band}:${key}`;
export const undatedDropId = (band: string, key: string) => `undated:${band}:${key}`;
export const stripDropId = (band: string) => `strip:${band}`;
export const trayDropId = (band: string) => `tray:${band}`;
export const pillDropId = (band: string, workPackageId: string) => `pill:${band}:${workPackageId}`;
export const MILESTONES_DROP_ID = 'milestones';

/**
 * Target of a drop from the ID of the drop surface: `row:<band>:<package|none>`,
 * `undated:<Band>:<Paket|none>`, `strip:<Band>`, `tray:<Band>`, `pill:<Band>:<Paket>` or `milestones`.
 */
export function dropTargetOf(id: string): DropTarget | null {
  if (id === MILESTONES_DROP_ID) return { kind: 'milestones' };
  const [kind, band, key, ...rest] = id.split(':');
  if (!band || rest.length > 0) return null;
  if (kind === 'strip' && key === undefined) return { kind: 'strip', band };
  if (kind === 'tray' && key === undefined) return { kind: 'tray', band };
  if (kind === 'row' && key) return { kind: 'row', band, workPackageId: key === NO_PACKAGE ? null : key };
  if (kind === 'undated' && key) return { kind: 'undated', band, workPackageId: key === NO_PACKAGE ? null : key };
  if (kind === 'pill' && key) return { kind: 'pill', band, workPackageId: key };
  return null;
}

/** Surfaces with days: package rows, band header rows, the milestone row. */
export function hasDays(target: DropTarget | null): boolean {
  return target?.kind === 'row' || target?.kind === 'strip' || target?.kind === 'milestones';
}

/** The band of a surface; null for the milestone row, which belongs to the whole goal. */
export function bandOfTarget(target: DropTarget): string | null {
  return target.kind === 'milestones' ? null : target.band;
}

/**
 * May a TODO land here? In rows and on packages only of its own project - the server would reject
 * anything else. A legacy case (TODO in a package of another project) may stay in its package
 * and there change the day or give up the date.
 */
export function todoAccepts(todo: Pick<GoalTodoItem, 'projectId' | 'workPackageId'>, target: DropTarget): boolean {
  if (target.kind !== 'row' && target.kind !== 'pill' && target.kind !== 'undated') return false;
  return target.band === bandOfTodo(todo) || (target.workPackageId !== null && target.workPackageId === todo.workPackageId);
}

/**
 * A bar stays in its band: moved or scheduled to a day in a row or the header row,
 * dragged into the "without period" strip it loses the period.
 */
export function packageAccepts(item: Pick<GoalWorkPackage, 'projectId'>, target: DropTarget): boolean {
  if (target.kind !== 'row' && target.kind !== 'strip' && target.kind !== 'tray') return false;
  return target.band === bandOfPackage(item);
}

/** Milestones belong to the whole goal: every surface with days accepts them - the day counts, not the row. */
export function milestoneAccepts(target: DropTarget): boolean {
  return hasDays(target);
}

/**
 * What changes on a TODO when it lands on `day` in `target`: another day sets the
 * due date, another row the package; a package of the strip only the package; the label of a
 * row removes the date and sets the row's package.
 */
export function planChanges(
  todo: { dueDate: string | null; workPackageId: string | null }, target: DropTarget, day: number | null,
): PlanChanges {
  if (target.kind === 'pill') {
    return (todo.workPackageId ?? null) === target.workPackageId ? {} : { workPackageId: target.workPackageId };
  }
  if (target.kind !== 'row' && target.kind !== 'undated') return {};
  const changes: PlanChanges = {};
  if (target.kind === 'undated') {
    if (todo.dueDate) changes.dueDate = '';
  } else if (day !== null && (!todo.dueDate || dayOf(todo.dueDate) !== day)) {
    changes.dueDate = dueDateOfDay(day);
  }
  if ((todo.workPackageId ?? null) !== target.workPackageId) changes.workPackageId = target.workPackageId ?? '';
  return changes;
}

/** Period shifted by `shift` days – the duration stays. */
export function movedRange(range: DayRange, shift: number): DayRange {
  return { start: range.start + shift, end: range.end + shift };
}

/** Start or end shifted by `shift` days; never beyond the other end (at least one day). */
export function resizedRange(range: DayRange, edge: 'start' | 'end', shift: number): DayRange {
  return edge === 'start'
    ? { start: Math.min(range.end, range.start + shift), end: range.end }
    : { start: range.start, end: Math.max(range.start, range.end + shift) };
}

/** A package without a period, placed on the axis at `day`: a week from that day. */
export function weekFrom(day: number): DayRange {
  return { start: day, end: day + WEEK_DAYS - 1 };
}

/** Start and end for the server; without a period both empty. */
export function rangeRequest(range: DayRange | null): { startDate: string; endDate: string } {
  return range ? { startDate: startDateOfDay(range.start), endDate: dueDateOfDay(range.end) } : { startDate: '', endDate: '' };
}

export const sameRange = (a: DayRange | null, b: DayRange | null) =>
  a === b || (!!a && !!b && a.start === b.start && a.end === b.end);

/** The changes in a goal's TODO rows - for the optimistic display after dropping. */
export function applyPlan(todos: GoalTodoItem[], todoId: string, changes: PlanChanges): GoalTodoItem[] {
  return todos.map((todo) => {
    if (todo.id !== todoId) return todo;
    const next = { ...todo };
    if (changes.dueDate !== undefined) next.dueDate = changes.dueDate || null;
    if (changes.workPackageId !== undefined) next.workPackageId = changes.workPackageId || null;
    return next;
  });
}
