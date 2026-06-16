const LEVEL_COLOR: Record<string, string> = {
  ERROR: '#fb7185', WARN: '#fbbf24', INFO: '#38bdf8', DEBUG: '#5b6678',
};

/** Color for an activity log level. Pure. */
export function levelColor(level: string): string {
  return LEVEL_COLOR[level] ?? '#5b6678';
}

/** Compact, language-neutral relative time: "<1m", "15m", "3h", "2d". Pure. */
export function relativeTime(iso: string, now: Date): string {
  const m = Math.floor((now.getTime() - new Date(iso).getTime()) / 60000);
  if (m < 1) return '<1m';
  if (m < 60) return `${m}m`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h`;
  return `${Math.floor(h / 24)}d`;
}
