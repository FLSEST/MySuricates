import { useState } from 'react';
import {
  CheckCircle, AlertTriangle, Clock, DollarSign, Zap, TrendingUp,
  BarChart3, Layers, Cpu, AlertCircle, Sparkles, ListChecks, Wand2,
  MessageSquare, Link2,
} from 'lucide-react';
import { Card, CardHeader, Spinner } from '../components/ui';
import { ErrorState } from '../components/shared/ErrorState';
import { RunsChart } from '../components/dashboard/RunsChart';
import { SuccessRateChart } from '../components/dashboard/SuccessRateChart';
import { ProviderStatsCard } from '../components/dashboard/ProviderStatsCard';
import { IntegrationStatsCard } from '../components/dashboard/IntegrationStatsCard';
import { ModelStatsCard } from '../components/dashboard/ModelStatsCard';
import { TimeRangeSelector } from '../components/insights/TimeRangeSelector';
import { CostOverTimeChart } from '../components/insights/CostOverTimeChart';
import { DurationOverTimeChart } from '../components/insights/DurationOverTimeChart';
import { TokenBreakdownChart } from '../components/insights/TokenBreakdownChart';
import { GateStatusChart } from '../components/insights/GateStatusChart';
import { CostByWorkflowChart } from '../components/insights/CostByWorkflowChart';
import { TriggerDistributionChart } from '../components/insights/TriggerDistributionChart';
import { BlockTypeUsageChart } from '../components/insights/BlockTypeUsageChart';
import { FailureReasonsCard } from '../components/insights/FailureReasonsCard';
import { SkillUsageChart } from '../components/insights/SkillUsageChart';
import { PlatformNumbersGrid } from '../components/insights/PlatformNumbersGrid';
import { TodoStatsChart } from '../components/insights/TodoStatsChart';
import { WishStatsChart } from '../components/insights/WishStatsChart';
import { useDashboard, usePlatformStats } from '../hooks/useLearning';
import { useProjects } from '../hooks/useProjects';
import { useT } from '../i18n';
import { formatDuration } from '../utils/formatTime';

function StatCard({ icon, label, value, sub }: { icon: React.ReactNode; label: string; value: string; sub?: string }) {
  return (
    <Card>
      <div className="flex items-center gap-3">
        <div className="p-2 rounded-lg bg-accent/10 text-accent">{icon}</div>
        <div>
          <p className="text-xs text-text-tertiary">{label}</p>
          <p className="text-xl font-semibold text-text">{value}</p>
          {sub && <p className="text-xs text-text-secondary">{sub}</p>}
        </div>
      </div>
    </Card>
  );
}

function SectionHeader({ icon, title }: { icon: React.ReactNode; title: string }) {
  return (
    <div className="flex items-center gap-2 pt-2 pb-1 border-t border-border">
      <span className="text-text-tertiary">{icon}</span>
      <h2 className="text-sm font-semibold text-text-secondary uppercase tracking-wider">{title}</h2>
    </div>
  );
}

function formatCost(usd: number | undefined): string {
  if (!usd) return '$0.00';
  if (usd < 0.01) return `$${usd.toFixed(4)}`;
  return `$${usd.toFixed(2)}`;
}

