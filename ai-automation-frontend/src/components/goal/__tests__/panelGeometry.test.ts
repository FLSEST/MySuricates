import { describe, it, expect, beforeEach } from 'vitest';
import {
  DOCK_MAX, DOCK_MIN, EDGE, WINDOW_MIN, clampRect, clampWidth, movedRect, readPanelPrefs, resizedRect, storePanelPrefs,
} from '../panelGeometry';
import { parseSelection, selectionValue } from '../goalSelection';

const view = { width: 1440, height: 900 };

// c60bf914: the side panel is resizable, detachable and always inside the view
describe('panelGeometry', () => {
  beforeEach(() => localStorage.clear());

  it('keeps the docked width between its bounds and at most 60 % of the page', () => {
    expect(clampWidth(100, view)).toBe(DOCK_MIN);
    expect(clampWidth(5000, view)).toBe(DOCK_MAX);
    expect(clampWidth(5000, { width: 1000, height: 800 })).toBe(600);
    expect(clampWidth(410.4, view)).toBe(410);
  });

  it('keeps a window fully in view – size first, then place', () => {
    expect(clampRect({ x: -50, y: -10, width: 400, height: 300 }, view)).toEqual({ x: EDGE, y: EDGE, width: 400, height: 300 });
    expect(clampRect({ x: 1300, y: 800, width: 400, height: 300 }, view)).toEqual({ x: 1440 - 400 - EDGE, y: 900 - 300 - EDGE, width: 400, height: 300 });
    expect(clampRect({ x: 0, y: 0, width: 5000, height: 5000 }, view)).toEqual({ x: EDGE, y: EDGE, width: 1440 - 2 * EDGE, height: 900 - 2 * EDGE });
    expect(clampRect({ x: 10, y: 10, width: 10, height: 10 }, view)).toMatchObject(WINDOW_MIN);
  });

  it('moves and resizes, never past the edges and never below the minimum', () => {
    const rect = { x: 100, y: 100, width: 400, height: 500 };
    expect(movedRect(rect, 50, -20, view)).toMatchObject({ x: 150, y: 80 });
    expect(resizedRect(rect, 'corner', 100, 50, view)).toMatchObject({ x: 100, width: 500, height: 550 });
    expect(resizedRect(rect, 'right', 5000, 0, view)).toMatchObject({ width: 1440 - 100 - EDGE, height: 500 });
    expect(resizedRect(rect, 'bottom', 0, -5000, view)).toMatchObject({ height: WINDOW_MIN.height });
    // the left edge moves, the right one stays
    expect(resizedRect(rect, 'left', -60, 0, view)).toMatchObject({ x: 40, width: 460 });
  });

  it('remembers the settings and pulls a remembered window back into view', () => {
    storePanelPrefs({ mode: 'floating', width: 500, rect: { x: 3000, y: 3000, width: 400, height: 300 } });
    expect(readPanelPrefs(view)).toEqual({ mode: 'floating', width: 500, rect: { x: 1440 - 400 - EDGE, y: 900 - 300 - EDGE, width: 400, height: 300 } });
    localStorage.setItem('mysuricates-goal-panel', '{broken');
    expect(readPanelPrefs(view).mode).toBe('docked');
  });

  it('reads and writes the selection of the URL', () => {
    expect(parseSelection('milestone:ms1')).toEqual({ kind: 'milestone', id: 'ms1' });
    expect(parseSelection('workpackage:w-1:x')).toEqual({ kind: 'workpackage', id: 'w-1:x' });
    expect(parseSelection('todo:1')).toBeNull();
    expect(parseSelection('milestone:')).toBeNull();
    expect(parseSelection(null)).toBeNull();
    expect(selectionValue({ kind: 'workpackage', id: 'w1' })).toBe('workpackage:w1');
  });
});
