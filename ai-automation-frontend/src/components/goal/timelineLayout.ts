import type { GoalMilestone, GoalTodoItem, TodoStatus } from '../../types';

/**
 * Layout des Zeitstrahls eines Ziels (TODO e192df18), rein und testbar: Achse, Raster, x ↔ Datum,
 * Zeilen je Meilenstein und gestapelte TODO-Chips.
 *
 * Gerechnet wird in ganzen **Tagen** des lokalen Kalenders (`dayOf`), nicht in Millisekunden – so
 * verschiebt der Wechsel auf Sommer- oder Winterzeit nichts. Eine Fälligkeit gilt wie im TODO-Dialog
 * bis 23:59:59 Ortszeit (`dueDateOfDay`).
 */

export const DAY_MS = 86_400_000;
/** Breite eines TODO-Chips in px; das Stapeln rechnet damit. */
export const CHIP_WIDTH = 152;
/** Abstand vom Rand eines Chips zur Mitte seines Statuspunkts – der Punkt sitzt auf dem Datum. */
export const CHIP_ANCHOR = 12;
/** Höhe einer Spur in einer Zeile (Chip plus Abstand). */
export const LANE_HEIGHT = 32;
/** Breite, solange keine gemessen ist (erster Render, Tests). */
export const FALLBACK_WIDTH = 960;
/** Schlüssel der Zeile „ohne Meilenstein“. */
export const NO_MILESTONE = 'none';

const CHIP_GAP = 6;
const MIN_SPAN_DAYS = 14;
/** Kleinster Abstand zweier Rasterlinien in px, der noch lesbar beschriftet werden kann. */
const MIN_DAY_PX = 24;
const MIN_WEEK_PX = 36;

export type TimelineScale = 'day' | 'week' | 'month';

export interface TimelineAxis {
  /** Erster und letzter gezeigter Tag (einschließlich). */
  startDay: number;
  endDay: number;
  days: number;
  today: number;
  scale: TimelineScale;
  /** Tage mit Rasterlinie und Beschriftung: jeder Tag, jeder Montag oder jeder Monatserste. */
  ticks: number[];
  /** Beginn der Abschnitte der oberen Beschriftung: Monate über Tag/Woche, Jahre über Monat. */
  bands: number[];
}

export interface ChipSlot {
  id: string;
  left: number;
  lane: number;
  /** Chip steht links vom Datum, Punkt am rechten Rand – sonst liefe er rechts aus dem Zeitstrahl. */
  flipped: boolean;
  /** Abstand vom linken Rand des Chips zum Datum (Mitte des Statuspunkts). */
  anchor: number;
}

export interface PlacedTodo extends ChipSlot {
  todo: GoalTodoItem;
  day: number;
}

export interface TimelineRow {
  /** ID des Meilensteins oder {@link NO_MILESTONE}. */
  key: string;
  milestone: GoalMilestone | null;
  items: PlacedTodo[];
  lanes: number;
}

export type DropTarget = { kind: 'row'; milestoneId: string | null } | { kind: 'unplanned' };

/** Was ein Ablegen ändert: `dueDate` als ISO-Zeitpunkt (`''` leert), `milestoneId` (`''` nimmt ihn weg). */
export interface PlanChanges {
  dueDate?: string;
  milestoneId?: string;
}

// ── Tage ──────────────────────────────────────────────────────────────────────

/** Tag des lokalen Kalenders als ganze Zahl (Tage seit 1970-01-01). */
export function dayOf(value: string | number | Date): number {
  const date = value instanceof Date ? value : new Date(value);
  return Math.round(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()) / DAY_MS);
}

/** Mitternacht (Ortszeit) des Tages. */
export function dateOfDay(day: number): Date {
  const utc = new Date(day * DAY_MS);
  return new Date(utc.getUTCFullYear(), utc.getUTCMonth(), utc.getUTCDate());
}

/** `YYYY-MM-DD` – der Wert eines `<input type="date">`. */
export function isoDateOfDay(day: number): string {
  return new Date(day * DAY_MS).toISOString().slice(0, 10);
}

export function dayOfIsoDate(value: string): number | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return null;
  return Math.round(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])) / DAY_MS);
}

/** Fälligkeit wie im TODO-Dialog: Ende des Tages, 23:59:59 Ortszeit, als ISO-Zeitpunkt. */
export function dueDateOfDay(day: number): string {
  const date = dateOfDay(day);
  date.setHours(23, 59, 59, 0);
  return date.toISOString();
}

