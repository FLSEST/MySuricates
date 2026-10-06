import { useMemo } from 'react';
import { useTodos, useTodoColumn } from './useTodos';
import { useSettingsStore } from '../stores/settingsStore';
import {
  ACTIVE_STATUSES, KANBAN_COLUMN_STATUSES, type TodoStatusFilter, type ViewMode,
} from '../components/todo/todoConstants';
import type { TodoKanbanColumn } from '../components/todo/TodoKanbanView';
import type { TodoStatus } from '../types';

interface TodoBoardInput {
  viewMode: ViewMode;
  statusFilter: TodoStatusFilter;
  /** Filters without the status — the board adds it per column. */
  filterParams: Record<string, string>;
  /** Filters with the status, for the list. */
  apiParams: Record<string, string | string[]>;
  page: number;
}

/**
 * Data of the TODO page: the list as one page (`useTodos`), the board with one query per
 * visible column. The status filter determines the columns; only the queries of the active view
 * run. Returns the visible TODOs, loading, error and `refetch` of the active view.
 */
export function useTodoBoard({ viewMode, statusFilter, filterParams, apiParams, page }: TodoBoardInput) {
  // Pagination — sizes are user settings, not constants (see TodoSettingsCard)
  const listPageSize = useSettingsStore((s) => s.listPageSize);
  const kanbanColumnSize = useSettingsStore((s) => s.kanbanColumnSize);

  const isBoard = viewMode === 'board';

  // Which columns the board shows — the status filter narrows them. ARCHIVED has no
  // permanent column; it only appears when the archive filter is selected explicitly.
  const boardStatuses = useMemo<TodoStatus[]>(() => {
    if (statusFilter === 'ALL') return KANBAN_COLUMN_STATUSES;
    if (statusFilter === 'ACTIVE') return ACTIVE_STATUSES;
    return [statusFilter];
  }, [statusFilter]);

  // One query per column — never distribute a mixed page across the columns (b12a1085).
  const columnEnabled = (status: TodoStatus) => isBoard && boardStatuses.includes(status);
  const openColumn = useTodoColumn('OPEN', kanbanColumnSize, filterParams, columnEnabled('OPEN'));
  const inProgressColumn = useTodoColumn('IN_PROGRESS', kanbanColumnSize, filterParams, columnEnabled('IN_PROGRESS'));
  const doneColumn = useTodoColumn('DONE', kanbanColumnSize, filterParams, columnEnabled('DONE'));
  const backlogColumn = useTodoColumn('BACKLOG', kanbanColumnSize, filterParams, columnEnabled('BACKLOG'));
  const archivedColumn = useTodoColumn('ARCHIVED', kanbanColumnSize, filterParams, columnEnabled('ARCHIVED'));
  const columnQueries = {
    OPEN: openColumn,
    IN_PROGRESS: inProgressColumn,
    DONE: doneColumn,
    BACKLOG: backlogColumn,
    ARCHIVED: archivedColumn,
  } satisfies Record<TodoStatus, ReturnType<typeof useTodoColumn>>;

  const boardColumns: TodoKanbanColumn[] = boardStatuses.map((status) => ({
    status,
    todos: columnQueries[status].data?.content ?? [],
    total: columnQueries[status].data?.totalElements ?? 0,
    isLoading: columnQueries[status].isLoading,
  }));

  const listQuery = useTodos(apiParams, page, listPageSize, !isBoard);
  const todosPage = listQuery.data;
  const isLoading = isBoard
    ? boardColumns.length > 0 && boardColumns.every((column) => column.isLoading)
    : listQuery.isLoading;
  const error = isBoard
    ? boardStatuses.map((status) => columnQueries[status].error).find(Boolean) ?? null
    : listQuery.error;
  const refetch = () => {
    if (isBoard) boardStatuses.forEach((status) => columnQueries[status].refetch());
    else listQuery.refetch();
  };

  // Todos currently on screen — list rows or the union of all board columns
  const todos = isBoard ? boardColumns.flatMap((column) => column.todos) : todosPage?.content;
  /** A board column still loading must not flash the empty state. */
  const boardStillLoading = isBoard && boardColumns.some((column) => column.isLoading);

  return { isBoard, boardColumns, todosPage, todos, isLoading, error, refetch, boardStillLoading };
}
