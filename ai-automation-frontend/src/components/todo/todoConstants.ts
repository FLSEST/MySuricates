import { User, Bot, GitBranch } from 'lucide-react';
import { createElement } from 'react';
import type { TodoStatus, TodoPriority, TodoSource } from '../../types';

export const STATUSES: TodoStatus[] = ['OPEN', 'IN_PROGRESS', 'DONE', 'BACKLOG'];

/** Status filter of the todos page: a single status, everything, or the active work (OPEN + IN_PROGRESS). */
export type TodoStatusFilter = TodoStatus | 'ALL' | 'ACTIVE';

/** What "ACTIVE" means — the default filter: what is actually being worked on. */
export const ACTIVE_STATUSES: TodoStatus[] = ['OPEN', 'IN_PROGRESS'];

/** Columns of the board. ARCHIVED deliberately has no permanent column — it only
 *  appears when the archive filter is selected explicitly. */
export const KANBAN_COLUMN_STATUSES: TodoStatus[] = ['OPEN', 'IN_PROGRESS', 'DONE', 'BACKLOG'];

/** Would the current filter show a todo with this status? (deep links release filters that hide their target) */
export function filterShowsStatus(filter: TodoStatusFilter, status: TodoStatus): boolean {
  if (filter === 'ALL') return true;
  if (filter === 'ACTIVE') return ACTIVE_STATUSES.includes(status);
  return filter === status;
}

export const PRIORITIES: TodoPriority[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
export const SOURCES: TodoSource[] = ['USER', 'AI', 'WORKFLOW'];

export const MAX_VISIBLE_TAGS = 8;

/**
 * Search entry on the todos page waits this long after the last keystroke before it queries.
 * Long enough that a typed word produces one request instead of one per character, short
 * enough to still feel immediate — the free-text filter runs a LIKE over title and description
 * server-side, so every keystroke would be a real query.
 */
export const SEARCH_DEBOUNCE_MS = 300;

/**
 * How many active todos the cockpit shows per expanded project card. A preview, not the list:
 * the full set is one click away on the todos page, and a project with dozens of open todos
 * must not stretch the card (or the query) without bound.
 */
export const PROJECT_ACTIVE_TODOS_PREVIEW_SIZE = 12;

/**
 * How long that preview stays fresh. Expanding and collapsing cards must not re-query on every
 * toggle; todos change on a human timescale, so half a minute of staleness is invisible here.
 */
export const PROJECT_ACTIVE_TODOS_STALE_MS = 30_000;

export type ViewMode = 'list' | 'board';

/** The one place for priority colours: marker classes (list, board, dashboard, goals …). */
export const priorityColor: Record<TodoPriority, string> = {
  CRITICAL: 'bg-danger ring-2 ring-danger/40',
  HIGH: 'bg-danger',
  MEDIUM: 'bg-warning',
  LOW: 'bg-text-tertiary',
};

/** The same tones as CSS values, for SVG fills and inline styles (relation map, simple mode). */
export const priorityFill: Record<TodoPriority, string> = {
  CRITICAL: 'var(--color-danger)',
  HIGH: 'var(--color-danger)',
  MEDIUM: 'var(--color-warning)',
  LOW: 'var(--color-text-tertiary)',
};

export const sourceIcon = (s: TodoSource) => {
  switch (s) {
    case 'USER': return createElement(User, { className: 'w-3 h-3' });
    case 'AI': return createElement(Bot, { className: 'w-3 h-3' });
    case 'WORKFLOW': return createElement(GitBranch, { className: 'w-3 h-3' });
  }
};

