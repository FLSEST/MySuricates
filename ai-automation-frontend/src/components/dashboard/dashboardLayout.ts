/**
 * Persisted dashboard layout model — pure data + functions, no React.
 *
 * A layout holds named views; each view is an ordered list of widget placements.
 * A view with an empty list means "default": every registered widget in registry order.
 * Hidden widgets stay in the list with `hidden: true` — otherwise they could not be told
 * apart from widgets added in a later release, which are appended and shown automatically.
 */

export type WidgetSize = 'stat' | 'chart' | 'third' | 'twoThirds' | 'full';

const WIDGET_SIZES: readonly WidgetSize[] = ['stat', 'chart', 'third', 'twoThirds', 'full'];

export interface WidgetPlacement {
  id: string;
  hidden?: boolean;
  /** Overrides the widget's default size (reserved for a later resize UI). */
  size?: WidgetSize;
}

export interface DashboardView {
  id: string;
  name: string;
  widgets: WidgetPlacement[];
}

export interface DashboardLayout {
  activeViewId: string;
  views: DashboardView[];
}

export const DEFAULT_VIEW_ID = 'default';

export function createDefaultLayout(): DashboardLayout {
  return {
    activeViewId: DEFAULT_VIEW_ID,
    views: [{ id: DEFAULT_VIEW_ID, name: '', widgets: [] }],
  };
}

/** Drops unknown and duplicate ids, then appends known widgets missing from the list. */
export function normalizePlacements(placements: WidgetPlacement[], knownIds: readonly string[]): WidgetPlacement[] {
  const known = new Set(knownIds);
  const seen = new Set<string>();
  const result: WidgetPlacement[] = [];
  for (const placement of placements) {
    if (!known.has(placement.id) || seen.has(placement.id)) continue;
    seen.add(placement.id);
    result.push(placement);
  }
  for (const id of knownIds) {
    if (!seen.has(id)) result.push({ id });
  }
  return result;
}

/** Moves `activeId` to the position of `overId`; ids in between (hidden ones too) keep their relative order. */
export function moveWidget(placements: WidgetPlacement[], activeId: string, overId: string): WidgetPlacement[] {
  const from = placements.findIndex((p) => p.id === activeId);
  const to = placements.findIndex((p) => p.id === overId);
  const moved = placements[from];
  if (!moved || to < 0 || from === to) return placements;
  const next = [...placements];
  next.splice(from, 1);
  next.splice(to, 0, moved);
  return next;
}

export function setWidgetHidden(placements: WidgetPlacement[], id: string, hidden: boolean): WidgetPlacement[] {
  return placements.map((p) => {
    if (p.id !== id) return p;
    if (hidden) return { ...p, hidden: true };
    const { hidden: _omit, ...rest } = p;
    return rest;
  });
}

function sanitizePlacement(value: unknown): WidgetPlacement | null {
  if (!value || typeof value !== 'object') return null;
  const { id, hidden, size } = value as Record<string, unknown>;
  if (typeof id !== 'string' || !id) return null;
  const placement: WidgetPlacement = { id };
  if (hidden === true) placement.hidden = true;
  if (typeof size === 'string' && (WIDGET_SIZES as readonly string[]).includes(size)) {
    placement.size = size as WidgetSize;
  }
  return placement;
}

function sanitizeView(value: unknown): DashboardView | null {
  if (!value || typeof value !== 'object') return null;
  const { id, name, widgets } = value as Record<string, unknown>;
  if (typeof id !== 'string' || !id) return null;
  return {
    id,
    name: typeof name === 'string' ? name : '',
    widgets: Array.isArray(widgets)
      ? widgets.map(sanitizePlacement).filter((p): p is WidgetPlacement => p !== null)
      : [],
  };
}

/** Turns whatever came out of localStorage into a valid layout; falls back to the default. */
export function sanitizeLayout(value: unknown): DashboardLayout {
  if (!value || typeof value !== 'object') return createDefaultLayout();
  const { activeViewId, views } = value as Record<string, unknown>;
  const validViews = Array.isArray(views)
    ? views.map(sanitizeView).filter((v): v is DashboardView => v !== null)
    : [];
  const [firstView] = validViews;
  if (!firstView) return createDefaultLayout();
  const active = validViews.some((v) => v.id === activeViewId) ? (activeViewId as string) : firstView.id;
  return { activeViewId: active, views: validViews };
}
