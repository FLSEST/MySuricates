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

  it('offers several buttons below the message; each runs its own action and closes the toast', () => {
    const show = vi.fn();
    const undo = vi.fn();
    render(<ToastContainer />);
    act(() => useToastStore.getState().addToast('success', 'Added: AutoPilot · Repo', [
      { label: 'Show', onClick: show }, { label: 'Undo', onClick: undo },
    ]));

    expect(screen.getAllByRole('button').map((button) => button.textContent || button.getAttribute('aria-label')))
      .toEqual(['Show', 'Undo', 'Close']);
    fireEvent.click(screen.getByRole('button', { name: 'Undo' }));
    act(() => { vi.advanceTimersByTime(250); });

    expect(undo).toHaveBeenCalledTimes(1);
    expect(show).not.toHaveBeenCalled();
    expect(useToastStore.getState().toasts).toEqual([]);
  });

  it('shows only the close button on a toast without action', () => {
    render(<ToastContainer />);
    act(() => useToastStore.getState().addToast('info', 'Saved'));

    expect(screen.getByText('Saved')).toBeInTheDocument();
    expect(screen.getAllByRole('button')).toHaveLength(1);
  });

  const messages = () => useToastStore.getState().toasts.map((toast) => toast.message);
  const advance = (ms: number) => act(() => { vi.advanceTimersByTime(ms); });

  it('holds a toast with action while the pointer is on it and runs on for the rest', () => {
    render(<ToastContainer />);
    act(() => useToastStore.getState().addToast('success', 'Filed', { label: 'Show', onClick: () => {} }));
    advance(5000);

    fireEvent.mouseEnter(screen.getByTestId('toast'));
    advance(20_000);
    expect(messages()).toEqual(['Filed']);

    fireEvent.mouseLeave(screen.getByTestId('toast'));
    advance(2900);
    expect(messages()).toEqual(['Filed']);
    advance(400); // 3000 left of 8000, then the 200 ms exit
    expect(messages()).toEqual([]);
  });

  it('holds it while focus is inside, also across its buttons and when the pointer passes by', () => {
    render(<ToastContainer />);
    act(() => useToastStore.getState().addToast('success', 'Filed', { label: 'Show', onClick: () => {} }));
    advance(2000);

    act(() => screen.getByRole('button', { name: 'Show' }).focus());
    act(() => screen.getByRole('button', { name: 'Close' }).focus());
    fireEvent.mouseEnter(screen.getByTestId('toast'));
    fireEvent.mouseLeave(screen.getByTestId('toast'));
    advance(20_000);
    expect(messages()).toEqual(['Filed']);

    act(() => screen.getByRole('button', { name: 'Close' }).blur());
    advance(5900);
    expect(messages()).toEqual(['Filed']);
    advance(400); // 6000 left, then the exit
    expect(messages()).toEqual([]);
  });

  it('leaves a plain toast on its fixed time, pointer or not', () => {
    render(<ToastContainer />);
    act(() => useToastStore.getState().addToast('info', 'Saved'));

    fireEvent.mouseEnter(screen.getByTestId('toast'));
    advance(4300);

    expect(messages()).toEqual([]);
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
