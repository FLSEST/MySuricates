import { PieChart, Pie, Cell, ResponsiveContainer, Tooltip } from 'recharts';
import { ChartCard } from '../dashboard/ChartCard';
import { useT } from '../../i18n';

const COLORS = ['var(--color-accent)', 'var(--color-chart-1)'];

function formatTokens(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`;
  return String(n);
}

export function TokenBreakdownChart({ input, output }: { input: number; output: number }) {
  const t = useT();
  const total = input + output;
  const data = [
    { name: t.insights.inputTokens, value: input },
    { name: t.insights.outputTokens, value: output },
  ];

  return (
    <ChartCard title={t.insights.tokenBreakdown} isEmpty={total === 0}>
      <div className="h-48 relative">
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie data={data} cx="50%" cy="50%" innerRadius={50} outerRadius={72} dataKey="value" strokeWidth={0}>
              {data.map((_, i) => <Cell key={i} fill={COLORS[i]} />)}
            </Pie>
            <Tooltip
              contentStyle={{ backgroundColor: 'var(--color-surface-overlay)', border: '1px solid var(--color-border)', borderRadius: '8px', color: 'var(--color-text)', fontSize: 12 }}
              formatter={(value) => [formatTokens(Number(value ?? 0))]}
            />
          </PieChart>
        </ResponsiveContainer>
        <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
          <div className="text-center">
            <p className="text-lg font-bold text-text">{formatTokens(total)}</p>
            <p className="text-[10px] text-text-tertiary">{t.insights.tokens}</p>
          </div>
        </div>
      </div>
      <div className="flex justify-center gap-4 -mt-2">
        {data.map((d, i) => (
          <div key={d.name} className="flex items-center gap-1.5 text-xs text-text-secondary">
            <span className="w-2 h-2 rounded-full" style={{ backgroundColor: COLORS[i] }} />
            {d.name}: {formatTokens(d.value)}
          </div>
        ))}
      </div>
    </ChartCard>
  );
}
