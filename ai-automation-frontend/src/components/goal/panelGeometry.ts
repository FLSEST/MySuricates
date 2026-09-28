/**
 * Lage und Größe der Seitenleiste der Zielseite (c60bf914, Flo: „ziehbar und verschiebbar und
 * größenänderbar … bei den tieferen nicht alles erkennbar“): angedockt mit änderbarer Breite oder als
 * schwebendes Fenster, immer im Sichtbereich. Rein und testbar; gemerkt im Browser.
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
  /** Lage und Größe als Fenster. */
  rect: PanelRect;
}

export interface Viewport {
  width: number;
  height: number;
}

export const DOCK_MIN = 300;
/** Höchstens so breit angedockt – und nie mehr als 60 % der Seite, damit der Inhalt daneben bedienbar bleibt. */
export const DOCK_MAX = 720;
export const WINDOW_MIN = { width: 300, height: 240 };
/** So viel Abstand hält ein Fenster zum Rand des Sichtbereichs. */
export const EDGE = 8;
export const DEFAULT_WIDTH = 384;
const STORAGE_KEY = 'mysuricates-goal-panel';

export function clampWidth(width: number, viewport: Viewport): number {
  const max = Math.max(DOCK_MIN, Math.min(DOCK_MAX, Math.floor(viewport.width * 0.6)));
  return Math.round(Math.min(max, Math.max(DOCK_MIN, width)));
}

/** Ein Fenster, das ganz im Sichtbereich liegt: erst die Größe begrenzen, dann die Lage. */
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

/** Größe an einem Rand oder der Ecke rechts unten ändern; links wandert die Kante, rechts bleibt stehen. */
export function resizedRect(rect: PanelRect, edge: ResizeEdge, dx: number, dy: number, viewport: Viewport): PanelRect {
  if (edge === 'left') {
    const width = Math.max(WINDOW_MIN.width, Math.min(rect.width - dx, rect.x + rect.width - EDGE));
    return clampRect({ ...rect, x: rect.x + rect.width - width, width }, viewport);
  }
  const width = edge === 'bottom' ? rect.width : Math.min(rect.width + dx, viewport.width - rect.x - EDGE);
  const height = edge === 'right' ? rect.height : Math.min(rect.height + dy, viewport.height - rect.y - EDGE);
  return clampRect({ ...rect, width, height }, viewport);
}

/** Das erste Fenster: rechts, so breit wie angedockt, fast so hoch wie der Sichtbereich. */
export function defaultRect(width: number, viewport: Viewport): PanelRect {
  const w = Math.min(width, viewport.width - 2 * EDGE);
  return clampRect({ x: viewport.width - w - 24, y: 72, width: w, height: viewport.height - 96 }, viewport);
}

export function readPanelPrefs(viewport: Viewport): PanelPrefs {
  const fallback: PanelPrefs = { mode: 'docked', width: DEFAULT_WIDTH, rect: defaultRect(DEFAULT_WIDTH, viewport) };
  try {
    const raw = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? 'null') as Partial<PanelPrefs> | null;
    if (!raw || typeof raw !== 'object') return fallback;
    const width = typeof raw.width === 'number' ? clampWidth(raw.width, viewport) : fallback.width;
    const rect = raw.rect && ['x', 'y', 'width', 'height'].every((key) => typeof (raw.rect as unknown as Record<string, unknown>)[key] === 'number')
      ? clampRect(raw.rect, viewport) : defaultRect(width, viewport);
    return { mode: raw.mode === 'floating' ? 'floating' : 'docked', width, rect };
  } catch {
    return fallback;
  }
}

export function storePanelPrefs(prefs: PanelPrefs) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(prefs));
  } catch {
    // privates Fenster oder gesperrter Speicher: gilt dann bis zum Neuladen
  }
}
