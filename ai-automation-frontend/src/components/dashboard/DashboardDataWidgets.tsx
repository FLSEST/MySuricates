/**
 * Dashboard widgets over the learning-dashboard data of the rendered view. The presentational cards
 * stay prop-based (Insights passes its own time range); these wrappers read the one query that
 * DashboardDataProvider runs for the view's filter — one subscription, however many widgets.
 */
import { RunsChart } from './RunsChart';
import { SuccessRateChart } from './SuccessRateChart';
import { FailingWorkflowsCard } from './FailingWorkflowsCard';
import { ProviderStatsCard } from './ProviderStatsCard';
import { IntegrationStatsCard } from './IntegrationStatsCard';
import { ModelStatsCard } from './ModelStatsCard';
import { WidgetSkeleton } from './WidgetSkeleton';
import { useDashboardData } from './dashboardDataContext';

export function RunsChartWidget() {
  const { data, isLoading } = useDashboardData();
  if (isLoading) return <WidgetSkeleton size="chart" />;
  return <RunsChart data={data?.timeSeries} />;
}

export function SuccessRateChartWidget() {
  const { data, isLoading } = useDashboardData();
  if (isLoading) return <WidgetSkeleton size="chart" />;
  return <SuccessRateChart data={data?.timeSeries} />;
}

export function FailingWorkflowsWidget() {
  const { data, isLoading } = useDashboardData();
  if (isLoading) return <WidgetSkeleton size="chart" />;
  return <FailingWorkflowsCard recentRuns={data?.recentRuns} />;
}

export function ProviderStatsWidget() {
  const { data, isLoading } = useDashboardData();
  if (isLoading) return <WidgetSkeleton size="third" />;
  return <ProviderStatsCard data={data?.providerStats} />;
}

export function IntegrationStatsWidget() {
  const { data, isLoading } = useDashboardData();
  if (isLoading) return <WidgetSkeleton size="third" />;
  return <IntegrationStatsCard data={data?.integrationStats} />;
}

/** Renders nothing without data, like the provider and integration cards. */
export function ModelStatsWidget() {
  const { data, isLoading } = useDashboardData();
  if (isLoading) return <WidgetSkeleton size="third" />;
  if (!data?.modelStats?.length) return null;
  return <ModelStatsCard data={data.modelStats} />;
}
