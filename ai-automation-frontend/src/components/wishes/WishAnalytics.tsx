import { BarChart3, CheckCircle2, XCircle, Loader2, TrendingUp } from 'lucide-react';
import { PieChart, Pie, Cell, ResponsiveContainer, Tooltip } from 'recharts';
import { Card } from '../ui';
import { usePlatformStats } from '../../hooks/useLearning';
import { usePersistedState } from '../../hooks/usePersistedState';
import { useT } from '../../i18n';

const COLORS = ['var(--color-success)', 'var(--color-danger)', '#3b82f6'];

export function WishAnalytics() {
  const t = useT();
  const { data: stats } = usePlatformStats();
  const [expanded, setExpanded] = usePersistedState('wishes-show-analytics', false);
  const wishStats = stats?.wishStats;

  if (!wishStats || wishStats.total === 0) return null;

  const completionRate = Math.round((wishStats.completed / wishStats.total) * 100);
  const chartData = [
    { name: t.wishes.status.completed, value: wishStats.completed },
    { name: t.wishes.status.failed, value: wishStats.failed },
    { name: t.wishes.status.executing, value: wishStats.inProgress },
  ].filter((d) => d.value > 0);

  return (
    <div>
      <button
        onClick={() => setExpanded(!expanded)}
        className="flex items-center gap-1.5 text-xs text-text-tertiary hover:text-text transition-colors mb-2"
      >
        <BarChart3 className="w-3.5 h-3.5" />
        {expanded ? t.wishes.hideAnalytics : t.wishes.showAnalytics}
      </button>

      {expanded && (
        <Card>
          <div className="flex flex-col lg:flex-row gap-4">
            {/* Stat cards */}
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 flex-1">
              <StatCard
                icon={<TrendingUp className="w-4 h-4 text-accent" />}
                label={t.wishes.totalWishes}
                value={String(wishStats.total)}
              />
              <StatCard
                icon={<CheckCircle2 className="w-4 h-4 text-success" />}
                label={t.wishes.completionRate}
                value={`${completionRate}%`}
              />
              <StatCard
                icon={<Loader2 className="w-4 h-4 text-info" />}
                label={t.wishes.inProgressWishes}
                value={String(wishStats.inProgress)}
              />
              <StatCard
                icon={<XCircle className="w-4 h-4 text-danger" />}
                label={t.wishes.failedWishes}
                value={String(wishStats.failed)}
              />
            </div>

            {/* Pie chart */}
            {chartData.length > 0 && (
              <div className="w-full lg:w-44 shrink-0">
                <div className="h-32 relative">
                  <ResponsiveContainer width="100%" height="100%">
                    <PieChart>
                      <Pie data={chartData} cx="50%" cy="50%" innerRadius={30} outerRadius={48} dataKey="value" strokeWidth={0}>
                        {chartData.map((_, i) => <Cell key={i} fill={COLORS[i % COLORS.length]} />)}
                      </Pie>
                      <Tooltip contentStyle={{ backgroundColor: 'var(--color-surface-overlay)', border: '1px solid var(--color-border)', borderRadius: '8px', color: 'var(--color-text)', fontSize: 12 }} />
                    </PieChart>
                  </ResponsiveContainer>
                  <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
                    <p className="text-sm font-bold text-text">{wishStats.total}</p>
                  </div>
                </div>
                <div className="flex justify-center gap-2 -mt-1">
                  {chartData.map((d, i) => (
                    <div key={d.name} className="flex items-center gap-1 text-[10px] text-text-secondary">
                      <span className="w-1.5 h-1.5 rounded-full" style={{ backgroundColor: COLORS[i] }} />
                      {d.value}
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        </Card>
      )}
    </div>
  );
}

function StatCard({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return (
    <div className="flex items-center gap-3 px-3 py-2.5 rounded-lg bg-surface-secondary/50 border border-border/50">
      {icon}
      <div>
        <p className="text-lg font-bold text-text leading-tight">{value}</p>
        <p className="text-[10px] text-text-tertiary">{label}</p>
      </div>
    </div>
  );
}