/** Fällig vor heute und weder erledigt noch archiviert. */
export function isOverdue(todo: { dueDate: string | null; status: TodoStatus }, today: number): boolean {
  return !!todo.dueDate && todo.status !== 'DONE' && todo.status !== 'ARCHIVED' && dayOf(todo.dueDate) < today;
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
 * Von min(heute, frühestes Datum) bis max(Zieldatum, spätestes Datum), mindestens zwei Wochen, plus
 * Rand. Das Raster richtet sich nach der Spanne je Pixel: Tage, solange ein Tag breit genug für seine
 * Zahl ist, sonst Wochen, sonst Monate.
 */
export function buildAxis(input: { today: number; days: number[]; target: number | null }, width: number): TimelineAxis {
  const all = [input.today, ...input.days, ...(input.target !== null ? [input.target] : [])];
  const min = Math.min(...all);
  const max = Math.max(Math.max(...all), min + MIN_SPAN_DAYS - 1);
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

/** Die Achse für ein Ziel: heute, die Fälligkeiten seiner TODOs, seine Meilensteine und das Zieldatum. */
export function axisFor(input: {
  todos: GoalTodoItem[];
  milestones: GoalMilestone[];
  targetDate: string | null;
  today: number;
}, width: number): TimelineAxis {
  const days = [
    ...input.todos.filter((todo) => shownOnTimeline(todo) && todo.dueDate).map((todo) => dayOf(todo.dueDate!)),
    ...input.milestones.map((milestone) => dayOf(milestone.dueDate)),
  ];
  return buildAxis({ today: input.today, days, target: input.targetDate ? dayOf(input.targetDate) : null }, width);
}

/** Mitte der Spalte eines Tages, in px vom linken Rand der Zeitachse. */
export function xOfDay(axis: TimelineAxis, day: number, width: number): number {
  return (day - axis.startDay + 0.5) * (width / axis.days);
}

/** Der Tag unter `x` – eingerastet auf ganze Tage, außerhalb der Achse ihr erster bzw. letzter Tag. */
export function dayAtX(axis: TimelineAxis, x: number, width: number): number {
  const index = Math.floor(x / (width / axis.days));
  return axis.startDay + Math.min(axis.days - 1, Math.max(0, index));
}

// ── Zeilen ────────────────────────────────────────────────────────────────────

/**
 * Chips einer Zeile, von links nach rechts in die erste freie Spur gelegt. Der Statuspunkt sitzt auf
 * dem Datum; am rechten Rand steht der Chip links davon (`flipped`).
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

/**
 * Eine Zeile je Meilenstein (nach Datum), dann „ohne Meilenstein“, jeweils mit den fälligen TODOs.
 * TODOs ohne Fälligkeit sind ungeplant; archivierte stehen nicht auf dem Zeitstrahl. Ein TODO, dessen
 * Meilenstein es nicht (mehr) gibt, landet in „ohne Meilenstein“.
 */
export function buildRows(todos: GoalTodoItem[], milestones: GoalMilestone[], axis: TimelineAxis, width: number):
  { rows: TimelineRow[]; unplanned: GoalTodoItem[] } {
  const shown = todos.filter(shownOnTimeline);
  const known = new Set(milestones.map((milestone) => milestone.id));
  const rowOf = (todo: GoalTodoItem) => (todo.milestoneId && known.has(todo.milestoneId) ? todo.milestoneId : NO_MILESTONE);
  const ordered = [...milestones].sort((a, b) =>
    Date.parse(a.dueDate) - Date.parse(b.dueDate) || a.title.localeCompare(b.title));

  const rows = [...ordered.map((milestone) => ({ key: milestone.id, milestone })), { key: NO_MILESTONE, milestone: null }]
    .map(({ key, milestone }): TimelineRow => {
      const dated = shown.filter((todo) => todo.dueDate && rowOf(todo) === key);
      const byId = new Map(dated.map((todo) => [todo.id, todo]));
      const { slots, lanes } = stackChips(dated.map((todo) => ({ id: todo.id, x: xOfDay(axis, dayOf(todo.dueDate!), width) })), width);
      const items = slots.map((slot): PlacedTodo => {
        const todo = byId.get(slot.id)!;
        return { ...slot, todo, day: dayOf(todo.dueDate!) };
      });
      return { key, milestone, items, lanes };
    });
  return { rows, unplanned: shown.filter((todo) => !todo.dueDate) };
}

// ── Ablegen ───────────────────────────────────────────────────────────────────

/** Ziel eines Ablegens aus der ID der Drop-Fläche: `row:<Meilenstein|none>` oder `unplanned`. */
export function dropTargetOf(id: string): DropTarget | null {
  if (id === 'unplanned') return { kind: 'unplanned' };
  if (!id.startsWith('row:')) return null;
  const key = id.slice('row:'.length);
  return { kind: 'row', milestoneId: key === NO_MILESTONE ? null : key };
}

export function rowDropId(key: string): string {
  return `row:${key}`;
}

/**
 * Was sich ändert, wenn ein TODO an `day` in `target` landet: ein anderer Tag setzt die Fälligkeit,
 * eine andere Zeile den Meilenstein; „ungeplant“ leert nur das Datum.
 */
export function planChanges(
  todo: { dueDate: string | null; milestoneId: string | null }, target: DropTarget, day: number | null,
): PlanChanges {
  if (target.kind === 'unplanned') return todo.dueDate ? { dueDate: '' } : {};
  const changes: PlanChanges = {};
  if (day !== null && (!todo.dueDate || dayOf(todo.dueDate) !== day)) changes.dueDate = dueDateOfDay(day);
  if ((todo.milestoneId ?? null) !== target.milestoneId) changes.milestoneId = target.milestoneId ?? '';
  return changes;
}

/** Die Änderungen in den TODO-Zeilen eines Ziels – für die optimistische Anzeige nach dem Ablegen. */
export function applyPlan(todos: GoalTodoItem[], todoId: string, changes: PlanChanges): GoalTodoItem[] {
  return todos.map((todo) => {
    if (todo.id !== todoId) return todo;
    const next = { ...todo };
    if (changes.dueDate !== undefined) next.dueDate = changes.dueDate || null;
    if (changes.milestoneId !== undefined) next.milestoneId = changes.milestoneId || null;
    return next;
  });
}
