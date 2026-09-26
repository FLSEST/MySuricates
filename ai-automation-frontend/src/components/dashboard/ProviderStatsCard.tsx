import { Card, CardHeader } from '../ui';
import { useI18nStore, useT } from '../../i18n';
import { formatUsd } from '../../utils/localeFormat';
import type { ProviderStats } from '../../types';

interface ProviderStatsCardProps {
  data?: ProviderStats[];
}

const PROVIDER_COLORS: Record<string, string> = {
  CLAUDE: 'bg-accent',
  CODEX: 'bg-chart-3',
  PI: 'bg-chart-2',
};

function providerColor(provider: string): string {
  return PROVIDER_COLORS[provider.toUpperCase()] ?? 'bg-success';
}

export function ProviderStatsCard({ data }: ProviderStatsCardProps) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);

  if (!data?.length) return null;

  return (
    <Card>
      <CardHeader title={t.charts.providerStats} />
      <div className="space-y-4">
        {data.map((p) => (
          <div key={p.provider}>
            <div className="flex items-center justify-between gap-2 mb-1.5">
              <span className="text-sm font-medium text-text">{p.provider}</span>
              <span className="text-xs text-text-tertiary shrink-0">
                {p.runCount} {t.common.runs}
              </span>
            </div>
            {/* Success rate bar */}
            <div className="h-1.5 rounded-full bg-surface overflow-hidden mb-2">
              <div
                className={`h-full rounded-full transition-all duration-500 ${providerColor(p.provider)}`}
                style={{ width: `${p.successRate * 100}%` }}
              />
            </div>
            <div className="flex items-center justify-between text-[11px] text-text-tertiary">
              <span>{Math.round(p.successRate * 100)}% {t.dashboard.successRate.toLowerCase()}</span>
              <span>
                {p.totalTokens > 0
                  ? `${(p.totalTokens / 1000).toFixed(0)}k ${t.charts.tokens}`
                  : '—'}
                {p.totalCostUsd > 0
                  ? ` · ${formatUsd(p.totalCostUsd, locale)}`
                  : ''}
              </span>
            </div>
          </div>
        ))}
      </div>
    </Card>
  );
}
