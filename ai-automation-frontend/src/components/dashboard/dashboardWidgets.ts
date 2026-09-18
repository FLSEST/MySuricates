/**
 * Operations on the widget list of one view — pure functions over dashboardLayout.ts.
 *
 * Singleton widgets (the default) exist once per view with instanceId === type. Missing ones are
 * appended, so an empty list means "default layout" and widgets from a later release show up in
 * customized layouts; hidden singletons stay in the list with `hidden: true`.
 * Widgets registered with `multiple` are only added explicitly, with a config; they can also be
 * removed, in addition to being hidden.
 */

import {
  createInstanceId,
  type WidgetConfig,
  type WidgetInstance,
  type WidgetTypeInfo,
} from './dashboardLayout';

/**
 * Drops instances of unknown types, duplicate instance ids, singletons stored under another id and
 * multi instances that use a singleton's id, then appends missing singletons in registry order.
 * Multi widgets are never appended.
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
    if (type.multiple || seenSingletons.has(type.id)) continue;
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
