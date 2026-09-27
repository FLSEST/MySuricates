import type { Locale, TranslationKeys } from '../../i18n';
import { formatDate, formatDateRange } from '../../utils/localeFormat';
import { dateOfDay, dayOf, hasDays, type DayRange, type DropTarget } from './timelineLayout';
import { acceptsTarget, bandOfDragged, type Dragged, type LiveLine } from './timelineDrag';

/**
 * Die Live-Zeile beim Ziehen (c60bf914, Flo: „beim Reinziehen … unten drunter Arbeitspaket und
 * Datum“): unter dem gezogenen Eintrag steht, wo er landet – ein TODO mit Arbeitspaket und Tag, ein
 * Paket mit seinem Zeitraum, ein Meilenstein mit seinem Tag. Wo es nicht hin darf, sagt die Zeile
 * das. Datumsangaben in der Sprache der Oberfläche.
 */

/** Ein Tag mit Wochentag: „Mo., 12.10.“ / „Mon, 10/12“. */
export function dayText(day: number, locale: Locale): string {
  return formatDate(dateOfDay(day), locale, { weekday: 'short', day: 'numeric', month: 'numeric' });
}

/** Ein Zeitraum, gemeinsame Teile nur einmal: „12.–18.10.“ / „10/12 – 10/18“; ein einzelner Tag als Datum. */
export function rangeText(range: DayRange, locale: Locale): string {
  const options = { day: 'numeric', month: 'numeric' } as const;
  return range.start === range.end
    ? formatDate(dateOfDay(range.start), locale, options)
    : formatDateRange(dateOfDay(range.start), dateOfDay(range.end), locale, options);
}

export interface LiveContext {
  t: TranslationKeys;
  locale: Locale;
  /** Titel eines Pakets. */
  packageTitle: (id: string) => string | undefined;
  /** Name eines Bands (Projekts). */
  bandName: (band: string) => string;
}

/**
 * Text der Live-Zeile. `day`: der Tag unter dem Zeiger (auf Flächen mit Tagen), `preview`: der
 * Zeitraum, den ein gezogenes Paket bekäme.
 */
export function liveLine(
  dragged: NonNullable<Dragged>, target: DropTarget | null, day: number | null, preview: DayRange | null, ctx: LiveContext,
): LiveLine {
  const tl = ctx.t.goals.timeline.live;
  if (dragged.kind === 'package' && (dragged.mode === 'start' || dragged.mode === 'end')) {
    return { text: preview ? rangeText(preview, ctx.locale) : '', refused: false };
  }
  if (!target) return { text: tl.outside, refused: true };
  if (!acceptsTarget(dragged, target)) {
    const band = bandOfDragged(dragged);
    return { text: band === null ? tl.outside : tl.foreign.replace('{project}', ctx.bandName(band)), refused: true };
  }
  if (dragged.kind === 'milestone') return { text: day !== null ? dayText(day, ctx.locale) : '', refused: false };
  if (dragged.kind === 'package') {
    if (target.kind === 'tray') return { text: tl.unscheduled, refused: false, arrow: true };
    return { text: preview ? rangeText(preview, ctx.locale) : '', refused: false };
  }
  const packageId = target.kind === 'row' || target.kind === 'undated' || target.kind === 'pill' ? target.workPackageId : null;
  const where = (packageId ? ctx.packageTitle(packageId) : undefined) ?? ctx.t.goals.timeline.noPackage;
  let when = tl.noDate;
  if (target.kind === 'pill' && dragged.todo.dueDate) when = dayText(dayOf(dragged.todo.dueDate), ctx.locale);
  else if (target.kind !== 'undated' && hasDays(target) && day !== null) when = dayText(day, ctx.locale);
  return { text: tl.target.replace('{target}', where).replace('{date}', when), refused: false, arrow: true };
}
