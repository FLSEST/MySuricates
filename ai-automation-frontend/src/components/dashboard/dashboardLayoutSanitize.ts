/**
 * Turns stored or received layout data into the current format: migration of older format versions
 * and a strict check of the shape. Per-browser and server data are never trusted.
 */

import {
  LAYOUT_LIMITS,
  WIDGET_SIZES,
  createDefaultLayout,
  isLayoutId,
  sanitizeConfig,
  sanitizeFilter,
  truncateCodePoints,
  type DashboardLayout,
  type DashboardView,
  type WidgetInstance,
  type WidgetSize,
} from './dashboardLayout';

/**
 * Brings a persisted layout of an older format version to the current one. Returns the input
 * unchanged when there is nothing to migrate; the result still has to go through sanitizeLayout.
 */
export function migrateLayout(persisted: unknown, fromVersion: number): unknown {
  if (fromVersion >= 2 || !persisted || typeof persisted !== 'object') return persisted;
  const { views, ...rest } = persisted as Record<string, unknown>;
  if (!Array.isArray(views)) return persisted;
  return {
    ...rest,
    views: views.map((view) => {
      if (!view || typeof view !== 'object') return view;
      const { widgets, ...viewRest } = view as Record<string, unknown>;
      return {
        ...viewRest,
        // v1: { id, hidden?, size? } — the id was the widget type.
        widgets: Array.isArray(widgets)
          ? widgets.map((widget) => {
              if (!widget || typeof widget !== 'object') return widget;
              const { id, ...widgetRest } = widget as Record<string, unknown>;
              return { ...widgetRest, instanceId: id, type: id };
            })
          : widgets,
      };
    }),
  };
}

function sanitizeInstance(value: unknown): WidgetInstance | null {
  if (!value || typeof value !== 'object') return null;
  const { instanceId, type, hidden, size, config } = value as Record<string, unknown>;
  if (!isLayoutId(instanceId) || !isLayoutId(type)) return null;
  const instance: WidgetInstance = { instanceId, type };
  if (hidden === true) instance.hidden = true;
  if (typeof size === 'string' && (WIDGET_SIZES as readonly string[]).includes(size)) {
    instance.size = size as WidgetSize;
  }
  const cleanConfig = sanitizeConfig(config);
  if (cleanConfig) instance.config = cleanConfig;
  return instance;
}

/** Drops invalid entries and later duplicates of an instance id (the backend rejects duplicates). */
function uniqueInstances(instances: (WidgetInstance | null)[]): WidgetInstance[] {
  const seen = new Set<string>();
  return instances.filter((w): w is WidgetInstance => {
    if (w === null || seen.has(w.instanceId)) return false;
    seen.add(w.instanceId);
    return true;
  });
}

function sanitizeView(value: unknown): DashboardView | null {
  if (!value || typeof value !== 'object') return null;
  const { id, name, widgets, filter } = value as Record<string, unknown>;
  if (!isLayoutId(id)) return null;
  const view: DashboardView = {
    id,
    name: typeof name === 'string' ? truncateCodePoints(name, LAYOUT_LIMITS.nameLength) : '',
    widgets: Array.isArray(widgets) ? uniqueInstances(widgets.map(sanitizeInstance)).slice(0, LAYOUT_LIMITS.widgetsPerView) : [],
  };
  const cleanFilter = sanitizeFilter(filter);
  if (cleanFilter) view.filter = cleanFilter;
  return view;
}

/** Turns whatever came out of storage into a valid current-format layout; falls back to the default. */
export function sanitizeLayout(value: unknown): DashboardLayout {
  if (!value || typeof value !== 'object') return createDefaultLayout();
  const { activeViewId, views } = value as Record<string, unknown>;
  const seenViewIds = new Set<string>();
  const validViews = (Array.isArray(views) ? views : [])
    .map(sanitizeView)
    .filter((v): v is DashboardView => {
      if (v === null || seenViewIds.has(v.id)) return false;
      seenViewIds.add(v.id);
      return true;
    })
    .slice(0, LAYOUT_LIMITS.views);
  const [firstView] = validViews;
  if (!firstView) return createDefaultLayout();
  const active = validViews.some((v) => v.id === activeViewId) ? (activeViewId as string) : firstView.id;
  return { activeViewId: active, views: validViews };
}
