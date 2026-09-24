import { describe, expect, it, vi } from 'vitest';
import { act, fireEvent, renderHook } from '@testing-library/react';
import type { PaletteItem } from '../contexts/types';
import { usePaletteSelection, type PaletteKeyActions } from '../usePaletteSelection';

const item = (id: string): PaletteItem => ({ id, title: id, raw: null });

function setup(overrides: { isEmptyQuery?: boolean; pinnedItems?: PaletteItem[]; recentItems?: PaletteItem[];
  results?: PaletteItem[]; hasAssistantRow?: boolean; keys?: Partial<PaletteKeyActions> } = {}) {
  const keys: PaletteKeyActions = {
    close: vi.fn(), cycleContext: vi.fn(), enter: vi.fn(), open: vi.fn(), openInNewTab: vi.fn(),
    askAssistant: vi.fn(), collecting: false, cancelCollecting: vi.fn(), ...overrides.keys,
  };
  const props = {
    isEmptyQuery: false, pinnedItems: [], recentItems: [], results: [item('a'), item('b')], hasAssistantRow: true,
    ...overrides, keys,
  };
  const hook = renderHook((p) => usePaletteSelection(p), { initialProps: props });
  return { ...hook, keys, props };
}

/** Fires on the window like a real keystroke; returns false when the palette prevented the default. */
function press(key: string, init: KeyboardEventInit = {}) {
  let notPrevented = true;
  act(() => { notPrevented = fireEvent.keyDown(window, { key, ...init }); });
  return notPrevented;
}

describe('usePaletteSelection', () => {
  it('moves over the results and the assistant row without leaving the list', () => {
    const { result } = setup();
    expect(press('ArrowUp')).toBe(false);
    expect(result.current.selectedIdx).toBe(0);
    for (let i = 0; i < 5; i++) press('ArrowDown');
    expect(result.current.selectedIdx).toBe(2);
    expect(result.current.assistantIdx).toBe(2);
    expect(result.current.itemAt(2)).toBeNull();
  });

  it('runs, opens or opens in a new tab the selected row and asks the assistant on its row', () => {
    const { result, keys } = setup();
    press('ArrowDown');
    press('Enter');
    expect(keys.enter).toHaveBeenCalledWith(expect.objectContaining({ id: 'b' }));
    press('Enter', { shiftKey: true });
    expect(keys.open).toHaveBeenCalledWith(expect.objectContaining({ id: 'b' }));
    press('Enter', { metaKey: true });
    press('Enter', { ctrlKey: true });
    expect(keys.openInNewTab).toHaveBeenCalledTimes(2);

    act(() => result.current.select(2));
    press('Enter');
    expect(keys.askAssistant).toHaveBeenCalledTimes(1);
    expect(keys.enter).toHaveBeenCalledTimes(1);
  });

  it('counts pinned before recent rows for an empty query and jumps with Alt+N', () => {
    const { result, keys } = setup({
      isEmptyQuery: true, pinnedItems: [item('p')], recentItems: [item('r1'), item('r2')], results: [], hasAssistantRow: false,
    });
    expect(result.current.itemAt(0)?.id).toBe('p');
    expect(result.current.itemAt(2)?.id).toBe('r2');
    expect(press('3', { altKey: true })).toBe(false);
    expect(result.current.selectedIdx).toBe(2);
    expect(keys.enter).toHaveBeenCalledWith(expect.objectContaining({ id: 'r2' }));

    press('9', { altKey: true });
    expect(result.current.selectedIdx).toBe(2);
    expect(keys.enter).toHaveBeenCalledTimes(1);
    for (let i = 0; i < 5; i++) press('ArrowDown');
    expect(result.current.selectedIdx).toBe(2);
  });

  it('asks the assistant with Alt+N on the assistant row', () => {
    const { result, keys } = setup();
    press('3', { altKey: true });
    expect(result.current.selectedIdx).toBe(2);
    expect(keys.askAssistant).toHaveBeenCalledTimes(1);
    press('1', { altKey: true });
    expect(keys.enter).toHaveBeenCalledWith(expect.objectContaining({ id: 'a' }));
  });

  it('switches the context with Tab, closes with Escape and runs the alt action', () => {
    const run = vi.fn();
    const { keys } = setup({ keys: { altAction: { shortcut: 'r', run } } });
    expect(press('Tab')).toBe(false);
    press('Tab', { shiftKey: true });
    expect(keys.cycleContext).toHaveBeenNthCalledWith(1, 1);
    expect(keys.cycleContext).toHaveBeenNthCalledWith(2, -1);

    press('R', { altKey: true });
    expect(run).toHaveBeenCalledWith(expect.objectContaining({ id: 'a' }));
    press('r');
    expect(run).toHaveBeenCalledTimes(1);

    press('Escape');
    expect(keys.close).toHaveBeenCalledTimes(1);
  });

  it('only lets Escape close the variable form while it is open', () => {
    const { keys } = setup({ keys: { collecting: true } });
    expect(press('Enter')).toBe(true);
    press('ArrowDown');
    press('Tab');
    expect(keys.enter).not.toHaveBeenCalled();
    expect(keys.cycleContext).not.toHaveBeenCalled();
    press('Escape');
    expect(keys.cancelCollecting).toHaveBeenCalledTimes(1);
    expect(keys.close).not.toHaveBeenCalled();
  });

  it('acts on the rows of the latest render and stops listening after unmount', () => {
    const { rerender, props, keys, unmount } = setup();
    rerender({ ...props, results: [item('fresh')] });
    press('Enter');
    expect(keys.enter).toHaveBeenCalledWith(expect.objectContaining({ id: 'fresh' }));

    unmount();
    expect(press('Enter')).toBe(true);
    expect(keys.enter).toHaveBeenCalledTimes(1);
  });
});
