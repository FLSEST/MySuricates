import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useDebounced, useFlushableDebounce } from '../useDebounced';

afterEach(() => { vi.useRealTimers(); });

describe('useDebounced', () => {
  it('follows the value after the wait', () => {
    vi.useFakeTimers();
    const { result, rerender } = renderHook(({ value }) => useDebounced(value, 250), { initialProps: { value: 'a' } });
    rerender({ value: 'ab' });
    act(() => { vi.advanceTimersByTime(249); });
    expect(result.current).toBe('a');
    act(() => { vi.advanceTimersByTime(1); });
    expect(result.current).toBe('ab');
  });
});

describe('useFlushableDebounce', () => {
  it('takes the current value at once on flush, and keeps debouncing later values', () => {
    vi.useFakeTimers();
    const { result, rerender } = renderHook(({ value }) => useFlushableDebounce(value, 250), { initialProps: { value: 'de' } });
    rerender({ value: 'dep' });
    expect(result.current.debounced).toBe('de');
    act(() => result.current.flush());
    expect(result.current.debounced).toBe('dep');

    rerender({ value: 'depl' });
    act(() => { vi.advanceTimersByTime(100); });
    expect(result.current.debounced).toBe('dep');
    act(() => { vi.advanceTimersByTime(150); });
    expect(result.current.debounced).toBe('depl');
  });
});
