import { describe, it, expect, beforeEach } from 'vitest';
import { useToastStore } from '../toastStore';

describe('toastStore', () => {
  beforeEach(() => {
    useToastStore.setState({ toasts: [] });
  });

  it('starts with empty toasts', () => {
    expect(useToastStore.getState().toasts).toEqual([]);
  });

  it('addToast adds a toast with correct type and message', () => {
    useToastStore.getState().addToast('success', 'It worked!');
    const toasts = useToastStore.getState().toasts;
    expect(toasts).toHaveLength(1);
    expect(toasts[0].type).toBe('success');
    expect(toasts[0].message).toBe('It worked!');
  });

  it('addToast generates unique ids', () => {
    useToastStore.getState().addToast('info', 'First');
    useToastStore.getState().addToast('info', 'Second');
    const toasts = useToastStore.getState().toasts;
    expect(toasts[0].id).not.toBe(toasts[1].id);
  });

  it('addToast sets createdAt timestamp', () => {
    const before = Date.now();
    useToastStore.getState().addToast('error', 'Oops');
    const after = Date.now();
    const toast = useToastStore.getState().toasts[0];
    expect(toast.createdAt).toBeGreaterThanOrEqual(before);
    expect(toast.createdAt).toBeLessThanOrEqual(after);
  });

  it('addToast supports all types', () => {
    const types = ['success', 'error', 'info', 'warning'] as const;
    types.forEach((type) => useToastStore.getState().addToast(type, `msg-${type}`));
    const toasts = useToastStore.getState().toasts;
    expect(toasts.map((t) => t.type)).toEqual([...types]);
  });

  it('removeToast removes the correct toast', () => {
    useToastStore.getState().addToast('info', 'Keep');
    useToastStore.getState().addToast('error', 'Remove');
    const toasts = useToastStore.getState().toasts;
    useToastStore.getState().removeToast(toasts[1].id);
    const remaining = useToastStore.getState().toasts;
    expect(remaining).toHaveLength(1);
    expect(remaining[0].message).toBe('Keep');
  });

  it('removeToast with non-existent id is a no-op', () => {
    useToastStore.getState().addToast('info', 'Stay');
    useToastStore.getState().removeToast('non-existent');
    expect(useToastStore.getState().toasts).toHaveLength(1);
  });

  it('evicts oldest toast when exceeding MAX_TOASTS (5)', () => {
    for (let i = 0; i < 6; i++) {
      useToastStore.getState().addToast('info', `Toast ${i}`);
    }
    const toasts = useToastStore.getState().toasts;
    expect(toasts).toHaveLength(5);
    expect(toasts[0].message).toBe('Toast 1');
    expect(toasts[4].message).toBe('Toast 5');
  });

  it('never exceeds 5 toasts even with many additions', () => {
    for (let i = 0; i < 20; i++) {
      useToastStore.getState().addToast('info', `Toast ${i}`);
    }
    expect(useToastStore.getState().toasts).toHaveLength(5);
    expect(useToastStore.getState().toasts[4].message).toBe('Toast 19');
  });
});
