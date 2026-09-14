/**
 * Dashboard widgets over the shared learning-dashboard query. The presentational cards stay
 * prop-based (Insights passes its own time range); these wrappers bind them to useDashboard(),
 * whose query cache all widgets share — one request, however many widgets.
 */
import { RunsChart } from './RunsChart';
import { SuccessRateChart } from './SuccessRateChart';
import { FailingWorkflowsCard } from './FailingWorkflowsCard';
import { ProviderStatsCard } from './ProviderStatsCard';
import { IntegrationStatsCard } from './IntegrationStatsCard';
import { ModelStatsCard } from './ModelStatsCard';
import { useDashboard } from '../../hooks/useLearning';

export function RunsChartWidget() {
  const { data } = useDashboard();
  return <RunsChart data={data?.timeSeries} />;
}

export function SuccessRateChartWidget() {
  const { data } = useDashboard();
  return <SuccessRateChart data={data?.timeSeries} />;
}

export function FailingWorkflowsWidget() {
  const { data } = useDashboard();
  return <FailingWorkflowsCard recentRuns={data?.recentRuns} />;
}

export function ProviderStatsWidget() {
  const { data } = useDashboard();
  return <ProviderStatsCard data={data?.providerStats} />;
}

export function IntegrationStatsWidget() {
  const { data } = useDashboard();
  return <IntegrationStatsCard data={data?.integrationStats} />;
}

/** Renders nothing without data, like the provider and integration cards. */
export function ModelStatsWidget() {
  const { data } = useDashboard();
  if (!data?.modelStats?.length) return null;
  return <ModelStatsCard data={data.modelStats} />;
}
