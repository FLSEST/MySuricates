import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { usePersistedState } from '../usePersistedState';

describe('usePersistedState', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('returns default value when localStorage is empty', () => {
    const { result } = renderHook(() => usePersistedState('key', 'default'));
    expect(result.current[0]).toBe('default');
  });

  it('reads initial value from localStorage', () => {
    localStorage.setItem('key', JSON.stringify('saved'));
    const { result } = renderHook(() => usePersistedState('key', 'default'));
    expect(result.current[0]).toBe('saved');
  });

  it('persists value to localStorage on update', () => {
    const { result } = renderHook(() => usePersistedState('key', 'initial'));
    act(() => { result.current[1]('updated'); });
    expect(result.current[0]).toBe('updated');
    expect(JSON.parse(localStorage.getItem('key')!)).toBe('updated');
  });

  it('supports updater function', () => {
    const { result } = renderHook(() => usePersistedState('count', 0));
    act(() => { result.current[1]((prev) => prev + 1); });
    expect(result.current[0]).toBe(1);
    expect(JSON.parse(localStorage.getItem('count')!)).toBe(1);
  });

  it('handles non-JSON localStorage gracefully', () => {
    localStorage.setItem('bad', '{invalid');
    const { result } = renderHook(() => usePersistedState('bad', 'fallback'));
    expect(result.current[0]).toBe('fallback');
  });

  it('handles localStorage write errors gracefully', () => {
    const { result } = renderHook(() => usePersistedState('key', 'init'));
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError');
    });
    act(() => { result.current[1]('new'); });
    // State still updates even if persist fails
    expect(result.current[0]).toBe('new');
    vi.restoreAllMocks();
  });
});
