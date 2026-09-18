/**
 * View operations — pure functions over dashboardLayout.ts. Each returns the same layout object
 * when nothing changes (unknown id, limit reached, same value), so callers can tell a real edit
 * from a no-op by identity.
 */

import {
  LAYOUT_LIMITS,
  cleanViewName,
  sanitizeFilter,
  type DashboardLayout,
  type DashboardView,
  type DashboardViewFilter,
  type WidgetInstance,
} from './dashboardLayout';

/** The shown view: the active one, or the first view when the id is stale. */
export function activeViewOf(layout: DashboardLayout): DashboardView | undefined {
  return layout.views.find((v) => v.id === layout.activeViewId) ?? layout.views[0];
}

/** Appends a view (empty, or with the widgets of a template) and makes it the active one. */
export function addLayoutView(layout: DashboardLayout, name: string, id: string, widgets: WidgetInstance[] = []): DashboardLayout {
  if (layout.views.length >= LAYOUT_LIMITS.views || layout.views.some((v) => v.id === id)) return layout;
  return { activeViewId: id, views: [...layout.views, { id, name: cleanViewName(name), widgets: [...widgets] }] };
}

export function renameLayoutView(layout: DashboardLayout, viewId: string, name: string): DashboardLayout {
  const cleaned = cleanViewName(name);
  const view = layout.views.find((v) => v.id === viewId);
  if (!view || view.name === cleaned) return layout;
  return { ...layout, views: layout.views.map((v) => (v.id === viewId ? { ...v, name: cleaned } : v)) };
}

/** Copies widgets and filter of a view into a new view right after it and makes the copy active. */
export function duplicateLayoutView(layout: DashboardLayout, viewId: string, name: string, id: string): DashboardLayout {
  const index = layout.views.findIndex((v) => v.id === viewId);
  const source = layout.views[index];
  if (!source || layout.views.length >= LAYOUT_LIMITS.views || layout.views.some((v) => v.id === id)) return layout;
  const copy: DashboardView = { id, name: cleanViewName(name), widgets: [...source.widgets] };
  if (source.filter) copy.filter = { ...source.filter };
  const views = [...layout.views];
  views.splice(index + 1, 0, copy);
  return { activeViewId: id, views };
}

/** Removes a view but never the last one; a removed active view hands over to the next view (or the previous one). */
export function removeLayoutView(layout: DashboardLayout, viewId: string): DashboardLayout {
  const index = layout.views.findIndex((v) => v.id === viewId);
  if (index < 0 || layout.views.length <= 1) return layout;
  const views = layout.views.filter((v) => v.id !== viewId);
  if (layout.activeViewId !== viewId) return { ...layout, views };
  const neighbour = views[index] ?? views[index - 1];
  if (!neighbour) return layout;
  return { activeViewId: neighbour.id, views };
}

/** Moves a view onto the position of another one (tab drag); views in between keep their order. */
export function moveLayoutView(layout: DashboardLayout, draggedViewId: string, overViewId: string): DashboardLayout {
  const from = layout.views.findIndex((v) => v.id === draggedViewId);
  const to = layout.views.findIndex((v) => v.id === overViewId);
  const moved = layout.views[from];
  if (!moved || to < 0 || from === to) return layout;
  const views = [...layout.views];
  views.splice(from, 1);
  views.splice(to, 0, moved);
  return { ...layout, views };
}

export function selectLayoutView(layout: DashboardLayout, viewId: string): DashboardLayout {
  if (layout.activeViewId === viewId || !layout.views.some((v) => v.id === viewId)) return layout;
  return { ...layout, activeViewId: viewId };
}

/** Sets the data filter of a view; invalid parts are dropped and an empty filter removes the field. */
export function setLayoutViewFilter(layout: DashboardLayout, viewId: string, filter: DashboardViewFilter): DashboardLayout {
  const view = layout.views.find((v) => v.id === viewId);
  if (!view) return layout;
  const cleaned = sanitizeFilter(filter);
  if (cleaned?.projectId === view.filter?.projectId && cleaned?.days === view.filter?.days) return layout;
  const next: DashboardView = { id: view.id, name: view.name, widgets: view.widgets };
  if (cleaned) next.filter = cleaned;
  return { ...layout, views: layout.views.map((v) => (v.id === viewId ? next : v)) };
}
