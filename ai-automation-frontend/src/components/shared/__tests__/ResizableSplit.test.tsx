import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ResizableSplit, SPLIT_DEFAULT_SIDE_PERCENT, SPLIT_MAX_SIDE_PERCENT, SPLIT_MIN_SIDE_PERCENT } from '../ResizableSplit';

const KEY = 'test-split-width';

function renderSplit(props: { collapsed?: boolean } = {}) {
  return render(
    <ResizableSplit
      storageKey={KEY}
      separatorLabel="Seitenleiste anpassen"
      collapsed={props.collapsed}
      main={<div>Hauptbereich</div>}
      side={<div>Seitenleiste</div>}
    />,
  );
}

const separator = () => screen.getByRole('separator', { name: 'Seitenleiste anpassen' });
const sideWidth = () => Number(separator().getAttribute('aria-valuenow'));

function setDesktop(matches: boolean) {
  vi.mocked(window.matchMedia).mockImplementation((query: string) => ({
    matches: query.includes('max-width') ? !matches : matches,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  }));
}

describe('ResizableSplit', () => {
  beforeEach(() => {
    localStorage.clear();
    setDesktop(true);
  });

  it('starts at the default side width with both panes visible', () => {
    renderSplit();
    expect(screen.getByText('Hauptbereich')).toBeVisible();
    expect(screen.getByText('Seitenleiste')).toBeVisible();
    expect(sideWidth()).toBe(SPLIT_DEFAULT_SIDE_PERCENT);
    expect(separator()).toHaveAttribute('aria-valuemin', String(SPLIT_MIN_SIDE_PERCENT));
    expect(separator()).toHaveAttribute('aria-valuemax', String(SPLIT_MAX_SIDE_PERCENT));
  });

  it('moves the divider with the arrow keys and remembers the width', () => {
    renderSplit();
    fireEvent.keyDown(separator(), { key: 'ArrowLeft' });
    const widened = sideWidth();
    expect(widened).toBeGreaterThan(SPLIT_DEFAULT_SIDE_PERCENT);
    expect(JSON.parse(localStorage.getItem(KEY)!)).toBe(widened);

    fireEvent.keyDown(separator(), { key: 'ArrowRight' });
    fireEvent.keyDown(separator(), { key: 'ArrowRight' });
    expect(sideWidth()).toBeLessThan(SPLIT_DEFAULT_SIDE_PERCENT);
  });

  it('jumps to the bounds with Home and End', () => {
    renderSplit();
    fireEvent.keyDown(separator(), { key: 'Home' });
    expect(sideWidth()).toBe(SPLIT_MAX_SIDE_PERCENT);
    fireEvent.keyDown(separator(), { key: 'End' });
    expect(sideWidth()).toBe(SPLIT_MIN_SIDE_PERCENT);
  });

  it('follows a pointer drag relative to the container', () => {
    renderSplit();
    const container = separator().parentElement!;
    vi.spyOn(container, 'getBoundingClientRect').mockReturnValue(
      { left: 0, right: 1000, width: 1000, top: 0, bottom: 500, height: 500, x: 0, y: 0, toJSON: () => ({}) },
    );
    fireEvent.pointerDown(separator(), { pointerId: 1, clientX: 670 });
    fireEvent.pointerMove(separator(), { pointerId: 1, clientX: 600 });
    fireEvent.pointerUp(separator(), { pointerId: 1, clientX: 600 });
    expect(sideWidth()).toBe(40);
    expect(JSON.parse(localStorage.getItem(KEY)!)).toBe(40);

    // Moving after release must not resize any more
    fireEvent.pointerMove(separator(), { pointerId: 1, clientX: 900 });
    expect(sideWidth()).toBe(40);
  });

  it('clamps a drag beyond the bounds', () => {
    renderSplit();
    const container = separator().parentElement!;
    vi.spyOn(container, 'getBoundingClientRect').mockReturnValue(
      { left: 0, right: 1000, width: 1000, top: 0, bottom: 500, height: 500, x: 0, y: 0, toJSON: () => ({}) },
    );
    fireEvent.pointerDown(separator(), { pointerId: 1, clientX: 670 });
    fireEvent.pointerMove(separator(), { pointerId: 1, clientX: 50 });
    expect(sideWidth()).toBe(SPLIT_MAX_SIDE_PERCENT);
    fireEvent.pointerMove(separator(), { pointerId: 1, clientX: 990 });
    expect(sideWidth()).toBe(SPLIT_MIN_SIDE_PERCENT);
  });

  it('resets to the default width on double click', () => {
    localStorage.setItem(KEY, JSON.stringify(50));
    renderSplit();
    expect(sideWidth()).toBe(50);
    fireEvent.doubleClick(separator());
    expect(sideWidth()).toBe(SPLIT_DEFAULT_SIDE_PERCENT);
  });

  it('falls back to the default for a broken or out-of-range stored width', () => {
    localStorage.setItem(KEY, JSON.stringify('breit'));
    const { unmount } = renderSplit();
    expect(sideWidth()).toBe(SPLIT_DEFAULT_SIDE_PERCENT);
    unmount();

    localStorage.setItem(KEY, JSON.stringify(95));
    renderSplit();
    expect(sideWidth()).toBe(SPLIT_MAX_SIDE_PERCENT);
  });

  it('hides the side pane and the divider when collapsed, keeping the side mounted', () => {
    renderSplit({ collapsed: true });
    expect(screen.queryByRole('separator')).not.toBeInTheDocument();
    expect(screen.getByText('Hauptbereich')).toBeVisible();
    // Stays mounted so its state (e.g. the run list page) survives re-expanding
    expect(screen.getByText('Seitenleiste')).not.toBeVisible();
  });

  it('stacks both panes without a divider on narrow screens, even when collapsed', () => {
    setDesktop(false);
    renderSplit({ collapsed: true });
    expect(screen.queryByRole('separator')).not.toBeInTheDocument();
    expect(screen.getByText('Hauptbereich')).toBeVisible();
    expect(screen.getByText('Seitenleiste')).toBeVisible();
  });
});
