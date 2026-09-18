/**
 * Persisted dashboard layout model — types, limits and the value rules shared by all layout
 * functions. Pure data + functions, no React.
 *
 * A layout holds named views; each view is an ordered list of widget instances. The operations
 * live next to this file: widget lists in dashboardWidgets.ts, views in dashboardViews.ts, and
 * the check of stored or received data in dashboardLayoutSanitize.ts.
 *
 * Everything here is plain data — no markup, no URLs to load, no code. The same shape is
 * validated again by the backend (DashboardLayoutCanonicalizer).
 */

import { generateId } from '../../utils/uuid';

export const LAYOUT_FORMAT_VERSION = 2;

export type WidgetSize = 'stat' | 'chart' | 'third' | 'twoThirds' | 'full';

export const WIDGET_SIZES: readonly WidgetSize[] = ['stat', 'chart', 'third', 'twoThirds', 'full'];

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

/** Data filter of a view (part of the format since v2, so stored versions need no migration). */
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
  /** Any number of instances with their own config; only added explicitly, can be removed. */
  multiple?: boolean;
  /** A singleton that never appears by itself: added from the catalog, can be removed. */
  catalogOnly?: boolean;
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

/** A view, instance, type or project id as the backend accepts it. */
export function isLayoutId(value: unknown): value is string {
  return typeof value === 'string' && value.length <= LAYOUT_LIMITS.idLength && ID_PATTERN.test(value);
}

function isConfigValue(value: unknown): value is WidgetConfigValue {
  return (typeof value === 'string' && value.length <= LAYOUT_LIMITS.configString)
    || (typeof value === 'number' && Number.isFinite(value))
    || typeof value === 'boolean';
}

/**
 * Valid config entries only (key pattern, primitive values, key limit), sorted by key like the
 * backend's canonical JSON, so equal configs compare equal in layoutContentKey. Undefined when none
 * are left.
 */
export function sanitizeConfig(value: unknown): WidgetConfig | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const entries = Object.entries(value as Record<string, unknown>)
    .filter((entry): entry is [string, WidgetConfigValue] => CONFIG_KEY_PATTERN.test(entry[0]) && isConfigValue(entry[1]))
    // Keys are ASCII, so code-unit order equals the backend's TreeMap (String.compareTo) order.
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    .slice(0, LAYOUT_LIMITS.configKeys);
  return entries.length > 0 ? Object.fromEntries(entries) : undefined;
}

/** Valid filter parts only; undefined when nothing is left (no filter at all). */
export function sanitizeFilter(value: unknown): DashboardViewFilter | undefined {
  if (!value || typeof value !== 'object') return undefined;
  const { projectId, days } = value as Record<string, unknown>;
  const filter: DashboardViewFilter = {};
  if (isLayoutId(projectId)) filter.projectId = projectId;
  if (typeof days === 'number' && Number.isInteger(days) && days >= 1 && days <= LAYOUT_LIMITS.maxDays) {
    filter.days = days;
  }
  return Object.keys(filter).length > 0 ? filter : undefined;
}
