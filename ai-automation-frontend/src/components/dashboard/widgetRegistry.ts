import type { WidgetSize } from './dashboardLayout';
import { describedBy, type DashboardWidgetDef } from './widgetDefinition';
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

export type { DashboardWidgetDef, WidgetProps } from './widgetDefinition';

/** Registry order is the default layout; new standard singletons would be appended to customized layouts. */
export const DASHBOARD_WIDGETS: readonly DashboardWidgetDef[] = [
  {
    id: 'stat-active-projects', category: 'overview', size: 'stat', component: ActiveProjectsStat,
    label: (t) => t.dashboard.activeProjects, description: describedBy('statActiveProjects'),
  },
  {
    id: 'stat-total-workflows', category: 'overview', size: 'stat', component: TotalWorkflowsStat,
    label: (t) => t.dashboard.totalWorkflows, description: describedBy('statTotalWorkflows'),
  },
  {
    id: 'stat-runs-today', category: 'runs', size: 'stat', component: RunsTodayStat,
    label: (t) => t.dashboard.runsToday, description: describedBy('statRunsToday'),
  },
  {
    id: 'stat-success-rate', category: 'quality', size: 'stat', component: SuccessRateStat,
    label: (t) => t.dashboard.successRate, description: describedBy('statSuccessRate'),
  },
  {
    id: 'stat-total-cost', category: 'costs', size: 'stat', component: TotalCostStat,
    label: (t) => t.dashboard.totalCost, description: describedBy('statTotalCost'),
  },
  {
    id: 'stat-planned-today', category: 'planning', size: 'stat', component: PlannedTodayStat,
    label: (t) => t.dashboard.plannedToday, description: describedBy('statPlannedToday'),
  },
  {
    id: 'stat-planned-week', category: 'planning', size: 'stat', component: PlannedThisWeekStat,
    label: (t) => t.dashboard.plannedThisWeek, description: describedBy('statPlannedWeek'),
  },
  {
    id: 'stat-version', category: 'overview', size: 'stat', component: BuildInfoStat,
    label: (t) => t.dashboard.version, description: describedBy('statVersion'),
  },
  {
    id: 'chart-runs', category: 'runs', size: 'chart', component: RunsChartWidget,
    label: (t) => t.charts.runsOverTime, description: describedBy('chartRuns'),
  },
  {
    id: 'chart-success-rate', category: 'quality', size: 'chart', component: SuccessRateChartWidget,
    label: (t) => t.dashboard.successRateChart, description: describedBy('chartSuccessRate'),
  },
  {
    id: 'chart-todos', category: 'planning', size: 'chart', component: TodosDonutChart,
    label: (t) => t.dashboard.todoStatusChart, description: describedBy('chartTodos'),
  },
  {
    id: 'upcoming-schedules', category: 'planning', size: 'chart', component: UpcomingSchedulesCard,
    label: (t) => t.dashboard.upcomingSchedules, description: describedBy('upcomingSchedules'),
  },
  {
    id: 'failing-workflows', category: 'quality', size: 'chart', component: FailingWorkflowsWidget,
    label: (t) => t.dashboard.failingWorkflows, description: describedBy('failingWorkflows'),
  },
  {
    id: 'provider-stats', category: 'costs', size: 'third', component: ProviderStatsWidget,
    label: (t) => t.charts.providerStats, description: describedBy('providerStats'),
  },
  {
    id: 'integration-stats', category: 'runs', size: 'third', module: 'integrations', component: IntegrationStatsWidget,
    label: (t) => t.charts.integrationStats, description: describedBy('integrationStats'),
  },
  {
    id: 'model-stats', category: 'costs', size: 'third', component: ModelStatsWidget,
    label: (t) => t.charts.modelStats, description: describedBy('modelStats'),
  },
  {
    id: 'todos', category: 'planning', size: 'full', component: TodosWidget,
    label: (t) => t.dashboard.openTodos, description: describedBy('todos'),
  },
  {
    id: 'recent-runs', category: 'runs', size: 'twoThirds', component: RecentRunsCard,
    label: (t) => t.dashboard.recentRuns, description: describedBy('recentRuns'),
  },
  {
    id: 'quick-actions', category: 'overview', size: 'third', component: QuickActionsCard,
    label: (t) => t.dashboard.quickActions, description: describedBy('quickActions'),
  },
];

export const DASHBOARD_WIDGET_IDS: readonly string[] = DASHBOARD_WIDGETS.map((w) => w.id);

/** Standard singletons: what an empty view shows, in this order. */
export const DEFAULT_WIDGET_IDS: readonly string[] = DASHBOARD_WIDGETS.filter((w) => !w.multiple && !w.catalogOnly).map((w) => w.id);

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
