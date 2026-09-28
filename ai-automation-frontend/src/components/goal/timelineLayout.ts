import type { GoalMilestone, GoalTodoItem, GoalWorkPackage, TodoStatus } from '../../types';

/**
 * Layout des Zeitstrahls eines Ziels (TODO e192df18, c60bf914), rein und testbar: Achse, Raster,
 * x ↔ Datum, oben die Meilensteine, je Projekt ein Band mit einer Zeile je Arbeitspaket (Balken von
 * Beginn bis Ende, fällige TODOs darunter), gestapelte TODO-Chips und was wohin abgelegt werden darf.
 *
 * Gerechnet wird in ganzen **Tagen** des lokalen Kalenders (`dayOf`), nicht in Millisekunden – so
 * verschiebt der Wechsel auf Sommer- oder Winterzeit nichts. Eine Fälligkeit und das Ende eines
 * Pakets gelten bis 23:59:59 Ortszeit (`dueDateOfDay`), ein Beginn ab 00:00 (`startDateOfDay`).
 */

export const DAY_MS = 86_400_000;
/** Breite eines TODO-Chips in px; das Stapeln rechnet damit. */
export const CHIP_WIDTH = 152;
/** Abstand vom Rand eines Chips zur Mitte seines Statuspunkts – der Punkt sitzt auf dem Datum. */
export const CHIP_ANCHOR = 12;
/** Höhe einer Spur für TODO-Chips (schmaler Chip plus Abstand). */
export const LANE_HEIGHT = 28;
/** Breite, solange keine gemessen ist (erster Render, Tests). */
export const FALLBACK_WIDTH = 960;
/** Schlüssel der Zeile „ohne Arbeitspaket“ in einem Band. */
export const NO_PACKAGE = 'none';
/** Schlüssel des Bands „ohne Projekt“ – Projekt-IDs sind UUIDs, das kollidiert nicht. */
export const NO_PROJECT_BAND = 'none';
/** Halbe Breite einer ziehbaren Raute: so weit liegt ihre Mitte (das Datum) vom linken Rand. */
export const DIAMOND_ANCHOR = 10;
/** Ein neues oder auf die Achse gezogenes Paket bekommt eine Woche: Beginn plus sechs Tage. */
export const WEEK_DAYS = 7;
/** Balken eines Pakets: Abstand oben und Höhe in px; darunter beginnen die Spuren der TODO-Chips. */
export const BAR_TOP = 9;
export const BAR_HEIGHT = 22;
export const BAR_ZONE = BAR_TOP + BAR_HEIGHT + 6;

const CHIP_GAP = 6;
const MIN_SPAN_DAYS = 14;
/** Ohne jedes Datum: heute bis in vier Wochen. */
const EMPTY_SPAN_DAYS = 28;
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

