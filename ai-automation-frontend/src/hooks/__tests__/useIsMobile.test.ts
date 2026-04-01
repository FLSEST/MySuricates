import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useIsMobile } from '../useIsMobile';

function createMatchMedia(matches: boolean) {
  const listeners: Array<(e: MediaQueryListEvent) => void> = [];
  const mql = {
    matches,
    media: '(max-width: 1023px)',
    addEventListener: vi.fn((_event: string, handler: (e: MediaQueryListEvent) => void) => {
      listeners.push(handler);
    }),
    removeEventListener: vi.fn((_event: string, handler: (e: MediaQueryListEvent) => void) => {
      const idx = listeners.indexOf(handler);
      if (idx >= 0) listeners.splice(idx, 1);
    }),
    dispatchChange: (newMatches: boolean) => {
      for (const fn of listeners) fn({ matches: newMatches } as MediaQueryListEvent);
    },
  };
  return mql;
}

describe('useIsMobile', () => {
  let mql: ReturnType<typeof createMatchMedia>;

  beforeEach(() => {
    mql = createMatchMedia(false);
    window.matchMedia = vi.fn().mockReturnValue(mql) as any;
  });

  it('returns false on desktop', () => {
    const { result } = renderHook(() => useIsMobile());
    expect(result.current).toBe(false);
  });

  it('returns true on mobile', () => {
    mql = createMatchMedia(true);
    window.matchMedia = vi.fn().mockReturnValue(mql) as any;
    const { result } = renderHook(() => useIsMobile());
    expect(result.current).toBe(true);
  });

  it('updates when media query changes', () => {
    const { result } = renderHook(() => useIsMobile());
    expect(result.current).toBe(false);
    act(() => { mql.dispatchChange(true); });
    expect(result.current).toBe(true);
  });

  it('cleans up listener on unmount', () => {
    const { unmount } = renderHook(() => useIsMobile());
    unmount();
    expect(mql.removeEventListener).toHaveBeenCalled();
  });
});
