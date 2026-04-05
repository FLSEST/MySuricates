import { PieChart, Pie, Cell, ResponsiveContainer, Tooltip } from 'recharts';
import { ChartCard } from '../dashboard/ChartCard';
import { useT } from '../../i18n';
import type { TodoStats } from '../../types';

const STATUS_COLORS = ['#f59e0b', '#3b82f6', 'var(--color-success)', 'var(--color-text-tertiary)'];
const SOURCE_COLORS = ['var(--color-accent)', '#8b5cf6', '#10b981'];

export function TodoStatsChart({ data }: { data?: TodoStats }) {
  const t = useT();
  if (!data) return null;

  const statusData = [
    { name: t.insights.todoOpen, value: data.open },
    { name: t.insights.todoInProgress, value: data.inProgress },
    { name: t.insights.todoDone, value: data.done },
    { name: t.insights.todoBacklog, value: data.backlog },
  ].filter((d) => d.value > 0);

  const sourceData = [
    { name: t.insights.todoFromUser, value: data.fromUser },
    { name: t.insights.todoFromAi, value: data.fromAi },
    { name: t.insights.todoFromWorkflow, value: data.fromWorkflow },
  ].filter((d) => d.value > 0);

  const total = data.open + data.inProgress + data.done + data.backlog;

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
      <ChartCard title={t.insights.todosByStatus} isEmpty={total === 0}>
        <div className="h-40 relative">
          <ResponsiveContainer width="100%" height="100%">
            <PieChart>
              <Pie data={statusData} cx="50%" cy="50%" innerRadius={40} outerRadius={60} dataKey="value" strokeWidth={0}>
                {statusData.map((_, i) => <Cell key={i} fill={STATUS_COLORS[i % STATUS_COLORS.length]} />)}
              </Pie>
              <Tooltip contentStyle={{ backgroundColor: 'var(--color-surface-overlay)', border: '1px solid var(--color-border)', borderRadius: '8px', color: 'var(--color-text)', fontSize: 12 }} />
            </PieChart>
          </ResponsiveContainer>
          <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
            <p className="text-lg font-bold text-text">{total}</p>
          </div>
        </div>
        <div className="flex flex-wrap justify-center gap-3 -mt-1">
          {statusData.map((d, i) => (
            <div key={d.name} className="flex items-center gap-1 text-[10px] text-text-secondary">
              <span className="w-1.5 h-1.5 rounded-full" style={{ backgroundColor: STATUS_COLORS[i] }} />
              {d.name}: {d.value}
            </div>
          ))}
        </div>
      </ChartCard>

      <ChartCard title={t.insights.todosBySource} isEmpty={!sourceData.length}>
        <div className="h-40 relative">
          <ResponsiveContainer width="100%" height="100%">
            <PieChart>
              <Pie data={sourceData} cx="50%" cy="50%" innerRadius={40} outerRadius={60} dataKey="value" strokeWidth={0}>
                {sourceData.map((_, i) => <Cell key={i} fill={SOURCE_COLORS[i % SOURCE_COLORS.length]} />)}
              </Pie>
              <Tooltip contentStyle={{ backgroundColor: 'var(--color-surface-overlay)', border: '1px solid var(--color-border)', borderRadius: '8px', color: 'var(--color-text)', fontSize: 12 }} />
            </PieChart>
          </ResponsiveContainer>
          <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
            <p className="text-lg font-bold text-text">{total}</p>
          </div>
        </div>
        <div className="flex flex-wrap justify-center gap-3 -mt-1">
          {sourceData.map((d, i) => (
            <div key={d.name} className="flex items-center gap-1 text-[10px] text-text-secondary">
              <span className="w-1.5 h-1.5 rounded-full" style={{ backgroundColor: SOURCE_COLORS[i] }} />
              {d.name}: {d.value}
            </div>
          ))}
        </div>
      </ChartCard>
    </div>
  );
}