export function Insights() {
  const t = useT();
  const [days, setDays] = useState(30);
  const [projectId, setProjectId] = useState<string>();

  const { data: stats, isLoading: dashLoading, error: dashError, refetch: dashRefetch } = useDashboard(days, projectId);
  const { data: platform, isLoading: platLoading } = usePlatformStats();
  const { data: projects } = useProjects();

  const isLoading = dashLoading || platLoading;

  if (isLoading) {
    return <div className="flex justify-center items-center h-64"><Spinner /></div>;
  }
  if (dashError) {
    return <ErrorState onRetry={dashRefetch} />;
  }

  const successRate = stats?.successRate != null ? `${(stats.successRate * 100).toFixed(1)}%` : '—';
  const failRate = stats?.successRate != null ? `${((1 - stats.successRate) * 100).toFixed(1)}%` : '—';
  const avgDuration = stats?.averageDurationMs ? formatDuration(stats.averageDurationMs) : '—';
  const costLabel = `${t.insights.cost} (${days}${t.insights.days30.replace('30', '')})`;

  // Automation degree
  const trig = stats?.triggerDistribution;
  const totalTriggers = trig ? trig.manual + trig.scheduled + trig.webhook + trig.completionTrigger + trig.todoTrigger : 0;
  const automatedCount = trig ? trig.scheduled + trig.webhook + trig.completionTrigger + trig.todoTrigger : 0;
  const automationPct = totalTriggers > 0 ? Math.round((automatedCount / totalTriggers) * 100) : 0;

  return (
    <div className="p-6 space-y-6 animate-fade-in">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-text">{t.insights.title}</h1>
          <p className="text-sm text-text-secondary mt-1">{t.insights.subtitle}</p>
        </div>
        <div className="flex items-center gap-3 shrink-0">
          <select
            className="text-xs bg-surface border border-border rounded-lg px-2.5 py-1.5 text-text-secondary"
            value={projectId ?? ''}
            onChange={(e) => setProjectId(e.target.value || undefined)}
          >
            <option value="">{t.insights.allProjects}</option>
            {projects?.filter((p) => !p.archivedAt).map((p) => (
              <option key={p.id} value={p.id}>{p.name}</option>
            ))}
          </select>
          <TimeRangeSelector value={days} onChange={setDays} />
        </div>
      </div>

      {/* Execution KPIs */}
      {stats && (
        <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-3">
          <StatCard icon={<CheckCircle className="w-5 h-5" />} label={t.insights.successRate} value={successRate} sub={`${stats.successfulRuns} / ${stats.totalRuns}`} />
          <StatCard icon={<AlertTriangle className="w-5 h-5" />} label={t.insights.failureRate} value={failRate} sub={`${stats.failedRuns} ${t.insights.failedRuns}`} />
          <StatCard icon={<Clock className="w-5 h-5" />} label={t.insights.avgDuration} value={avgDuration} />
          <StatCard icon={<DollarSign className="w-5 h-5" />} label={costLabel} value={formatCost(stats.totalCostUsd30d)} sub={stats.totalTokens30d ? `${(stats.totalTokens30d / 1_000_000).toFixed(1)}M tokens` : undefined} />
          <StatCard icon={<Zap className="w-5 h-5" />} label={t.insights.totalRuns} value={String(stats.totalRuns)} />
          <StatCard icon={<TrendingUp className="w-5 h-5" />} label={t.insights.runsToday} value={String(stats.runsToday)} />
        </div>
      )}

      {/* Platform Overview */}
      {platform && (
        <>
          <SectionHeader icon={<Layers className="w-4 h-4" />} title={t.insights.platformOverview} />
          <PlatformNumbersGrid data={platform} />
        </>
      )}

      {/* Time Trends */}
      {stats?.timeSeries && stats.timeSeries.length > 0 && (
        <>
          <SectionHeader icon={<TrendingUp className="w-4 h-4" />} title={t.insights.timeTrends} />
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <RunsChart data={stats.timeSeries} />
            <SuccessRateChart data={stats.timeSeries} />
          </div>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <CostOverTimeChart data={stats.timeSeries} />
            <DurationOverTimeChart data={stats.timeSeries} />
          </div>
        </>
      )}

      {/* Execution Analysis */}
      {stats && (
        <>
          <SectionHeader icon={<BarChart3 className="w-4 h-4" />} title={t.insights.executionAnalysis} />
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <TriggerDistributionChart data={stats.triggerDistribution} />
            <BlockTypeUsageChart data={stats.blockTypeUsage} />
            <GateStatusChart data={stats.gateStatusSummary} />
          </div>
        </>
      )}

      {/* Models, Provider & Tokens */}
      {stats && (
        <>
          <SectionHeader icon={<Cpu className="w-4 h-4" />} title={t.insights.modelsAndTokens} />
          <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-4">
            <ModelStatsCard data={stats.modelStats} />
            <ProviderStatsCard data={stats.providerStats} />
            <IntegrationStatsCard data={stats.integrationStats} />
            <TokenBreakdownChart input={stats.totalInputTokens ?? 0} output={stats.totalOutputTokens ?? 0} />
          </div>
        </>
      )}

      {/* Cost Analysis */}
      {stats?.costByWorkflow && stats.costByWorkflow.length > 0 && (
        <>
          <SectionHeader icon={<DollarSign className="w-4 h-4" />} title={t.insights.costAnalysis} />
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <CostByWorkflowChart data={stats.costByWorkflow} />
            <Card>
              <CardHeader title={t.insights.topWorkflows} />
              <div className="space-y-2">
                {stats.topWorkflows?.map((wf, i) => (
                  <div key={wf.workflowId} className="flex items-center gap-3 py-1">
                    <span className="text-sm font-medium text-text-tertiary w-5">{i + 1}</span>
                    <span className="flex-1 text-sm text-text truncate">{wf.workflowName}</span>
                    <span className="text-xs font-medium text-accent bg-accent/10 px-2 py-0.5 rounded-full">
                      {wf.runCount} {t.insights.runs}
                    </span>
                  </div>
                ))}
              </div>
            </Card>
          </div>
        </>
      )}

      {/* Failure Analysis */}
      {stats?.failureReasons && stats.failureReasons.length > 0 && (
        <>
          <SectionHeader icon={<AlertCircle className="w-4 h-4" />} title={t.insights.failureAnalysis} />
          <FailureReasonsCard data={stats.failureReasons} />
        </>
      )}

      {/* Skills & Automation */}
      {platform && (platform.topSkillsByUsage?.length > 0 || automationPct > 0) && (
        <>
          <SectionHeader icon={<Sparkles className="w-4 h-4" />} title={t.insights.skillsAndAutomation} />
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <SkillUsageChart data={platform.topSkillsByUsage} />
            <Card>
              <CardHeader title={t.insights.automationDegree} />
              <div className="space-y-4">
                <div>
                  <div className="flex justify-between text-xs text-text-secondary mb-1.5">
                    <span>{t.insights.automated}</span>
                    <span>{automationPct}%</span>
                  </div>
                  <div className="h-3 rounded-full bg-border overflow-hidden">
                    <div className="h-full rounded-full bg-success transition-all" style={{ width: `${automationPct}%` }} />
                  </div>
                </div>
                <div className="flex justify-between text-sm">
                  <span className="text-text-secondary">{t.insights.automated}</span>
                  <span className="font-medium text-text">{automatedCount} {t.insights.runs}</span>
                </div>
                <div className="flex justify-between text-sm">
                  <span className="text-text-secondary">{t.insights.manual}</span>
                  <span className="font-medium text-text">{trig?.manual ?? 0} {t.insights.runs}</span>
                </div>
              </div>
            </Card>
          </div>
        </>
      )}

      {/* Todos */}
      {platform?.todoStats && (platform.todoStats.open + platform.todoStats.inProgress + platform.todoStats.done + platform.todoStats.backlog > 0) && (
        <>
          <SectionHeader icon={<ListChecks className="w-4 h-4" />} title={t.insights.todosSection} />
          <TodoStatsChart data={platform.todoStats} />
        </>
      )}

      {/* Wishes */}
      {platform?.wishStats && platform.wishStats.total > 0 && (
        <>
          <SectionHeader icon={<Wand2 className="w-4 h-4" />} title={t.insights.wishesSection} />
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <WishStatsChart data={platform.wishStats} />
          </div>
        </>
      )}

      {/* Assistant */}
      {platform?.assistantStats && platform.assistantStats.totalSessions > 0 && (
        <>
          <SectionHeader icon={<MessageSquare className="w-4 h-4" />} title={t.insights.assistantSection} />
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            <StatCard icon={<MessageSquare className="w-5 h-5" />} label={t.insights.sessions} value={String(platform.assistantStats.totalSessions)} />
            <StatCard icon={<BarChart3 className="w-5 h-5" />} label={t.insights.avgMessages} value={(platform.assistantStats.avgMessagesPerSession ?? 0).toFixed(1)} />
          </div>
        </>
      )}

      {/* Chains */}
      {platform?.chainStats && platform.chainStats.totalChains > 0 && (
        <>
          <SectionHeader icon={<Link2 className="w-4 h-4" />} title={t.insights.chainsSection} />
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            <StatCard icon={<Link2 className="w-5 h-5" />} label={t.insights.chains} value={String(platform.chainStats.totalChains)} />
            <StatCard icon={<Zap className="w-5 h-5" />} label={t.insights.chainRuns} value={String(platform.chainStats.totalRuns)} />
            <StatCard icon={<CheckCircle className="w-5 h-5" />} label={t.insights.successfulRuns} value={String(platform.chainStats.successfulRuns)} />
            <StatCard icon={<AlertTriangle className="w-5 h-5" />} label={t.insights.failedRuns} value={String(platform.chainStats.failedRuns)} />
          </div>
        </>
      )}
    </div>
  );
}
