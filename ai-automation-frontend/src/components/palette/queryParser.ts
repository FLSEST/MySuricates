import type { RunStatus } from '../../types';

export interface ParsedQuery {
  /** Text without leading slash-tokens. */
  query: string;
  /** Active filter (MVP: run status only; extend per context as needed). */
  status: RunStatus | null;
}

const STATUS_ALIASES: Record<string, RunStatus> = {
  running: 'RUNNING',
  failed: 'FAILED',
  completed: 'COMPLETED',
  cancelled: 'CANCELLED',
  canceled: 'CANCELLED',
  paused: 'PAUSED',
  pending: 'PENDING',
  interrupted: 'INTERRUPTED',
};

/**
 * Parses palette input. `/failed dashboard` → { status: 'FAILED', query: 'dashboard' }.
 * Unknown slash tokens are left in the query (treated as regular text).
 * `/all` explicitly clears any status filter.
 */
export function parsePaletteQuery(raw: string): ParsedQuery {
  const input = raw.trim();
  if (!input.startsWith('/')) return { query: input, status: null };

  const firstSpace = input.indexOf(' ');
  const token = (firstSpace === -1 ? input.slice(1) : input.slice(1, firstSpace)).toLowerCase();
  const rest = firstSpace === -1 ? '' : input.slice(firstSpace + 1).trim();

  if (token === 'all') return { query: rest, status: null };
  const mapped = STATUS_ALIASES[token];
  if (mapped) {
    return { query: rest, status: mapped };
  }
  // Unknown slash token — preserve the full input so the user is not surprised.
  return { query: input, status: null };
}
