import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useToast } from '../useToast';
import { useToastStore } from '../../stores/toastStore';

describe('useToast', () => {
  beforeEach(() => {
    useToastStore.setState({ toasts: [] });
  });

  it('adds a success toast', () => {
    const { result } = renderHook(() => useToast());
    act(() => { result.current.success('Done!'); });
    const toasts = useToastStore.getState().toasts;
    expect(toasts).toHaveLength(1);
    expect(toasts[0].type).toBe('success');
    expect(toasts[0].message).toBe('Done!');
  });

  it('adds an error toast', () => {
    const { result } = renderHook(() => useToast());
    act(() => { result.current.error('Failed'); });
    const toasts = useToastStore.getState().toasts;
    expect(toasts).toHaveLength(1);
    expect(toasts[0].type).toBe('error');
  });

  it('adds an info toast', () => {
    const { result } = renderHook(() => useToast());
    act(() => { result.current.info('FYI'); });
    const toasts = useToastStore.getState().toasts;
    expect(toasts[0].type).toBe('info');
  });

  it('adds a warning toast', () => {
    const { result } = renderHook(() => useToast());
    act(() => { result.current.warning('Careful'); });
    const toasts = useToastStore.getState().toasts;
    expect(toasts[0].type).toBe('warning');
  });

  it('evicts oldest when exceeding max toasts', () => {
    const { result } = renderHook(() => useToast());
    act(() => {
      for (let i = 0; i < 6; i++) {
        result.current.info(`msg${i}`);
      }
    });
    const toasts = useToastStore.getState().toasts;
    expect(toasts).toHaveLength(5);
    expect(toasts[0].message).toBe('msg1');
  });
});
