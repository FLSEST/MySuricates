import { describe, expect, it } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import type { SyncPreview, SyncPreviewItem } from '../../../types';
import { useSyncSelection } from '../useSyncSelection';

const item = (id: string, action: SyncPreviewItem['action']): SyncPreviewItem => ({ id, name: id, action });

const preview: SyncPreview = {
  projects: [item('p1', 'NEW'), item('p2', 'SKIP')],
  workflows: [item('w1', 'UPDATE'), item('w2', 'SKIP')],
  skills: [item('s1', 'SKIP')],
  chains: [item('c1', 'NEW')],
};

function setup(initial: SyncPreview | undefined) {
  return renderHook(({ p }) => useSyncSelection(p), { initialProps: { p: initial } });
}

describe('useSyncSelection', () => {
  it('selects every row that changes something once the preview is there', () => {
    const { result, rerender } = setup(undefined);
    expect(result.current.selectedCount).toBe(0);

    rerender({ p: preview });
    expect(result.current.selectedCount).toBe(3);
    expect(result.current.isSelected('project', 'p1')).toBe(true);
    expect(result.current.isSelected('project', 'p2')).toBe(false);
    expect(result.current.isSelected('workflow', 'w1')).toBe(true);
    expect(result.current.isSelected('chain', 'c1')).toBe(true);
    expect(result.current.sectionCount('project')).toBe('1/2');
    expect(result.current.sectionCount('skill')).toBe('0/1');
    expect(result.current.allSelected).toBe(false);
  });

  it('toggles single rows and all rows', () => {
    const { result } = setup(preview);
    act(() => result.current.toggle('skill', 's1'));
    expect(result.current.isSelected('skill', 's1')).toBe(true);
    act(() => result.current.toggle('project', 'p1'));
    expect(result.current.isSelected('project', 'p1')).toBe(false);

    act(() => result.current.toggleAll());
    expect(result.current.allSelected).toBe(true);
    expect(result.current.selectedCount).toBe(6);
    act(() => result.current.toggleAll());
    expect(result.current.selectedCount).toBe(0);
  });

  it('builds the request from the selection and leaves empty kinds out', () => {
    const { result } = setup(preview);
    expect(result.current.buildRequest()).toEqual({
      projectIds: ['p1'], workflowIds: ['w1'], skillIds: undefined, chainIds: ['c1'],
    });
    act(() => result.current.toggle('workflow', 'w2'));
    act(() => result.current.toggle('project', 'p1'));
    expect(result.current.buildRequest()).toEqual({
      projectIds: undefined, workflowIds: ['w1', 'w2'], skillIds: undefined, chainIds: ['c1'],
    });
  });

  it('copes with previews without chains', () => {
    const { result } = setup({ projects: [], workflows: [item('w1', 'NEW')], skills: [] });
    expect(result.current.sectionCount('chain')).toBe('0/0');
    expect(result.current.allSelected).toBe(true);
    expect(result.current.buildRequest()).toEqual({
      projectIds: undefined, workflowIds: ['w1'], skillIds: undefined, chainIds: undefined,
    });
  });

  it('collapses sections independently and starts over after reset', () => {
    const { result, rerender } = setup(preview);
    act(() => result.current.toggleSection('workflow'));
    expect(result.current.isCollapsed('workflow')).toBe(true);
    expect(result.current.isCollapsed('project')).toBe(false);
    act(() => result.current.toggle('skill', 's1'));

    // Closing clears everything; with the preview still loaded it selects anew from it
    act(() => result.current.reset());
    expect(result.current.isCollapsed('workflow')).toBe(false);
    expect(result.current.isSelected('skill', 's1')).toBe(false);
    expect(result.current.selectedCount).toBe(3);

    const next: SyncPreview = { ...preview, skills: [item('s1', 'UPDATE')] };
    rerender({ p: next });
    expect(result.current.isSelected('skill', 's1')).toBe(false);
  });
});
