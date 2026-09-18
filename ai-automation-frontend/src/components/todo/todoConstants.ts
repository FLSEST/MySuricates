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

export type ViewMode = 'list' | 'board';

export const priorityColor: Record<TodoPriority, string> = {
  CRITICAL: 'bg-red-700 dark:bg-red-600',
  HIGH: 'bg-danger',
  MEDIUM: 'bg-warning',
  LOW: 'bg-text-tertiary',
};

export const sourceIcon = (s: TodoSource) => {
  switch (s) {
    case 'USER': return createElement(User, { className: 'w-3 h-3' });
    case 'AI': return createElement(Bot, { className: 'w-3 h-3' });
    case 'WORKFLOW': return createElement(GitBranch, { className: 'w-3 h-3' });
  }
};

export function formatRelative(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.floor(hrs / 24);
  if (days < 30) return `${days}d ago`;
  return new Date(iso).toLocaleDateString();
}
