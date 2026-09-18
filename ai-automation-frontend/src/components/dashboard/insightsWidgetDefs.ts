import { lazy } from 'react';
import { describedBy, type DashboardWidgetDef } from './widgetDefinition';

// Lazy per registry entry; all of them share one chunk, fetched when the first one renders.
const loadInsightsWidgets = () => import('./InsightsWidgets');

const GateStatusWidget = lazy(() => loadInsightsWidgets().then((m) => ({ default: m.GateStatusWidget })));
const FailureReasonsWidget = lazy(() => loadInsightsWidgets().then((m) => ({ default: m.FailureReasonsWidget })));
const CostByWorkflowWidget = lazy(() => loadInsightsWidgets().then((m) => ({ default: m.CostByWorkflowWidget })));
const BlockTypeUsageWidget = lazy(() => loadInsightsWidgets().then((m) => ({ default: m.BlockTypeUsageWidget })));
const TriggerDistributionWidget = lazy(() => loadInsightsWidgets().then((m) => ({ default: m.TriggerDistributionWidget })));
const CostOverTimeWidget = lazy(() => loadInsightsWidgets().then((m) => ({ default: m.CostOverTimeWidget })));
const DurationOverTimeWidget = lazy(() => loadInsightsWidgets().then((m) => ({ default: m.DurationOverTimeWidget })));
const TokenBreakdownWidget = lazy(() => loadInsightsWidgets().then((m) => ({ default: m.TokenBreakdownWidget })));

/** Insights charts (spec E4). Catalog-only: they never appear in a view by themselves. */
export const INSIGHTS_WIDGETS: readonly DashboardWidgetDef[] = [
  {
    id: 'insights-gate-status', category: 'quality', size: 'third', catalogOnly: true, component: GateStatusWidget,
    label: (t) => t.insights.gateStatus, description: describedBy('insightsGateStatus'),
  },
  {
    id: 'insights-failure-reasons', category: 'quality', size: 'third', catalogOnly: true, component: FailureReasonsWidget,
    label: (t) => t.insights.failureAnalysis, description: describedBy('insightsFailureReasons'),
  },
  {
    id: 'insights-cost-by-workflow', category: 'costs', size: 'third', catalogOnly: true, component: CostByWorkflowWidget,
    label: (t) => t.insights.costByWorkflow, description: describedBy('insightsCostByWorkflow'),
  },
  {
    id: 'insights-block-types', category: 'runs', size: 'third', catalogOnly: true, component: BlockTypeUsageWidget,
    label: (t) => t.insights.blockTypeUsage, description: describedBy('insightsBlockTypes'),
  },
  {
    id: 'insights-triggers', category: 'runs', size: 'third', catalogOnly: true, component: TriggerDistributionWidget,
    label: (t) => t.insights.triggerDistribution, description: describedBy('insightsTriggers'),
  },
  {
    id: 'insights-cost-over-time', category: 'costs', size: 'third', catalogOnly: true, component: CostOverTimeWidget,
    label: (t) => t.insights.costOverTime, description: describedBy('insightsCostOverTime'),
  },
  {
    id: 'insights-duration-over-time', category: 'runs', size: 'third', catalogOnly: true, component: DurationOverTimeWidget,
    label: (t) => t.insights.durationOverTime, description: describedBy('insightsDurationOverTime'),
  },
  {
    id: 'insights-tokens', category: 'costs', size: 'third', catalogOnly: true, component: TokenBreakdownWidget,
    label: (t) => t.insights.tokenBreakdown, description: describedBy('insightsTokens'),
  },
];
