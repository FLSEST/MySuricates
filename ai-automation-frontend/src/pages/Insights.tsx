import { AlertTriangle, CheckCircle, Clock, DollarSign, Zap } from 'lucide-react';
import { Card, CardHeader, Spinner } from '../components/ui';
import { ErrorState } from '../components/shared/ErrorState';
import { useDashboard } from '../hooks/useLearning';
import { useT } from '../i18n';
import { formatDuration } from '../utils/formatTime';
import { useNavigate } from 'react-router-dom';

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

export function Insights() {
  const t = useT();
  const navigate = useNavigate();
  const { data, isLoading, error, refetch } = useDashboard();

  if (isLoading) {
    return (
      <div className="flex justify-center items-center h-64">
        <Spinner />
      </div>
    );
  }

  if (error) {
    return <ErrorState onRetry={refetch} />;
  }

  const stats = data!;
  const successRate = stats.successRate != null ? `${(stats.successRate * 100).toFixed(1)}%` : '—';
  const failRate = stats.successRate != null ? `${((1 - stats.successRate) * 100).toFixed(1)}%` : '—';
  const avgDuration = stats.averageDurationMs ? formatDuration(stats.averageDurationMs) : '—';

  return (
    <div className="p-6 space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-text">{t.insights.title}</h1>
        <p className="text-sm text-text-secondary mt-1">{t.insights.subtitle}</p>
      </div>

      {/* KPI row */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <StatCard
          icon={<CheckCircle className="w-5 h-5" />}
          label={t.insights.successRate}
          value={successRate}
          sub={`${stats.successfulRuns} / ${stats.totalRuns} ${t.insights.runs}`}
        />
        <StatCard
          icon={<AlertTriangle className="w-5 h-5" />}
          label={t.insights.failureRate}
          value={failRate}
          sub={`${stats.failedRuns} ${t.insights.failedRuns}`}
        />
        <StatCard
          icon={<Clock className="w-5 h-5" />}
          label={t.insights.avgDuration}
          value={avgDuration}
        />
        <StatCard
          icon={<DollarSign className="w-5 h-5" />}
          label={t.insights.costLast30d}
          value={stats.totalCostUsd30d != null ? `$${stats.totalCostUsd30d.toFixed(2)}` : '—'}
          sub={stats.totalTokens30d ? `${(stats.totalTokens30d / 1_000_000).toFixed(1)}M tokens` : undefined}
        />
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Top Workflows */}
        <Card padding={false}>
          <div className="p-5 pb-0">
            <CardHeader title={t.insights.topWorkflows} />
          </div>
          <div className="p-5 pt-0 space-y-2">
            {stats.topWorkflows && stats.topWorkflows.length > 0 ? (
              stats.topWorkflows.map((wf, i) => (
                <button
                  key={wf.workflowId}
                  onClick={() => navigate(`/workflows/${wf.workflowId}`)}
                  className="w-full flex items-center gap-3 py-2 hover:bg-surface rounded-lg px-2 -mx-2 transition-colors text-left"
                >
                  <span className="text-sm font-medium text-text-tertiary w-5">{i + 1}</span>
                  <span className="flex-1 text-sm text-text truncate">{wf.workflowName}</span>
                  <span className="text-xs font-medium text-accent bg-accent/10 px-2 py-0.5 rounded-full">
                    {wf.runCount} {t.insights.runs}
                  </span>
                </button>
              ))
            ) : (
              <p className="text-sm text-text-tertiary py-2">{t.insights.noData}</p>
            )}
          </div>
        </Card>

        {/* Overview stats */}
        <Card padding={false}>
          <div className="p-5 pb-0">
            <CardHeader title={t.insights.overview} />
          </div>
          <div className="p-5 pt-0 space-y-3">
            <div className="flex justify-between items-center py-1.5 border-b border-border">
              <span className="text-sm text-text-secondary">{t.insights.totalWorkflows}</span>
              <span className="text-sm font-medium text-text">{stats.totalWorkflows}</span>
            </div>
            <div className="flex justify-between items-center py-1.5 border-b border-border">
              <span className="text-sm text-text-secondary">{t.insights.totalRuns}</span>
              <span className="text-sm font-medium text-text">{stats.totalRuns}</span>
            </div>
            <div className="flex justify-between items-center py-1.5 border-b border-border">
              <span className="text-sm text-text-secondary">{t.insights.runsToday}</span>
              <span className="text-sm font-medium text-text">{stats.runsToday}</span>
            </div>

            {/* Success rate bar */}
            <div className="pt-2">
              <div className="flex justify-between text-xs text-text-secondary mb-1.5">
                <span>{t.insights.successRate}</span>
                <span>{successRate}</span>
              </div>
              <div className="h-2 rounded-full bg-border overflow-hidden">
                <div
                  className="h-full rounded-full bg-success transition-all"
                  style={{ width: `${((stats.successRate ?? 0) * 100).toFixed(1)}%` }}
                />
              </div>
            </div>
          </div>
        </Card>
      </div>

      {/* Provider stats */}
      {stats.providerStats && stats.providerStats.length > 0 && (
        <Card padding={false}>
          <div className="p-5 pb-0">
            <CardHeader title={t.insights.providerStats} />
          </div>
          <div className="p-5 pt-0">
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-xs text-text-tertiary border-b border-border">
                    <th className="pb-2 font-medium">{t.insights.provider}</th>
                    <th className="pb-2 font-medium text-right">{t.insights.runs}</th>
                    <th className="pb-2 font-medium text-right">{t.insights.successRate}</th>
                    <th className="pb-2 font-medium text-right">{t.insights.tokens}</th>
                    <th className="pb-2 font-medium text-right">{t.insights.cost}</th>
                  </tr>
                </thead>
                <tbody>
                  {stats.providerStats.map((p) => (
                    <tr key={p.provider} className="border-b border-border last:border-0">
                      <td className="py-2 font-medium text-text">{p.provider}</td>
                      <td className="py-2 text-right text-text-secondary">{p.runCount}</td>
                      <td className="py-2 text-right text-text-secondary">
                        {p.successRate != null ? `${(p.successRate * 100).toFixed(0)}%` : '—'}
                      </td>
                      <td className="py-2 text-right text-text-secondary">
                        {p.totalTokens != null ? `${(p.totalTokens / 1000).toFixed(1)}k` : '—'}
                      </td>
                      <td className="py-2 text-right text-text-secondary">
                        {p.totalCostUsd != null ? `$${p.totalCostUsd.toFixed(3)}` : '—'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </Card>
      )}

      {/* Model stats */}
      {stats.modelStats && stats.modelStats.length > 0 && (
        <Card padding={false}>
          <div className="p-5 pb-0">
            <CardHeader
              title={t.insights.modelStats}
              action={<Zap className="w-4 h-4 text-text-tertiary" />}
            />
          </div>
          <div className="p-5 pt-0">
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-xs text-text-tertiary border-b border-border">
                    <th className="pb-2 font-medium">{t.insights.model}</th>
                    <th className="pb-2 font-medium text-right">{t.insights.steps}</th>
                    <th className="pb-2 font-medium text-right">{t.insights.tokens}</th>
                    <th className="pb-2 font-medium text-right">{t.insights.cost}</th>
                  </tr>
                </thead>
                <tbody>
                  {stats.modelStats.map((m) => (
                    <tr key={m.model} className="border-b border-border last:border-0">
                      <td className="py-2 font-medium text-text">{m.model ?? '—'}</td>
                      <td className="py-2 text-right text-text-secondary">{m.stepCount}</td>
                      <td className="py-2 text-right text-text-secondary">
                        {m.totalTokens != null ? `${(m.totalTokens / 1000).toFixed(1)}k` : '—'}
                      </td>
                      <td className="py-2 text-right text-text-secondary">
                        {m.totalCostUsd != null ? `$${m.totalCostUsd.toFixed(3)}` : '—'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </Card>
      )}
    </div>
  );
}
