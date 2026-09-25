import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, render, screen, fireEvent } from '@testing-library/react';
import { ToastContainer } from '../ToastContainer';
import { useToastStore } from '../../../stores/toastStore';

describe('ToastContainer', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    useToastStore.setState({ toasts: [] });
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('runs the action of a toast and closes it', () => {
    const onClick = vi.fn();
    render(<ToastContainer />);
    act(() => useToastStore.getState().addToast('success', 'Filed under Accepted', { label: 'Show', onClick }));

    fireEvent.click(screen.getByRole('button', { name: 'Show' }));
    act(() => { vi.advanceTimersByTime(250); });

    expect(onClick).toHaveBeenCalledTimes(1);
    expect(useToastStore.getState().toasts).toEqual([]);
  });

  it('shows only the close button on a toast without action', () => {
    render(<ToastContainer />);
    act(() => useToastStore.getState().addToast('info', 'Saved'));

    expect(screen.getByText('Saved')).toBeInTheDocument();
    expect(screen.getAllByRole('button')).toHaveLength(1);
  });

  it('keeps a toast with action longer than a plain one', () => {
    render(<ToastContainer />);
    act(() => {
      useToastStore.getState().addToast('info', 'Plain');
      useToastStore.getState().addToast('success', 'With action', { label: 'Show', onClick: () => {} });
    });

    act(() => { vi.advanceTimersByTime(4300); });
    expect(useToastStore.getState().toasts.map((toast) => toast.message)).toEqual(['With action']);

    act(() => { vi.advanceTimersByTime(4000); });
    expect(useToastStore.getState().toasts).toEqual([]);
  });
});
