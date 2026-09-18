/**
 * Insights charts as dashboard widgets (spec E4). Like DashboardDataWidgets they read the one
 * learning-dashboard query of the view, so they follow its filter. insightsWidgetDefs.ts loads this
 * module lazily: its code is only fetched once a view shows one of these widgets.
 */
import { BlockTypeUsageChart } from '../insights/BlockTypeUsageChart';
import { CostByWorkflowChart } from '../insights/CostByWorkflowChart';
import { CostOverTimeChart } from '../insights/CostOverTimeChart';
import { DurationOverTimeChart } from '../insights/DurationOverTimeChart';
import { FailureReasonsCard } from '../insights/FailureReasonsCard';
import { GateStatusChart } from '../insights/GateStatusChart';
import { TokenBreakdownChart } from '../insights/TokenBreakdownChart';
import { TriggerDistributionChart } from '../insights/TriggerDistributionChart';
import { WidgetSkeleton } from './WidgetSkeleton';
import { useDashboardData } from './dashboardDataContext';

export function GateStatusWidget() {
  const { data, isLoading } = useDashboardData();
  if (isLoading) return <WidgetSkeleton size="third" />;
  return <GateStatusChart data={data?.gateStatusSummary} />;
}

/** Renders nothing without failures, like on the Insights page. */
export function FailureReasonsWidget() {
  const { data, isLoading } = useDashboardData();
  if (isLoading) return <WidgetSkeleton size="third" />;
  return <FailureReasonsCard data={data?.failureReasons} />;
}

export function CostByWorkflowWidget() {
  const { data, isLoading } = useDashboardData();
  if (isLoading) return <WidgetSkeleton size="third" />;
  return <CostByWorkflowChart data={data?.costByWorkflow} />;
}

export function BlockTypeUsageWidget() {
  const { data, isLoading } = useDashboardData();
  if (isLoading) return <WidgetSkeleton size="third" />;
  return <BlockTypeUsageChart data={data?.blockTypeUsage} />;
}

/** Renders nothing without data, like on the Insights page. */
export function TriggerDistributionWidget() {
  const { data, isLoading } = useDashboardData();
  if (isLoading) return <WidgetSkeleton size="third" />;
  return <TriggerDistributionChart data={data?.triggerDistribution} />;
}

export function CostOverTimeWidget() {
  const { data, isLoading } = useDashboardData();
  if (isLoading) return <WidgetSkeleton size="third" />;
  return <CostOverTimeChart data={data?.timeSeries} />;
}

export function DurationOverTimeWidget() {
  const { data, isLoading } = useDashboardData();
  if (isLoading) return <WidgetSkeleton size="third" />;
  return <DurationOverTimeChart data={data?.timeSeries} />;
}

export function TokenBreakdownWidget() {
  const { data, isLoading } = useDashboardData();
  if (isLoading) return <WidgetSkeleton size="third" />;
  return <TokenBreakdownChart input={data?.totalInputTokens ?? 0} output={data?.totalOutputTokens ?? 0} />;
}