/** Ein Zeitraum in ganzen Tagen, beide einschließlich. */
export interface DayRange {
  start: number;
  end: number;
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

/** Eine Zeile im Band: ein Arbeitspaket (oder „ohne Arbeitspaket“) mit Balken und seinen TODOs. */
export interface PackageRow {
  /** ID des Pakets oder {@link NO_PACKAGE}. */
  key: string;
  /** Band (Projekt), zu dem die Zeile gehört. */
  band: string;
  workPackage: GoalWorkPackage | null;
  /** Zeitraum des Balkens; null ohne Zeitraum. */
  range: DayRange | null;
  /** Fällige TODOs, unter dem Balken gestapelt. */
  items: PlacedTodo[];
  /** Spuren für die Chips; 0 ohne fällige TODOs – dann bleibt die Zeile flach. */
  lanes: number;
  /** TODOs ohne Fälligkeit – eingeklappt als Zähler an der Beschriftung. */
  undated: GoalTodoItem[];
}

/** Ein Paket ohne Zeitraum in der Leiste – mit seinen TODOs ohne Datum, die sich dort aufklappen lassen. */
export interface UnscheduledPackage {
  workPackage: GoalWorkPackage;
  undated: GoalTodoItem[];
}

/** Ein Projektband: Kopfzeile, eine Zeile je Arbeitspaket, „ohne Arbeitspaket“ und die Leiste „ohne Zeitraum“. */
export interface TimelineBand {
  /** Projekt-ID oder {@link NO_PROJECT_BAND}. */
  key: string;
  /** Projektname; null = ohne Projekt. */
  name: string | null;
  /** Gehört zu den Projekten des Ziels – nur dann lassen sich darin Pakete anlegen. */
  linked: boolean;
  /** Pakete mit Zeitraum in ihrer Reihenfolge – dazu eins ohne Zeitraum nur, wenn schon eins seiner TODOs fällig ist. */
  rows: PackageRow[];
  /** TODOs des Projekts ohne Paket – die Zeile erscheint nur, wenn hier etwas steht (oder beim Ziehen). */
  noPackage: PackageRow;
  /** Pakete ohne Zeitraum, in ihrer Reihenfolge: die Leiste „ohne Zeitraum“. */
  unscheduled: UnscheduledPackage[];
}

/** Eine Raute in der Meilenstein-Zeile mit ihrem Titel daneben, in Spuren, damit nichts überlappt. */
export interface MilestoneMark {
  milestone: GoalMilestone;
  day: number;
  x: number;
  lane: number;
  /** Platz für den Titel neben der Raute. */
  labelWidth: number;
  /** Der Titel steht links der Raute – rechts am Rand wäre er abgeschnitten. */
  flipped: boolean;
}

/**
 * Wohin etwas fallen kann: auf die Achse einer Paket-Zeile (Tag und Paket), in ihre Beschriftung
 * (nur TODOs: Paket, Datum weg), in die Kopfzeile eines Bands (Tag), in die Leiste „ohne Zeitraum“
 * (nur Balken: Zeitraum weg), auf ein Paket dieser Leiste (nur TODOs: das Paket, der Tag bleibt) oder
 * in die Meilenstein-Zeile (nur Meilensteine).
 */
export type DropTarget =
  | { kind: 'row'; band: string; workPackageId: string | null }
  | { kind: 'undated'; band: string; workPackageId: string | null }
  | { kind: 'strip'; band: string }
  | { kind: 'tray'; band: string }
  | { kind: 'pill'; band: string; workPackageId: string }
  | { kind: 'milestones' };

/** Was ein Ablegen an einem TODO ändert: `dueDate` als ISO-Zeitpunkt (`''` leert), `workPackageId` (`''` nimmt es weg). */
export interface PlanChanges {
  dueDate?: string;
  workPackageId?: string;
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

/** Fälligkeit bzw. Ende eines Pakets: Ende des Tages, 23:59:59 Ortszeit, als ISO-Zeitpunkt. */
export function dueDateOfDay(day: number): string {
  const date = dateOfDay(day);
  date.setHours(23, 59, 59, 0);
  return date.toISOString();
}

/** Beginn eines Pakets: Anfang des Tages, 00:00 Ortszeit, als ISO-Zeitpunkt. */
export function startDateOfDay(day: number): string {
  return dateOfDay(day).toISOString();
}

/** Fällig vor heute und weder erledigt noch archiviert. */
export function isOverdue(todo: { dueDate: string | null; status: TodoStatus }, today: number): boolean {
  return !!todo.dueDate && todo.status !== 'DONE' && todo.status !== 'ARCHIVED' && dayOf(todo.dueDate) < today;
}

/** Zeitraum eines Pakets in Tagen – nur mit Beginn und Ende; ein verdrehter wird geordnet. */
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
 * Von min(heute, frühestes Datum) bis max(Zieldatum, spätestes Datum), mindestens zwei Wochen, plus
 * Rand. Ist gar nichts datiert, heute bis in vier Wochen. Das Raster richtet sich nach der Spanne je
 * Pixel: Tage, solange ein Tag breit genug für seine Zahl ist, sonst Wochen, sonst Monate.
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
 * Die Achse für ein Ziel: heute, Beginn und Ende seiner Pakete, seine Meilensteine, die
 * Fälligkeiten seiner TODOs und das Zieldatum.
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

/** Mitte der Spalte eines Tages, in px vom linken Rand der Zeitachse. */
export function xOfDay(axis: TimelineAxis, day: number, width: number): number {
  return (day - axis.startDay + 0.5) * (width / axis.days);
}

/** Der Tag unter `x` – eingerastet auf ganze Tage, außerhalb der Achse ihr erster bzw. letzter Tag. */
export function dayAtX(axis: TimelineAxis, x: number, width: number): number {
  const index = Math.floor(x / (width / axis.days));
  return axis.startDay + Math.min(axis.days - 1, Math.max(0, index));
}

/** Um wie viele ganze Tage eine Bewegung um `deltaX` px verschiebt. */
export function dayShift(axis: TimelineAxis, deltaX: number, width: number): number {
  return Math.round(deltaX / (width / axis.days));
}

/** Balken eines Zeitraums: vom Anfang der Spalte des ersten bis zum Ende der Spalte des letzten Tages. */
export function barBox(axis: TimelineAxis, range: DayRange, width: number): { left: number; width: number } {
  const perDay = width / axis.days;
  return { left: (range.start - axis.startDay) * perDay, width: (range.end - range.start + 1) * perDay };
}

/**
 * Was in den Balken passt – grob geschätzt (11 px Schrift): Titel und Stand, nur der Titel oder
 * nichts (dann steht der Titel daneben).
 */
export function barLabelFit(title: string, barWidth: number): 'full' | 'title' | 'none' {
  const text = title.length * 6.2;
  if (text + 44 <= barWidth) return 'full';
  return text + 16 <= barWidth ? 'title' : 'none';
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

// ── Meilensteine ──────────────────────────────────────────────────────────────

/** Höchstens so breit steht der Titel neben seiner Raute; längere werden gekürzt (mit Tooltip). */
const MILESTONE_LABEL_MAX = 150;

/**
 * Rauten der Meilensteine mit ihrem Titel rechts daneben, von links nach rechts in die erste Spur
 * gelegt, in der sie nichts überdecken. Passt der Titel rechts am Rand nicht und ist links mehr
 * Platz, klappt er nach links (`flipped`, c60bf914) – statt abgeschnitten zu werden.
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

/** Band eines TODOs bzw. Pakets: sein Projekt, sonst „ohne Projekt“. */
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
 * Die Bänder des Zeitstrahls (c60bf914): je Projekt des Ziels eins in dessen Reihenfolge, dahinter
 * Projekte, die nur über ein TODO oder Paket hereinkommen (Altdaten), zuletzt „ohne Projekt“, wenn
 * dort etwas steht. Je Paket mit Zeitraum eine Zeile, in der Reihenfolge der Pakete; Pakete ohne
 * Zeitraum warten in der Leiste (mit ihren TODOs ohne Datum) – eine Zeile bekommen sie nur, wenn
 * schon eins ihrer TODOs fällig ist. Ein TODO steht bei seinem Paket, sonst in „ohne Arbeitspaket“
 * seines Projekts – fällige unter dem Balken, die ohne Datum als Zähler an der Beschriftung.
 * Archivierte stehen nicht auf dem Zeitstrahl.
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
          // Hat das Paket schon eine Zeile, stehen seine TODOs ohne Datum dort – nicht doppelt
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
 * Ziel eines Ablegens aus der ID der Drop-Fläche: `row:<Band>:<Paket|none>`,
 * `undated:<Band>:<Paket|none>`, `strip:<Band>`, `tray:<Band>`, `pill:<Band>:<Paket>` oder `milestones`.
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

/** Flächen mit Tagen: Paket-Zeilen, Kopfzeilen der Bänder, die Meilenstein-Zeile. */
export function hasDays(target: DropTarget | null): boolean {
  return target?.kind === 'row' || target?.kind === 'strip' || target?.kind === 'milestones';
}

/** Das Band einer Fläche; null für die Meilenstein-Zeile, die dem ganzen Ziel gehört. */
export function bandOfTarget(target: DropTarget): string | null {
  return target.kind === 'milestones' ? null : target.band;
}

/**
 * Darf ein TODO hier landen? In Zeilen und auf Pakete nur seines eigenen Projekts – der Server wiese
 * alles andere ab. Ein Altfall (TODO in einem Paket eines anderen Projekts) darf in seinem Paket
 * bleiben und dort den Tag wechseln oder das Datum abgeben.
 */
export function todoAccepts(todo: Pick<GoalTodoItem, 'projectId' | 'workPackageId'>, target: DropTarget): boolean {
  if (target.kind !== 'row' && target.kind !== 'pill' && target.kind !== 'undated') return false;
  return target.band === bandOfTodo(todo) || (target.workPackageId !== null && target.workPackageId === todo.workPackageId);
}

/**
 * Ein Balken bleibt in seinem Band: auf einen Tag in einer Zeile oder der Kopfzeile verschoben bzw.
 * eingeplant, in die Leiste „ohne Zeitraum“ gezogen verliert er den Zeitraum.
 */
export function packageAccepts(item: Pick<GoalWorkPackage, 'projectId'>, target: DropTarget): boolean {
  if (target.kind !== 'row' && target.kind !== 'strip' && target.kind !== 'tray') return false;
  return target.band === bandOfPackage(item);
}

/** Meilensteine gehören dem ganzen Ziel: Jede Fläche mit Tagen nimmt sie – der Tag zählt, nicht die Zeile. */
export function milestoneAccepts(target: DropTarget): boolean {
  return hasDays(target);
}

/**
 * Was sich an einem TODO ändert, wenn es an `day` in `target` landet: ein anderer Tag setzt die
 * Fälligkeit, eine andere Zeile das Paket; ein Paket der Leiste nur das Paket; die Beschriftung einer
 * Zeile nimmt das Datum weg und setzt das Paket der Zeile.
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

/** Zeitraum um `shift` Tage verschoben – die Dauer bleibt. */
export function movedRange(range: DayRange, shift: number): DayRange {
  return { start: range.start + shift, end: range.end + shift };
}

/** Beginn oder Ende um `shift` Tage verschoben; nie über das andere Ende hinaus (mindestens ein Tag). */
export function resizedRange(range: DayRange, edge: 'start' | 'end', shift: number): DayRange {
  return edge === 'start'
    ? { start: Math.min(range.end, range.start + shift), end: range.end }
    : { start: range.start, end: Math.max(range.start, range.end + shift) };
}

/** Ein Paket ohne Zeitraum, an `day` auf die Achse gelegt: eine Woche ab diesem Tag. */
export function weekFrom(day: number): DayRange {
  return { start: day, end: day + WEEK_DAYS - 1 };
}

/** Beginn und Ende für den Server; ohne Zeitraum beide leer. */
export function rangeRequest(range: DayRange | null): { startDate: string; endDate: string } {
  return range ? { startDate: startDateOfDay(range.start), endDate: dueDateOfDay(range.end) } : { startDate: '', endDate: '' };
}

export const sameRange = (a: DayRange | null, b: DayRange | null) =>
  a === b || (!!a && !!b && a.start === b.start && a.end === b.end);

/** Die Änderungen in den TODO-Zeilen eines Ziels – für die optimistische Anzeige nach dem Ablegen. */
export function applyPlan(todos: GoalTodoItem[], todoId: string, changes: PlanChanges): GoalTodoItem[] {
  return todos.map((todo) => {
    if (todo.id !== todoId) return todo;
    const next = { ...todo };
    if (changes.dueDate !== undefined) next.dueDate = changes.dueDate || null;
    if (changes.workPackageId !== undefined) next.workPackageId = changes.workPackageId || null;
    return next;
  });
}
