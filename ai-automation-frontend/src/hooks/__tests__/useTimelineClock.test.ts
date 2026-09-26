import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useTimelineClock } from '../useTimelineClock';

let frames: FrameRequestCallback[] = [];

/** Runs the next requested frame at `now` milliseconds (the loop started at 0). */
function frame(now: number) {
  const next = frames.shift();
  expect(next).toBeDefined();
  act(() => next!(now));
}

function reduceMotion(reduce: boolean) {
  vi.mocked(window.matchMedia).mockImplementation((query: string) => ({
    matches: reduce && query.includes('reduce'), media: query, onchange: null,
    addListener: vi.fn(), removeListener: vi.fn(),
    addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
  }));
}

beforeEach(() => {
  frames = [];
  vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => frames.push(cb));
  vi.stubGlobal('cancelAnimationFrame', () => {});
  vi.spyOn(performance, 'now').mockReturnValue(0);
  reduceMotion(false);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('useTimelineClock', () => {
  it('plays right away and loops around the end of a pass', () => {
    const { result } = renderHook(() => useTimelineClock({ key: 'a', total: 2, running: true }));
    expect(result.current.playing).toBe(true);
    expect(result.current.elapsed).toBe(0);

    frame(500);
    expect(result.current.elapsed).toBeCloseTo(0.5);
    frame(2500);
    expect(result.current.elapsed).toBeCloseTo(0.5);
    expect(result.current.playing).toBe(true);
  });

  it('restarts at zero when the timeline changes', () => {
    const { result, rerender } = renderHook(
      ({ key }) => useTimelineClock({ key, total: 5, running: true }), { initialProps: { key: 'a' } });
    frame(1500);
    expect(result.current.elapsed).toBeCloseTo(1.5);

    rerender({ key: 'b' });
    expect(result.current.elapsed).toBe(0);
  });

  it('requests no frames while not running or paused', () => {
    const { result, rerender } = renderHook(
      ({ running }) => useTimelineClock({ key: 'a', total: 2, running }), { initialProps: { running: false } });
    expect(frames).toHaveLength(0);

    rerender({ running: true });
    expect(frames).toHaveLength(1);
    act(() => result.current.togglePlaying());
    expect(result.current.playing).toBe(false);
    frames = [];
    rerender({ running: true });
    expect(frames).toHaveLength(0);
  });

  it('under reduced motion waits for play, stops after one pass and replays from the start', () => {
    reduceMotion(true);
    const { result } = renderHook(() => useTimelineClock({ key: 'a', total: 2, running: true }));
    expect(result.current.playing).toBe(false);
    expect(frames).toHaveLength(0);

    act(() => result.current.togglePlaying());
    expect(result.current.playing).toBe(true);
    frame(1000);
    frame(3000);
    expect(result.current.elapsed).toBe(2);
    expect(result.current.playing).toBe(false);
    expect(frames).toHaveLength(0);

    act(() => result.current.togglePlaying());
    expect(result.current.elapsed).toBe(0);
    expect(result.current.playing).toBe(true);
  });
});
