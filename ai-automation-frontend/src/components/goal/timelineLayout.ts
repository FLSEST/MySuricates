import type { DatedMilestone, GoalMilestone, GoalTodoItem, TodoStatus } from '../../types';

/**
 * Layout des Zeitstrahls eines Ziels (TODO e192df18), rein und testbar: Achse, Raster, x ↔ Datum,
 * je Projekt ein Band (c60bf914) mit einer Zeile je Teilziel, gestapelte TODO-Chips und was wohin
 * abgelegt werden darf.
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
/** Schlüssel der Zeile „ohne Teilziel“ in einem Band. */
export const NO_MILESTONE = 'none';
/** Schlüssel des Bands „ohne Projekt“ – Projekt-IDs sind UUIDs, das kollidiert nicht. */
export const NO_PROJECT_BAND = 'none';
/** Halbe Breite einer ziehbaren Raute im Band: so weit liegt ihre Mitte (das Datum) vom linken Rand. */
export const DIAMOND_ANCHOR = 10;

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
  /** ID des Teilziels oder {@link NO_MILESTONE}. */
  key: string;
  /** Band (Projekt), zu dem die Zeile gehört. */
  band: string;
  milestone: GoalMilestone | null;
  /** Fällige TODOs auf der Achse. */
  items: PlacedTodo[];
  lanes: number;
  /** TODOs ohne Fälligkeit – sie stehen links in der Zeile, im Bereich „ohne Datum“ ihres Teilziels. */
  undated: GoalTodoItem[];
}

/** Ein Projektband: Kopfzeile mit den Rauten, eine Zeile je Teilziel, „ohne Teilziel“, die Leiste „ohne Datum“. */
export interface TimelineBand {
  /** Projekt-ID oder {@link NO_PROJECT_BAND}. */
  key: string;
  /** Projektname; null = ohne Projekt. */
  name: string | null;
  /** Gehört zu den Projekten des Ziels – nur dann lassen sich darin Teilziele anlegen. */
  linked: boolean;
  /** Teilziele mit Datum, nach Datum: ihre Rauten stehen in der Kopfzeile. */
  dated: DatedMilestone[];
  /** Teilziele ohne Datum, nach Position: die Leiste „ohne Datum“. */
  undated: GoalMilestone[];
  /** Je datiertes Teilziel eine Zeile, je undatiertes mit TODOs auch, zuletzt „ohne Teilziel“. */
  rows: TimelineRow[];
}

/**
 * Wohin etwas fallen kann: auf die Achse einer Zeile (Tag und Teilziel), in den Bereich „ohne Datum“
 * einer Zeile (nur TODOs: Teilziel, Datum weg), in die Kopfzeile eines Bands (nur Rauten: ein
 * Datum), in die Leiste „ohne Datum“ (nur Rauten: Datum weg) oder auf ein Teilziel dieser Leiste
 * (nur TODOs: das Teilziel, der Tag bleibt).
 */
export type DropTarget =
  | { kind: 'row'; band: string; milestoneId: string | null }
  | { kind: 'undated'; band: string; milestoneId: string | null }
  | { kind: 'strip'; band: string }
  | { kind: 'tray'; band: string }
  | { kind: 'pill'; band: string; milestoneId: string };

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

/** Nur Teilziele mit Datum stehen als Raute auf dem Zeitstrahl. */
export function isDated(milestone: GoalMilestone): milestone is DatedMilestone {
  return milestone.dueDate !== null;
}

