import type { ComponentType } from 'react';
import type { TranslationKeys } from '../../i18n/en';
import type { ModuleName } from '../../types';
import type { WidgetConfig, WidgetSize } from './dashboardLayout';

/** Props every widget component receives. Singletons can ignore them. */
export interface WidgetProps {
  instanceId: string;
  /** Validated plain values; the widget checks the keys it needs itself. */
  config: WidgetConfig;
}

/** Form a multi widget shows in the catalog before an instance is added (e.g. which workflow). */
export interface WidgetConfigFormProps {
  /** Adds the instance with this config; it is cleaned (sanitizeConfig) before it is stored. */
  onSubmit: (config: WidgetConfig) => void;
  onCancel: () => void;
}

type DescriptionKey = keyof TranslationKeys['dashboard']['catalog']['descriptions'];

/** Catalog description from dashboard.catalog.descriptions. */
export const describedBy = (key: DescriptionKey) => (t: TranslationKeys) => t.dashboard.catalog.descriptions[key];

/** Groups of the "Add widget" catalog, in display order. */
export const WIDGET_CATEGORIES = ['overview', 'runs', 'costs', 'quality', 'planning'] as const;
export type WidgetCategory = (typeof WIDGET_CATEGORIES)[number];

export interface DashboardWidgetDef {
  /** Stable id — persisted in users' layouts as instance type, never rename. */
  id: string;
  /** Unique label for the edit mode (catalog, drag handles, size menu). */
  label: (t: TranslationKeys) => string;
  /** One plain-text sentence for the catalog. */
  description: (t: TranslationKeys) => string;
  category: WidgetCategory;
  size: WidgetSize;
  /** Hidden entirely when the backend reports the module disabled. */
  module?: ModuleName;
  /** Can be added several times with its own config; never added automatically, can also be removed (not only hidden). */
  multiple?: boolean;
  /**
   * A singleton that never appears by itself: added from the catalog, can be removed. Every widget
   * added after Phase 4 is one of these or `multiple` — see DEFAULT_WIDGET_IDS.
   */
  catalogOnly?: boolean;
  /** Multi widgets only: asked in the catalog before adding; without it an instance starts without config. */
  configForm?: ComponentType<WidgetConfigFormProps>;
  /**
   * Loads its own data. May render nothing (e.g. no data yet) — the grid then collapses its slot.
   * May be a React.lazy component; the grid shows a skeleton while its code loads.
   */
  component: ComponentType<WidgetProps>;
}
