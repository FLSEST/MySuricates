const LEVEL_COLOR: Record<string, string> = {
  ERROR: 'var(--color-danger)', WARN: 'var(--color-warning)', INFO: 'var(--color-info)', DEBUG: 'var(--color-text-tertiary)',
};

/** Color for an activity log level. Pure. */
export function levelColor(level: string): string {
  return LEVEL_COLOR[level] ?? 'var(--color-text-tertiary)';
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
