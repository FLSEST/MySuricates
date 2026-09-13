import { describe, it, expect, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useSyncedState } from '../useSyncedState';

describe('useSyncedState', () => {
  it('should_derive_initial_state_from_first_source', () => {
    const { result } = renderHook(() => useSyncedState(3, (n) => n * 2));
    expect(result.current[0]).toBe(6);
  });

  it('should_pass_undefined_as_prev_on_first_render', () => {
    const derive = vi.fn((n: number, prev: number | undefined) => prev ?? n);
    renderHook(() => useSyncedState(1, derive));
    expect(derive).toHaveBeenCalledWith(1, undefined);
  });

  it('should_keep_local_edits_while_source_is_unchanged', () => {
    const { result, rerender } = renderHook(({ src }) => useSyncedState(src, (n) => n), {
      initialProps: { src: 1 },
    });
    act(() => result.current[1](42));
    rerender({ src: 1 });
    expect(result.current[0]).toBe(42);
  });

  it('should_re_derive_when_source_changes', () => {
    const { result, rerender } = renderHook(({ src }) => useSyncedState(src, (n) => n * 10), {
      initialProps: { src: 1 },
    });
    act(() => result.current[1](99));
    rerender({ src: 2 });
    expect(result.current[0]).toBe(20);
  });

  it('should_hand_previous_state_to_derive_on_source_change', () => {
    const { result, rerender } = renderHook(
      ({ src }) => useSyncedState<string, string | undefined>(src, (s, prev) => s ?? prev ?? 'fallback'),
      { initialProps: { src: 'a' as string | undefined } },
    );
    expect(result.current[0]).toBe('a');
    rerender({ src: undefined });
    expect(result.current[0]).toBe('a');
    rerender({ src: 'b' });
    expect(result.current[0]).toBe('b');
  });

  it('should_compare_source_by_identity_not_by_value', () => {
    const first = { v: 1 };
    const { result, rerender } = renderHook(({ src }) => useSyncedState(src, (o) => o.v), {
      initialProps: { src: first },
    });
    act(() => result.current[1](7));
    rerender({ src: first });
    expect(result.current[0]).toBe(7);
    rerender({ src: { v: 1 } });
    expect(result.current[0]).toBe(1);
  });

  it('should_not_re_derive_on_unrelated_rerenders', () => {
    const derive = vi.fn((n: number) => n);
    const { rerender } = renderHook(({ src }) => useSyncedState(src, derive), {
      initialProps: { src: 5 },
    });
    rerender({ src: 5 });
    rerender({ src: 5 });
    expect(derive).toHaveBeenCalledTimes(1);
  });
});
