import { describe, it, expect, afterEach, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useRunDiagramSync } from '../useRunDiagramSync';
import type { StepResult } from '../../../types';

afterEach(() => {
  vi.useRealTimers();
  document.body.innerHTML = '';
});

function step(stepIndex: number, status: string, blockIndex: number): StepResult {
  return { id: `s${stepIndex}`, stepIndex, status, metadata: { blockIndex } } as unknown as StepResult;
}

// A foreach at block 1 ran two inner steps: step indexes run ahead of block positions
const STEPS = [
  step(0, 'COMPLETED', 0),
  step(1, 'COMPLETED', 1),
  step(2, 'FAILED', 1),
  step(3, 'RUNNING', 2),
  step(4, 'SKIPPED', 3),
];

describe('useRunDiagramSync', () => {
  it('colours each block by its latest step and leaves other states out', () => {
    const { result } = renderHook(() => useRunDiagramSync(STEPS));
    expect([...result.current.statusByBlock]).toEqual([[0, 'completed'], [1, 'failed'], [2, 'running']]);
  });

  it('has nothing to colour without steps', () => {
    const { result } = renderHook(() => useRunDiagramSync(undefined));
    expect(result.current.statusByBlock.size).toBe(0);
  });

  it('scrolls to the step of a clicked box and highlights it briefly', () => {
    vi.useFakeTimers();
    const target = document.createElement('div');
    target.id = 'exec-step-2';
    target.scrollIntoView = vi.fn();
    document.body.appendChild(target);
    const { result } = renderHook(() => useRunDiagramSync(STEPS));

    act(() => result.current.selectStep({ blockIndex: 1 }));
    expect(target.scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'center' });
    expect(result.current.stepHighlight?.index).toBe(2);

    act(() => { vi.advanceTimersByTime(1800); });
    expect(result.current.stepHighlight).toBeNull();
  });

  it('ignores a box whose block has not run yet', () => {
    const { result } = renderHook(() => useRunDiagramSync(STEPS));
    act(() => result.current.selectStep({ blockIndex: 7 }));
    expect(result.current.stepHighlight).toBeNull();
  });
});
