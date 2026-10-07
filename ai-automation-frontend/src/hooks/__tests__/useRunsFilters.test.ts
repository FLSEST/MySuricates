import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useI18nStore } from '../../i18n';
import { RUNS_PAGE_SIZE, useRunsFilters } from '../useRunsFilters';

beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
});
afterEach(() => vi.useRealTimers());

describe('useRunsFilters', () => {
  it('starts on the workflows tab without filters', () => {
    const { result } = renderHook(() => useRunsFilters(true));
    expect(result.current.viewMode).toBe('workflows');
    expect(result.current.workflowQuery).toEqual({
      status: undefined, workflowId: undefined, projectId: undefined, search: undefined, page: 0, size: RUNS_PAGE_SIZE,
    });
    expect(result.current.chainQuery).toEqual({ status: undefined, search: undefined, page: 0, size: RUNS_PAGE_SIZE });
  });

  it('remembers the filters and the tab across page views', () => {
    const first = renderHook(() => useRunsFilters(true));
    act(() => first.result.current.workflow.setStatus('FAILED'));
    act(() => first.result.current.workflow.setProjectId('p1'));
    first.unmount();

    const { result } = renderHook(() => useRunsFilters(true));
    expect(result.current.workflow.status).toBe('FAILED');
    expect(result.current.workflowQuery).toMatchObject({ status: 'FAILED', projectId: 'p1' });
  });

  it('shows the workflows tab while the chains module is off, even with the chains tab remembered', () => {
    localStorage.setItem('runs-view-mode', JSON.stringify('chains'));
    expect(renderHook(() => useRunsFilters(false)).result.current.viewMode).toBe('workflows');
    expect(renderHook(() => useRunsFilters(true)).result.current.viewMode).toBe('chains');
  });

  it('debounces the search and then starts at the first page', () => {
    vi.useFakeTimers();
    const { result } = renderHook(() => useRunsFilters(true));
    act(() => result.current.workflow.setPage(3));
    act(() => result.current.workflow.setSearch('deploy'));
    expect(result.current.workflow.search).toBe('deploy');
    expect(result.current.workflowQuery.search).toBeUndefined();
    expect(result.current.workflowQuery.page).toBe(3);

    act(() => { vi.advanceTimersByTime(300); });
    expect(result.current.workflowQuery).toMatchObject({ search: 'deploy', page: 0 });

    act(() => result.current.chain.setPage(2));
    act(() => result.current.chain.setSearch('nightly'));
    act(() => { vi.advanceTimersByTime(299); });
    expect(result.current.chainQuery).toMatchObject({ search: undefined, page: 2 });
    act(() => { vi.advanceTimersByTime(1); });
    expect(result.current.chainQuery).toMatchObject({ search: 'nightly', page: 0 });
  });

  it('starts at the first page again after any filter change', () => {
    const { result } = renderHook(() => useRunsFilters(true));
    const workflowChanges = [
      () => result.current.workflow.setStatus('FAILED'),
      () => result.current.workflow.setWorkflowId('wf-1'),
      () => result.current.workflow.setProjectId('p1'),
    ];
    for (const change of workflowChanges) {
      act(() => result.current.workflow.setPage(3));
      act(change);
      expect(result.current.workflowQuery.page).toBe(0);
    }

    act(() => result.current.chain.setPage(2));
    act(() => result.current.chain.setStatus('RUNNING'));
    expect(result.current.chainQuery.page).toBe(0);
  });

  it('keeps a page chosen right after loading — only a changed search starts over', () => {
    vi.useFakeTimers();
    const { result } = renderHook(() => useRunsFilters(true));
    act(() => result.current.workflow.setPage(2));
    act(() => result.current.chain.setPage(1));
    act(() => { vi.advanceTimersByTime(300); });
    expect(result.current.workflowQuery.page).toBe(2);
    expect(result.current.chainQuery.page).toBe(1);

    // Typing and going back to the same text within the wait sends no new query either
    act(() => result.current.workflow.setSearch('x'));
    act(() => result.current.workflow.setSearch(''));
    act(() => { vi.advanceTimersByTime(300); });
    expect(result.current.workflowQuery).toMatchObject({ search: undefined, page: 2 });
  });

  it('clears the filters of the tab it leaves', () => {
    const { result } = renderHook(() => useRunsFilters(true));
    act(() => {
      result.current.workflow.setStatus('FAILED');
      result.current.workflow.setWorkflowId('wf-1');
      result.current.workflow.setProjectId('p1');
      result.current.workflow.setSearch('x');
      result.current.workflow.setPage(2);
    });

    act(() => result.current.changeTab('chains'));
    expect(result.current.viewMode).toBe('chains');
    expect(result.current.workflow).toMatchObject({ status: '', workflowId: '', projectId: '', search: '', page: 0 });
    expect(localStorage.getItem('runs-status-filter')).toBe('""');

    act(() => {
      result.current.chain.setStatus('RUNNING');
      result.current.chain.setSearch('y');
      result.current.chain.setPage(1);
    });
    act(() => result.current.changeTab('workflows'));
    expect(result.current.chain).toMatchObject({ status: '', search: '', page: 0 });
    expect(localStorage.getItem('runs-view-mode')).toBe('"workflows"');
  });

  it('offers the statuses in the app language', () => {
    const { result, rerender } = renderHook(() => useRunsFilters(true));
    expect(result.current.statusOptions.map((o) => o.label)).toEqual([
      'All Statuses', 'Completed', 'Completed with errors', 'Failed', 'Running', 'Cancelled', 'Paused', 'Pending', 'Interrupted',
    ]);

    act(() => useI18nStore.setState({ locale: 'de' }));
    rerender();
    expect(result.current.statusOptions.map((o) => o.label)).toEqual([
      'Alle Status', 'Abgeschlossen', 'Mit Fehlern abgeschlossen', 'Fehlgeschlagen', 'Läuft', 'Abgebrochen', 'Pausiert', 'Wartend', 'Unterbrochen',
    ]);
    expect(result.current.statusOptions.map((o) => o.value)).toEqual([
      '', 'COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'RUNNING', 'CANCELLED', 'PAUSED', 'PENDING', 'INTERRUPTED',
    ]);
  });
});
