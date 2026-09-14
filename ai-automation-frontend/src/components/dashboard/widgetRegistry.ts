import type { ComponentType } from 'react';
import type { TranslationKeys } from '../../i18n/en';
import type { ModuleName } from '../../types';
import type { WidgetSize } from './dashboardLayout';
import {
  ActiveProjectsStat, TotalWorkflowsStat, RunsTodayStat, SuccessRateStat, TotalCostStat,
  PlannedTodayStat, PlannedThisWeekStat, BuildInfoStat,
} from './StatWidgets';
import {
  RunsChartWidget, SuccessRateChartWidget, FailingWorkflowsWidget,
  ProviderStatsWidget, IntegrationStatsWidget, ModelStatsWidget,
} from './DashboardDataWidgets';
import { TodosDonutChart } from './TodosDonutChart';
import { UpcomingSchedulesCard } from './UpcomingSchedulesCard';
import { TodosWidget } from './TodosWidget';
import { RecentRunsCard } from './RecentRunsCard';
import { QuickActionsCard } from './QuickActionsCard';

export interface DashboardWidgetDef {
  /** Stable id — persisted in users' layouts, never rename. */
  id: string;
  /** Unique label for the edit mode (hidden list, drag handles). */
  label: (t: TranslationKeys) => string;
  size: WidgetSize;
  /** Hidden entirely when the backend reports the module disabled. */
  module?: ModuleName;
  /**
   * Loads its own data. May render nothing (e.g. no data yet) — the grid then collapses its slot.
   */
  component: ComponentType;
}

/** Registry order is the default layout; new widgets are appended to customized layouts. */
export const DASHBOARD_WIDGETS: readonly DashboardWidgetDef[] = [
  { id: 'stat-active-projects', label: (t) => t.dashboard.activeProjects, size: 'stat', component: ActiveProjectsStat },
  { id: 'stat-total-workflows', label: (t) => t.dashboard.totalWorkflows, size: 'stat', component: TotalWorkflowsStat },
  { id: 'stat-runs-today', label: (t) => t.dashboard.runsToday, size: 'stat', component: RunsTodayStat },
  { id: 'stat-success-rate', label: (t) => t.dashboard.successRate, size: 'stat', component: SuccessRateStat },
  { id: 'stat-total-cost', label: (t) => t.dashboard.totalCost, size: 'stat', component: TotalCostStat },
  { id: 'stat-planned-today', label: (t) => t.dashboard.plannedToday, size: 'stat', component: PlannedTodayStat },
  { id: 'stat-planned-week', label: (t) => t.dashboard.plannedThisWeek, size: 'stat', component: PlannedThisWeekStat },
  { id: 'stat-version', label: (t) => t.dashboard.version, size: 'stat', component: BuildInfoStat },
  { id: 'chart-runs', label: (t) => t.charts.runsOverTime, size: 'chart', component: RunsChartWidget },
  { id: 'chart-success-rate', label: (t) => t.dashboard.successRateChart, size: 'chart', component: SuccessRateChartWidget },
  { id: 'chart-todos', label: (t) => t.dashboard.todoStatusChart, size: 'chart', component: TodosDonutChart },
  { id: 'upcoming-schedules', label: (t) => t.dashboard.upcomingSchedules, size: 'chart', component: UpcomingSchedulesCard },
  { id: 'failing-workflows', label: (t) => t.dashboard.failingWorkflows, size: 'chart', component: FailingWorkflowsWidget },
  { id: 'provider-stats', label: (t) => t.charts.providerStats, size: 'third', component: ProviderStatsWidget },
  { id: 'integration-stats', label: (t) => t.charts.integrationStats, size: 'third', module: 'integrations', component: IntegrationStatsWidget },
  { id: 'model-stats', label: (t) => t.charts.modelStats, size: 'third', component: ModelStatsWidget },
  { id: 'todos', label: (t) => t.dashboard.openTodos, size: 'full', component: TodosWidget },
  { id: 'recent-runs', label: (t) => t.dashboard.recentRuns, size: 'twoThirds', component: RecentRunsCard },
  { id: 'quick-actions', label: (t) => t.dashboard.quickActions, size: 'third', component: QuickActionsCard },
];

export const DASHBOARD_WIDGET_IDS: readonly string[] = DASHBOARD_WIDGETS.map((w) => w.id);

/**
 * Flex-basis per size (minus the 1rem gap, flex-grow fills the rest). Mirrors the former
 * fixed rows: 8 stats / 5 charts / 3 thirds per row on xl. Rows with fewer widgets widen them
 * instead of leaving holes.
 */
export const WIDGET_SIZE_CLASSES: Record<WidgetSize, string> = {
  stat: 'basis-[calc(50%_-_1rem)] sm:basis-[calc(25%_-_1rem)] xl:basis-[calc(12.5%_-_1rem)]',
  chart: 'basis-full md:basis-[calc(50%_-_1rem)] xl:basis-[calc(20%_-_1rem)]',
  third: 'basis-full md:basis-[calc(50%_-_1rem)] lg:basis-[calc(33.333%_-_1rem)]',
  twoThirds: 'basis-full lg:basis-[calc(66.667%_-_1rem)]',
  full: 'basis-full',
};
