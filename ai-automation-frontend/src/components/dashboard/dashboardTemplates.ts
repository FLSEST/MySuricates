import type { TranslationKeys } from '../../i18n/en';

export type DashboardTemplateId = 'operations' | 'costs' | 'quality' | 'planning';

export interface DashboardTemplate {
  id: DashboardTemplateId;
  /** Becomes the name of the new view (the user can rename it). */
  name: (t: TranslationKeys) => string;
  /** Registry ids shown, in this order. Every other standard singleton is added hidden (templateWidgets). */
  widgetIds: readonly string[];
}

/** View templates (spec E4). They never contain workflow widgets (spec E3) or any other multi widget. */
export const DASHBOARD_TEMPLATES: readonly DashboardTemplate[] = [
  {
    id: 'operations',
    name: (t) => t.dashboard.templates.operations,
    widgetIds: ['failing-workflows', 'recent-runs', 'upcoming-schedules', 'todos'],
  },
  {
    id: 'costs',
    name: (t) => t.dashboard.templates.costs,
    widgetIds: ['stat-total-cost', 'insights-cost-over-time', 'insights-cost-by-workflow', 'insights-tokens', 'model-stats'],
  },
  {
    id: 'quality',
    name: (t) => t.dashboard.templates.quality,
    widgetIds: ['insights-gate-status', 'insights-failure-reasons', 'chart-success-rate'],
  },
  {
    id: 'planning',
    name: (t) => t.dashboard.templates.planning,
    widgetIds: ['stat-planned-today', 'stat-planned-week', 'upcoming-schedules'],
  },
];