/** Die Achse für ein Ziel: heute, die Fälligkeiten seiner TODOs, seine datierten Teilziele und das Zieldatum. */
export function axisFor(input: {
  todos: GoalTodoItem[];
  milestones: GoalMilestone[];
  targetDate: string | null;
  today: number;
}, width: number): TimelineAxis {
  const days = [
    ...input.todos.filter((todo) => shownOnTimeline(todo) && todo.dueDate).map((todo) => dayOf(todo.dueDate!)),
    ...input.milestones.filter(isDated).map((milestone) => dayOf(milestone.dueDate)),
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

/** Wo das Etikett „heute“ steht: mittig über dem Tag, rechts oder links daneben, oder eine Zeile höher. */
export type TodayLabelPlacement = 'center' | 'right' | 'left' | 'raised';

/** Breite des Etiketts „heute“ (Text 10 px, Rand) und halbe Breite einer Raute oder der Zielfahne, in px. */
export const TODAY_LABEL_WIDTH = 44;
const MARK_HALF_WIDTH = 10;
/** So weit reicht ein seitlich stehendes Etikett noch über die Linie von heute. */
export const TODAY_LABEL_OVERLAP = 3;

/**
 * Das Etikett „heute“ weicht den Rauten und der Zielfahne aus (TODO ff6f08fd – bei 768 px lag es auf
 * der Raute des Nachbartags): mittig, wenn dort frei ist, sonst rechts oder links an der Linie von
 * heute, sonst eine Zeile höher bei den Tageszahlen. Es bleibt innerhalb der Achse.
 */
export function todayLabelPlacement(todayX: number, marks: number[], width: number): TodayLabelPlacement {
  const free = (from: number, to: number) => from >= 0 && to <= width
    && marks.every((x) => x + MARK_HALF_WIDTH <= from || x - MARK_HALF_WIDTH >= to);
  if (free(todayX - TODAY_LABEL_WIDTH / 2, todayX + TODAY_LABEL_WIDTH / 2)) return 'center';
  if (free(todayX - TODAY_LABEL_OVERLAP, todayX - TODAY_LABEL_OVERLAP + TODAY_LABEL_WIDTH)) return 'right';
  if (free(todayX + TODAY_LABEL_OVERLAP - TODAY_LABEL_WIDTH, todayX + TODAY_LABEL_OVERLAP)) return 'left';
  return 'raised';
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

/** Band eines TODOs bzw. Teilziels: sein Projekt, sonst „ohne Projekt“. */
export const bandOfTodo = (todo: Pick<GoalTodoItem, 'projectId'>) => todo.projectId ?? NO_PROJECT_BAND;
export const bandOfMilestone = (milestone: Pick<GoalMilestone, 'projectId'>) => milestone.projectId ?? NO_PROJECT_BAND;

function placeRow(
  key: string, band: string, milestone: GoalMilestone | null, todos: GoalTodoItem[], axis: TimelineAxis, width: number,
): TimelineRow {
  const dated = todos.filter((todo) => todo.dueDate);
  const byId = new Map(dated.map((todo) => [todo.id, todo]));
  const { slots, lanes } = stackChips(dated.map((todo) => ({ id: todo.id, x: xOfDay(axis, dayOf(todo.dueDate!), width) })), width);
  const items = slots.map((slot): PlacedTodo => {
    const todo = byId.get(slot.id)!;
    return { ...slot, todo, day: dayOf(todo.dueDate!) };
  });
  return { key, band, milestone, items, lanes, undated: todos.filter((todo) => !todo.dueDate) };
}

/**
 * Die Bänder des Zeitstrahls (c60bf914): je Projekt des Ziels eins in dessen Reihenfolge, dahinter
 * Projekte, die nur über ein TODO oder Teilziel hereinkommen (Altdaten), zuletzt „ohne Projekt“, wenn
 * dort etwas steht. Ein TODO steht in der Zeile seines Teilziels, sonst in „ohne Teilziel“ seines
 * Projekts; ein TODO, dessen Teilziel es nicht (mehr) gibt, ebenso. Fällige TODOs stehen auf der
 * Achse, TODOs ohne Fälligkeit links in derselben Zeile – so behalten sie ihre Zuordnung, auch wenn
 * (wie meist) niemand Daten vergibt. Archivierte stehen nicht auf dem Zeitstrahl.
 */
export function buildBands(
  todos: GoalTodoItem[], milestones: GoalMilestone[], projects: { projectId: string; name: string }[],
  axis: TimelineAxis, width: number,
): TimelineBand[] {
  const shown = todos.filter(shownOnTimeline);
  const milestoneById = new Map(milestones.map((milestone) => [milestone.id, milestone]));
  const rowOf = (todo: GoalTodoItem) => {
    const milestone = todo.milestoneId ? milestoneById.get(todo.milestoneId) : undefined;
    return milestone ? { band: bandOfMilestone(milestone), key: milestone.id } : { band: bandOfTodo(todo), key: NO_MILESTONE };
  };

  const names = new Map(projects.map((project) => [project.projectId, project.name]));
  const keys = [...projects.map((project) => project.projectId)];
  const addStray = (band: string, name: string | null) => {
    if (band === NO_PROJECT_BAND || keys.includes(band)) return;
    keys.push(band);
    names.set(band, name ?? band);
  };
  shown.forEach((todo) => addStray(rowOf(todo).band, todo.projectName));
  milestones.forEach((milestone) => addStray(bandOfMilestone(milestone), null));
  const hasNoProject = shown.some((todo) => rowOf(todo).band === NO_PROJECT_BAND)
    || milestones.some((milestone) => bandOfMilestone(milestone) === NO_PROJECT_BAND);
  if (hasNoProject) keys.push(NO_PROJECT_BAND);
  const linked = new Set(projects.map((project) => project.projectId));

  const bands = keys.map((band): TimelineBand => {
    const own = milestones.filter((milestone) => bandOfMilestone(milestone) === band);
    const bandDated = own.filter(isDated).sort((a, b) =>
      Date.parse(a.dueDate) - Date.parse(b.dueDate) || a.position - b.position || a.title.localeCompare(b.title));
    const undated = own.filter((milestone) => !isDated(milestone)).sort((a, b) => a.position - b.position);
    const inRow = (key: string) => shown.filter((todo) => {
      const row = rowOf(todo);
      return row.band === band && row.key === key;
    });
    const rows = [
      ...bandDated.map((milestone) => placeRow(milestone.id, band, milestone, inRow(milestone.id), axis, width)),
      ...undated.filter((milestone) => inRow(milestone.id).length > 0)
        .map((milestone) => placeRow(milestone.id, band, milestone, inRow(milestone.id), axis, width)),
      placeRow(NO_MILESTONE, band, null, inRow(NO_MILESTONE), axis, width),
    ];
    return { key: band, name: band === NO_PROJECT_BAND ? null : names.get(band) ?? band, linked: linked.has(band), dated: bandDated, undated, rows };
  });
  return bands;
}

// ── Ablegen ───────────────────────────────────────────────────────────────────

export const rowDropId = (band: string, key: string) => `row:${band}:${key}`;
export const stripDropId = (band: string) => `strip:${band}`;
export const trayDropId = (band: string) => `tray:${band}`;
export const pillDropId = (band: string, milestoneId: string) => `pill:${band}:${milestoneId}`;
export const undatedDropId = (band: string, key: string) => `undated:${band}:${key}`;

/**
 * Ziel eines Ablegens aus der ID der Drop-Fläche: `row:<Band>:<Teilziel|none>`,
 * `undated:<Band>:<Teilziel|none>`, `strip:<Band>`, `tray:<Band>` oder `pill:<Band>:<Teilziel>`.
 */
export function dropTargetOf(id: string): DropTarget | null {
  const [kind, band, key, ...rest] = id.split(':');
  if (!band || rest.length > 0) return null;
  if (kind === 'strip' && key === undefined) return { kind: 'strip', band };
  if (kind === 'tray' && key === undefined) return { kind: 'tray', band };
  if (kind === 'row' && key) return { kind: 'row', band, milestoneId: key === NO_MILESTONE ? null : key };
  if (kind === 'undated' && key) return { kind: 'undated', band, milestoneId: key === NO_MILESTONE ? null : key };
  if (kind === 'pill' && key) return { kind: 'pill', band, milestoneId: key };
  return null;
}

/**
 * Darf ein TODO hier landen? In Zeilen und auf Teilziele nur seines eigenen Projekts – der Server
 * wiese alles andere ab. Ein Altfall (TODO in einem Teilziel eines anderen Projekts) darf in seinem
 * Teilziel bleiben und dort den Tag wechseln oder das Datum abgeben.
 */
export function todoAccepts(todo: Pick<GoalTodoItem, 'projectId' | 'milestoneId'>, target: DropTarget): boolean {
  if (target.kind !== 'row' && target.kind !== 'pill' && target.kind !== 'undated') return false;
  return target.band === bandOfTodo(todo) || (target.milestoneId !== null && target.milestoneId === todo.milestoneId);
}

/** Eine Raute bleibt in ihrem Band: Kopfzeile oder Zeile setzt das Datum, die Leiste „ohne Datum“ nimmt es weg. */
export function milestoneAccepts(milestone: Pick<GoalMilestone, 'projectId'>, target: DropTarget): boolean {
  if (target.kind !== 'row' && target.kind !== 'strip' && target.kind !== 'tray') return false;
  return target.band === bandOfMilestone(milestone);
}

/**
 * Was sich ändert, wenn ein TODO an `day` in `target` landet: ein anderer Tag setzt die Fälligkeit,
 * eine andere Zeile das Teilziel; ein Teilziel der Leiste nur das Teilziel; der Bereich „ohne Datum“
 * einer Zeile nimmt das Datum weg und setzt das Teilziel der Zeile.
 */
export function planChanges(
  todo: { dueDate: string | null; milestoneId: string | null }, target: DropTarget, day: number | null,
): PlanChanges {
  if (target.kind === 'pill') return (todo.milestoneId ?? null) === target.milestoneId ? {} : { milestoneId: target.milestoneId };
  if (target.kind !== 'row' && target.kind !== 'undated') return {};
  const changes: PlanChanges = {};
  if (target.kind === 'undated') {
    if (todo.dueDate) changes.dueDate = '';
  } else if (day !== null && (!todo.dueDate || dayOf(todo.dueDate) !== day)) {
    changes.dueDate = dueDateOfDay(day);
  }
  if ((todo.milestoneId ?? null) !== target.milestoneId) changes.milestoneId = target.milestoneId ?? '';
  return changes;
}

/**
 * Das neue Datum einer abgelegten Raute als ISO-Zeitpunkt, `''` entfernt es; undefined, wenn sich
 * nichts ändert.
 */
export function milestoneDateChange(milestone: Pick<GoalMilestone, 'dueDate'>, target: DropTarget, day: number | null): string | undefined {
  if (target.kind === 'tray') return milestone.dueDate ? '' : undefined;
  if ((target.kind !== 'row' && target.kind !== 'strip') || day === null) return undefined;
  return milestone.dueDate && dayOf(milestone.dueDate) === day ? undefined : dueDateOfDay(day);
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
