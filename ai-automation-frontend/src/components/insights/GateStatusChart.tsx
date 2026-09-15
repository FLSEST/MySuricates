import { PieChart, Pie, Cell, ResponsiveContainer, Tooltip } from 'recharts';
import { ChartCard } from '../dashboard/ChartCard';
import { useT } from '../../i18n';
import type { GateStatusEntry } from '../../types';

const GATE_COLORS: Record<string, string> = {
  GREEN: 'var(--color-success)',
  YELLOW: 'var(--color-warning)',
  RED: 'var(--color-danger)',
};

export function GateStatusChart({ data }: { data?: GateStatusEntry[] }) {
  const t = useT();
  const labels: Record<string, string> = { GREEN: t.insights.gateGreen, YELLOW: t.insights.gateYellow, RED: t.insights.gateRed };
  const chartData = data?.map((d) => ({ name: labels[d.status] ?? d.status, value: d.count, status: d.status })) ?? [];
  const total = chartData.reduce((s, d) => s + d.value, 0);

  return (
    <ChartCard title={t.insights.gateStatus} isEmpty={total === 0}>
      <div className="h-48 relative">
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie data={chartData} cx="50%" cy="50%" innerRadius={50} outerRadius={72} dataKey="value" strokeWidth={0}>
              {chartData.map((d) => <Cell key={d.status} fill={GATE_COLORS[d.status] ?? 'var(--color-border)'} />)}
            </Pie>
            <Tooltip
              contentStyle={{ backgroundColor: 'var(--color-surface-overlay)', border: '1px solid var(--color-border)', borderRadius: '8px', color: 'var(--color-text)', fontSize: 12 }}
            />
          </PieChart>
        </ResponsiveContainer>
        <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
          <div className="text-center">
            <p className="text-lg font-bold text-text">{total}</p>
            <p className="text-[10px] text-text-tertiary">{t.insights.runs}</p>
          </div>
        </div>
      </div>
      <div className="flex justify-center gap-4 -mt-2">
        {chartData.map((d) => (
          <div key={d.status} className="flex items-center gap-1.5 text-xs text-text-secondary">
            <span className="w-2 h-2 rounded-full" style={{ backgroundColor: GATE_COLORS[d.status] }} />
            {d.name}: {d.value}
          </div>
        ))}
      </div>
    </ChartCard>
  );
}
