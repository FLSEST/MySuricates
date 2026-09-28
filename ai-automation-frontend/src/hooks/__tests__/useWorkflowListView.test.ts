import { describe, it, expect, beforeEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useI18nStore } from '../../i18n';
import { useWorkflowListView } from '../useWorkflowListView';
import type { Workflow } from '../../types';

beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
});

const wf = (id: string, name: string, projectId: string, projectName: string, extra: Partial<Workflow> = {}) =>
  ({ id, name, projectId, projectName, description: null, ...extra }) as unknown as Workflow;

const list = [
  wf('w1', 'Deploy', 'pz', 'Zeta'),
  wf('w2', 'Lint', 'pa', 'Alpha', { sealed: true }),
  wf('w3', 'Report', 'pa', 'Alpha', { description: 'Weekly numbers' }),
];

describe('useWorkflowListView', () => {
  it('groups by project, sorted by project name', () => {
    const { result } = renderHook(() => useWorkflowListView(list));
    expect(result.current.groupedByProject.map((g) => [g.projectName, g.workflows.map((w) => w.id)])).toEqual([
      ['Alpha', ['w2', 'w3']], ['Zeta', ['w1']],
    ]);
  });

  it('searches name, project and description and combines it with the seal filter', () => {
    const { result } = renderHook(() => useWorkflowListView(list));
    expect(result.current.sealedCount).toBe(1);
    act(() => result.current.setSearch('weekly'));
    expect(result.current.filtered.map((w) => w.id)).toEqual(['w3']);
    act(() => result.current.setSearch('alpha'));
    expect(result.current.filtered.map((w) => w.id)).toEqual(['w2', 'w3']);
    act(() => result.current.setSealFilter('unsealed'));
    expect(result.current.filtered.map((w) => w.id)).toEqual(['w3']);
    act(() => result.current.setSealFilter('sealed'));
    expect(result.current.filtered.map((w) => w.id)).toEqual(['w2']);
  });

  it('collapses the projects of the first load and stores every change', () => {
    const { result } = renderHook(() => useWorkflowListView(list));
    expect(result.current.allCollapsed).toBe(true);
    expect(result.current.isProjectCollapsed('pa')).toBe(true);

    act(() => result.current.toggleProject('pa'));
    expect(result.current.isProjectCollapsed('pa')).toBe(false);
    expect(result.current.allCollapsed).toBe(false);
    expect(JSON.parse(localStorage.getItem('workflows-collapsed-projects')!)).toEqual(['pz']);

    act(() => result.current.toggleAll());
    expect(result.current.allCollapsed).toBe(true);
    act(() => result.current.toggleAll());
    expect(JSON.parse(localStorage.getItem('workflows-collapsed-projects')!)).toEqual([]);
  });

  it('falls back to the first match and forgets a deleted choice', () => {
    const { result } = renderHook(() => useWorkflowListView(list));
    expect(result.current.selectedWorkflow?.id).toBe('w1');

    act(() => result.current.setSelectedWorkflowId('w3'));
    expect(result.current.selectedWorkflow?.id).toBe('w3');
    expect(localStorage.getItem('workflows-selected-id')).toBe('w3');

    act(() => result.current.forgetSelection(['w2']));
    expect(result.current.selectedWorkflow?.id).toBe('w3');
    act(() => result.current.forgetSelection(['w3']));
    expect(result.current.selectedWorkflow?.id).toBe('w1');
    expect(localStorage.getItem('workflows-selected-id')).toBeNull();
  });

  it('restores row details, view mode and the chosen workflow from the last visit', () => {
    const first = renderHook(() => useWorkflowListView(list));
    act(() => first.result.current.toggleShowDetails());
    act(() => first.result.current.setViewMode('discovery'));
    act(() => first.result.current.setSelectedWorkflowId('w2'));
    expect(localStorage.getItem('workflows-show-row-details')).toBe('1');
    expect(localStorage.getItem('workflows-view-mode')).toBe('discovery');
    first.unmount();

    const { result } = renderHook(() => useWorkflowListView(list));
    expect(result.current.showDetails).toBe(true);
    expect(result.current.viewMode).toBe('discovery');
    expect(result.current.selectedWorkflow?.id).toBe('w2');
  });
});
