/**
 * Position and size of the side bar of the goal page (c60bf914, Flo: „ziehbar und verschiebbar und
 * größenänderbar … bei den tieferen nicht alles erkennbar“): docked with changeable width or as a
 * floating window, always within the viewport. Pure and testable; remembered in the browser.
 */

export type PanelMode = 'docked' | 'floating';

export interface PanelRect {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface PanelPrefs {
  mode: PanelMode;
  /** Breite angedockt. */
  width: number;
  /** Position and size as a window. */
  rect: PanelRect;
}

export interface Viewport {
  width: number;
  height: number;
}

export const DOCK_MIN = 300;
/** At most this wide when docked - and never more than 60 % of the page, so the content next to it stays usable. */
export const DOCK_MAX = 720;
export const WINDOW_MIN = { width: 300, height: 240 };
/** This much distance a window keeps from the edge of the viewport. */
export const EDGE = 8;
export const DEFAULT_WIDTH = 384;
/** Storage of the side bar; the workflow bar of the assignment (52bc1e8c) remembers its position separately. */
export const PANEL_STORAGE_KEY = 'mysuricates-goal-panel';
export const WORKFLOW_PANEL_KEY = 'mysuricates-goal-workflow-panel';

export function clampWidth(width: number, viewport: Viewport): number {
  const max = Math.max(DOCK_MIN, Math.min(DOCK_MAX, Math.floor(viewport.width * 0.6)));
  return Math.round(Math.min(max, Math.max(DOCK_MIN, width)));
}

/** A window that lies fully within the viewport: first limit the size, then the position. */
export function clampRect(rect: PanelRect, viewport: Viewport): PanelRect {
  const width = Math.min(Math.max(WINDOW_MIN.width, rect.width), Math.max(WINDOW_MIN.width, viewport.width - 2 * EDGE));
  const height = Math.min(Math.max(WINDOW_MIN.height, rect.height), Math.max(WINDOW_MIN.height, viewport.height - 2 * EDGE));
  const x = Math.min(Math.max(EDGE, rect.x), Math.max(EDGE, viewport.width - width - EDGE));
  const y = Math.min(Math.max(EDGE, rect.y), Math.max(EDGE, viewport.height - height - EDGE));
  return { x: Math.round(x), y: Math.round(y), width: Math.round(width), height: Math.round(height) };
}

export function movedRect(rect: PanelRect, dx: number, dy: number, viewport: Viewport): PanelRect {
  return clampRect({ ...rect, x: rect.x + dx, y: rect.y + dy }, viewport);
}

export type ResizeEdge = 'right' | 'bottom' | 'corner' | 'left';

/** Change size at an edge or the bottom right corner; on the left the edge moves, on the right it stays put. */
export function resizedRect(rect: PanelRect, edge: ResizeEdge, dx: number, dy: number, viewport: Viewport): PanelRect {
  if (edge === 'left') {
    const width = Math.max(WINDOW_MIN.width, Math.min(rect.width - dx, rect.x + rect.width - EDGE));
    return clampRect({ ...rect, x: rect.x + rect.width - width, width }, viewport);
  }
  const width = edge === 'bottom' ? rect.width : Math.min(rect.width + dx, viewport.width - rect.x - EDGE);
  const height = edge === 'right' ? rect.height : Math.min(rect.height + dy, viewport.height - rect.y - EDGE);
  return clampRect({ ...rect, width, height }, viewport);
}

/** The first window: on the right, as wide as docked, almost as tall as the viewport. */
export function defaultRect(width: number, viewport: Viewport): PanelRect {
  const w = Math.min(width, viewport.width - 2 * EDGE);
  return clampRect({ x: viewport.width - w - 24, y: 72, width: w, height: viewport.height - 96 }, viewport);
}

export function readPanelPrefs(viewport: Viewport, key: string = PANEL_STORAGE_KEY): PanelPrefs {
  const fallback: PanelPrefs = { mode: 'docked', width: DEFAULT_WIDTH, rect: defaultRect(DEFAULT_WIDTH, viewport) };
  try {
    const raw = JSON.parse(localStorage.getItem(key) ?? 'null') as Partial<PanelPrefs> | null;
    if (!raw || typeof raw !== 'object') return fallback;
    const width = typeof raw.width === 'number' ? clampWidth(raw.width, viewport) : fallback.width;
    const rect = raw.rect && ['x', 'y', 'width', 'height'].every((key) => typeof (raw.rect as unknown as Record<string, unknown>)[key] === 'number')
      ? clampRect(raw.rect, viewport) : defaultRect(width, viewport);
    return { mode: raw.mode === 'floating' ? 'floating' : 'docked', width, rect };
  } catch {
    return fallback;
  }
}

export function storePanelPrefs(prefs: PanelPrefs, key: string = PANEL_STORAGE_KEY) {
  try {
    localStorage.setItem(key, JSON.stringify(prefs));
  } catch {
    // private window or blocked storage: then valid until reload
  }
}
