/**
 * Operations on the widget list of one view — pure functions over dashboardLayout.ts.
 *
 * Three kinds of widgets (spec E2, E4):
 * - Standard singletons (the default) exist once per view with instanceId === type. Missing ones
 *   are appended, so an empty list means "default layout"; hidden ones stay in the list with
 *   `hidden: true`. They can be hidden, never removed (they would come back at the end).
 * - Catalog singletons (`catalogOnly`) follow the same id rules but are never appended: a view
 *   only has them after they were added from the catalog. They can be removed.
 * - Multi widgets (`multiple`) are only added explicitly, each with its own config; removable.
 * A view made from a template lists its widgets and every other standard singleton as hidden, so
 * it shows exactly the template's widgets (see templateWidgets).
 */

import {
  LAYOUT_LIMITS,
  createInstanceId,
  sanitizeConfig,
  type WidgetConfig,
  type WidgetInstance,
  type WidgetSize,
  type WidgetTypeInfo,
} from './dashboardLayout';

/** Catalog singletons and multi widgets can be removed from a view; standard singletons only hidden. */
export function isRemovableWidget(type: WidgetTypeInfo): boolean {
  return Boolean(type.multiple || type.catalogOnly);
}

/**
 * Drops instances of unknown types, duplicate instance ids, singletons stored under another id and
 * multi instances that use a singleton's id, then appends missing standard singletons in registry
 * order. Catalog singletons and multi widgets are never appended.
 */
export function normalizeWidgets(widgets: readonly WidgetInstance[], types: readonly WidgetTypeInfo[]): WidgetInstance[] {
  const typesById = new Map(types.map((type) => [type.id, type]));
  const singletonIds = new Set(types.filter((type) => !type.multiple).map((type) => type.id));
  const seenInstances = new Set<string>();
  const seenSingletons = new Set<string>();
  const result: WidgetInstance[] = [];
  for (const widget of widgets) {
    const type = typesById.get(widget.type);
    if (!type || seenInstances.has(widget.instanceId)) continue;
    if (type.multiple) {
      // Would otherwise block that singleton forever.
      if (singletonIds.has(widget.instanceId)) continue;
    } else {
      // Singletons are addressed by their type; anything else is edited or corrupt data.
      if (widget.instanceId !== type.id || seenSingletons.has(type.id)) continue;
      seenSingletons.add(type.id);
    }
    seenInstances.add(widget.instanceId);
    result.push(widget);
  }
  for (const type of types) {
    if (type.multiple || type.catalogOnly || seenSingletons.has(type.id)) continue;
    result.push({ instanceId: type.id, type: type.id });
  }
  return result;
}

/** Moves `activeInstanceId` to the position of `overInstanceId`; instances in between keep their order. */
export function moveWidget(widgets: WidgetInstance[], activeInstanceId: string, overInstanceId: string): WidgetInstance[] {
  const from = widgets.findIndex((w) => w.instanceId === activeInstanceId);
  const to = widgets.findIndex((w) => w.instanceId === overInstanceId);
  const moved = widgets[from];
  if (!moved || to < 0 || from === to) return widgets;
  const next = [...widgets];
  next.splice(from, 1);
  next.splice(to, 0, moved);
  return next;
}

export function setWidgetHidden(widgets: WidgetInstance[], instanceId: string, hidden: boolean): WidgetInstance[] {
  return widgets.map((w) => {
    if (w.instanceId !== instanceId) return w;
    if (hidden) return { ...w, hidden: true };
    const { hidden: _omit, ...rest } = w;
    return rest;
  });
}

/**
 * Appends an instance of a `multiple` widget. Returns the list unchanged for unknown or singleton
 * types and for an instance id that is already used or equals a widget type.
 */
export function addWidgetInstance(
  widgets: WidgetInstance[],
  types: readonly WidgetTypeInfo[],
  type: string,
  config: WidgetConfig,
  instanceId: string = createInstanceId(type),
): WidgetInstance[] {
  if (!types.find((t) => t.id === type)?.multiple) return widgets;
  if (types.some((t) => t.id === instanceId) || widgets.some((w) => w.instanceId === instanceId)) return widgets;
  const instance: WidgetInstance = { instanceId, type };
  if (Object.keys(config).length > 0) instance.config = config;
  return [...widgets, instance];
}

export function removeWidgetInstance(widgets: WidgetInstance[], instanceId: string): WidgetInstance[] {
  return widgets.filter((w) => w.instanceId !== instanceId);
}

export function updateWidgetConfig(widgets: WidgetInstance[], instanceId: string, config: WidgetConfig): WidgetInstance[] {
  return widgets.map((w) => {
    if (w.instanceId !== instanceId) return w;
    if (Object.keys(config).length > 0) return { ...w, config };
    const { config: _omit, ...rest } = w;
    return rest;
  });
}

/** Sets the size of one instance; `undefined` goes back to the widget's default. Same list when nothing changes. */
export function setWidgetSize(widgets: WidgetInstance[], instanceId: string, size: WidgetSize | undefined): WidgetInstance[] {
  const current = widgets.find((w) => w.instanceId === instanceId);
  if (!current || current.size === size) return widgets;
  return widgets.map((w) => {
    if (w.instanceId !== instanceId) return w;
    if (size) return { ...w, size };
    const { size: _omit, ...rest } = w;
    return rest;
  });
}

/**
 * What "add" in the catalog does: shows a hidden singleton again, appends a missing singleton, or
 * appends a new multi instance with a cleaned config. Same list for unknown types, a singleton that
 * is already shown, and appends past LAYOUT_LIMITS.widgetsPerView.
 */
export function addWidgetToView(
  widgets: WidgetInstance[],
  types: readonly WidgetTypeInfo[],
  type: string,
  config: WidgetConfig = {},
  instanceId: string = createInstanceId(type),
): WidgetInstance[] {
  const info = types.find((t) => t.id === type);
  if (!info) return widgets;
  const atLimit = widgets.length >= LAYOUT_LIMITS.widgetsPerView;
  if (info.multiple) {
    return atLimit ? widgets : addWidgetInstance(widgets, types, type, sanitizeConfig(config) ?? {}, instanceId);
  }
  const existing = widgets.find((w) => w.instanceId === type);
  if (existing) return existing.hidden ? setWidgetHidden(widgets, type, false) : widgets;
  return atLimit ? widgets : [...widgets, { instanceId: type, type }];
}

/**
 * Widget list of a view made from a template: the listed singletons visible, in template order,
 * then every other standard singleton hidden. Unknown ids, multi widgets and duplicates are skipped.
 */
export function templateWidgets(widgetIds: readonly string[], types: readonly WidgetTypeInfo[]): WidgetInstance[] {
  const singletonIds = new Set(types.filter((t) => !t.multiple).map((t) => t.id));
  const listed = [...new Set(widgetIds)].filter((id) => singletonIds.has(id));
  const hidden = types
    .filter((t) => !t.multiple && !t.catalogOnly && !listed.includes(t.id))
    .map((t): WidgetInstance => ({ instanceId: t.id, type: t.id, hidden: true }));
  return [...listed.map((id): WidgetInstance => ({ instanceId: id, type: id })), ...hidden];
}
