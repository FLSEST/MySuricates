/**
 * Persisted dashboard layout model — pure data + functions, no React.
 *
 * A layout holds named views; each view is an ordered list of widget instances.
 * Singleton widgets (the default) exist once per view with instanceId === type. Missing ones are
 * appended, so an empty list means "default layout" and widgets from a later release show up in
 * customized layouts; hidden singletons stay in the list with `hidden: true`.
 * Widgets registered with `multiple` are only added explicitly, with a config; they can also be
 * removed, in addition to being hidden.
 *
 * Everything here is plain data — no markup, no URLs to load, no code. The same shape is
 * validated again by the backend once layouts are stored there.
 */

import { generateId } from '../../utils/uuid';

export const LAYOUT_FORMAT_VERSION = 2;

export type WidgetSize = 'stat' | 'chart' | 'third' | 'twoThirds' | 'full';

const WIDGET_SIZES: readonly WidgetSize[] = ['stat', 'chart', 'third', 'twoThirds', 'full'];

export type WidgetConfigValue = string | number | boolean;
export type WidgetConfig = Readonly<Record<string, WidgetConfigValue>>;

export interface WidgetInstance {
  /** Unique within a view; singletons use their type. */
  instanceId: string;
  /** Registry id of the widget. */
  type: string;
  hidden?: boolean;
  /** Overrides the widget's default size. */
  size?: WidgetSize;
  config?: WidgetConfig;
}

/** Data filter of a view (used by the views phase; part of the format so stored versions need no migration). */
export interface DashboardViewFilter {
  projectId?: string;
  days?: number;
}

export interface DashboardView {
  id: string;
  name: string;
  widgets: WidgetInstance[];
  filter?: DashboardViewFilter;
}

export interface DashboardLayout {
  activeViewId: string;
  views: DashboardView[];
}

/** What the layout functions need to know about a registered widget. */
export interface WidgetTypeInfo {
  id: string;
  multiple?: boolean;
}

export const DEFAULT_VIEW_ID = 'default';

export const LAYOUT_LIMITS = {
  views: 20,
  widgetsPerView: 100,
  configKeys: 20,
  configString: 500,
  idLength: 80,
  nameLength: 80,
  maxDays: 365,
} as const;

const ID_PATTERN = /^[a-z0-9][a-z0-9-]*$/;
const CONFIG_KEY_PATTERN = /^[a-zA-Z][a-zA-Z0-9]{0,39}$/;

export function createDefaultLayout(): DashboardLayout {
  return {
    activeViewId: DEFAULT_VIEW_ID,
    views: [{ id: DEFAULT_VIEW_ID, name: '', widgets: [] }],
  };
}

/** True when the layout differs from the untouched default (used to decide whether local changes exist). */
export function isCustomizedLayout(layout: DashboardLayout): boolean {
  return layout.views.length > 1
    || layout.views.some((view) => view.widgets.length > 0 || view.name !== '' || view.filter !== undefined);
}

export function createInstanceId(type: string): string {
  return `${type}-${generateId().slice(0, 8)}`;
}

/** New view id: `view-` + 8 hex characters, valid for the backend's id pattern. */
export function createViewId(): string {
  return `view-${generateId().slice(0, 8)}`;
}

/** Cuts after `max` Unicode code points, so a surrogate pair (e.g. an emoji) is never split. */
export function truncateCodePoints(value: string, max: number): string {
  const codePoints = Array.from(value);
  return codePoints.length <= max ? value : codePoints.slice(0, max).join('');
}

/** A view name as stored: trimmed, at most LAYOUT_LIMITS.nameLength code points (the backend counts code points too). */
export function cleanViewName(name: string): string {
  return truncateCodePoints(name.trim(), LAYOUT_LIMITS.nameLength).trim();
}

/**
 * What a layout version stores apart from the active view. The shown view is chosen per device, so
 * comparisons between a local and a server layout must ignore it.
 */
export function layoutContentKey(layout: DashboardLayout): string {
  return JSON.stringify(layout.views);
}

/*
 * View operations. Each returns the same layout object when nothing changes (unknown id, limit
 * reached, same value), so callers can tell a real edit from a no-op by identity.
 */

/** Appends an empty view and makes it the active one. */
export function addLayoutView(layout: DashboardLayout, name: string, id: string): DashboardLayout {
  if (layout.views.length >= LAYOUT_LIMITS.views || layout.views.some((v) => v.id === id)) return layout;
  return { activeViewId: id, views: [...layout.views, { id, name: cleanViewName(name), widgets: [] }] };
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

function isId(value: unknown): value is string {
  return typeof value === 'string' && value.length <= LAYOUT_LIMITS.idLength && ID_PATTERN.test(value);
}

function isConfigValue(value: unknown): value is WidgetConfigValue {
  return (typeof value === 'string' && value.length <= LAYOUT_LIMITS.configString)
    || (typeof value === 'number' && Number.isFinite(value))
    || typeof value === 'boolean';
}

function sanitizeConfig(value: unknown): WidgetConfig | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const entries = Object.entries(value as Record<string, unknown>)
    .filter((entry): entry is [string, WidgetConfigValue] => CONFIG_KEY_PATTERN.test(entry[0]) && isConfigValue(entry[1]))
    .slice(0, LAYOUT_LIMITS.configKeys);
  return entries.length > 0 ? Object.fromEntries(entries) : undefined;
}

function sanitizeInstance(value: unknown): WidgetInstance | null {
  if (!value || typeof value !== 'object') return null;
  const { instanceId, type, hidden, size, config } = value as Record<string, unknown>;
  if (!isId(instanceId) || !isId(type)) return null;
  const instance: WidgetInstance = { instanceId, type };
  if (hidden === true) instance.hidden = true;
  if (typeof size === 'string' && (WIDGET_SIZES as readonly string[]).includes(size)) {
    instance.size = size as WidgetSize;
  }
  const cleanConfig = sanitizeConfig(config);
  if (cleanConfig) instance.config = cleanConfig;
  return instance;
}

function sanitizeFilter(value: unknown): DashboardViewFilter | undefined {
  if (!value || typeof value !== 'object') return undefined;
  const { projectId, days } = value as Record<string, unknown>;
  const filter: DashboardViewFilter = {};
  if (isId(projectId)) filter.projectId = projectId;
  if (typeof days === 'number' && Number.isInteger(days) && days >= 1 && days <= LAYOUT_LIMITS.maxDays) {
    filter.days = days;
  }
  return Object.keys(filter).length > 0 ? filter : undefined;
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
  if (!isId(id)) return null;
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
