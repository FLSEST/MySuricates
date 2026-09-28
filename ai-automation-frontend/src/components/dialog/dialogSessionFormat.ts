import type { Locale } from '../../i18n';
import { formatDate } from '../../utils/localeFormat';

/** Dauer einer Session für die Karte: „<1 min“, „12 min“, „1h 5min“; fehlt ein Zeitpunkt: „?“. */
export function formatDuration(start: string | null, end: string | null): string {
  if (!start || !end) return '?';
  const ms = new Date(end).getTime() - new Date(start).getTime();
  const mins = Math.floor(ms / 60000);
  if (mins < 1) return '<1 min';
  if (mins < 60) return `${mins} min`;
  return `${Math.floor(mins / 60)}h ${mins % 60}min`;
}

/** Startzeit einer Session kurz in der App-Sprache (Monat, Tag, Uhrzeit). */
export function formatSessionStart(iso: string | null, locale: Locale): string {
  if (!iso) return '?';
  return formatDate(iso, locale, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

/** Letztes Pfadsegment eines Projektpfads (der Ordnername). */
export function projectShortName(path: string): string {
  if (!path) return '?';
  const parts = path.split('/');
  return parts[parts.length - 1] || path;
}
