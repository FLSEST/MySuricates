import { Card, CardHeader } from '../ui';
import { useT } from '../../i18n';
import { useIntegrations } from '../../hooks/useIntegrations';
import type { IntegrationStats } from '../../types';

interface IntegrationStatsCardProps {
  data?: IntegrationStats[];
}

const FALLBACK_NAMES: Record<string, string> = {
  trello: 'Trello',
  jira: 'Jira',
  confluence: 'Confluence',
  'google-calendar': 'Google Calendar',
  'google-gmail': 'Gmail',
  gmail: 'Gmail',
};

function prettyName(id: string, lookup: Record<string, string>): string {
  if (lookup[id]) return lookup[id];
  if (FALLBACK_NAMES[id]) return FALLBACK_NAMES[id];
  return id
    .split('-')
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ');
}

export function IntegrationStatsCard({ data }: IntegrationStatsCardProps) {
  const t = useT();
  const { data: integrations } = useIntegrations();

  if (!data?.length) return null;

  const lookup: Record<string, string> = {};
  for (const i of integrations ?? []) {
    lookup[i.providerId] = i.displayName;
  }

  return (
    <Card>
      <CardHeader title={t.charts.integrationStats} />
      <div className="space-y-4">
        {data.map((p) => (
          <div key={p.provider}>
            <div className="flex items-center justify-between gap-2 mb-1.5">
              <span className="text-sm font-medium text-text">{prettyName(p.provider, lookup)}</span>
              <span className="text-xs text-text-tertiary shrink-0">
                {p.runCount} {t.common.runs}
              </span>
            </div>
            <div className="h-1.5 rounded-full bg-surface overflow-hidden mb-2">
              <div
                className="h-full rounded-full bg-success transition-all duration-500"
                style={{ width: `${p.successRate * 100}%` }}
              />
            </div>
            <div className="flex items-center justify-between text-[11px] text-text-tertiary">
              <span>{Math.round(p.successRate * 100)}% {t.dashboard.successRate.toLowerCase()}</span>
              <span>
                {p.totalTokens > 0
                  ? `${(p.totalTokens / 1000).toFixed(0)}k ${t.charts.tokens}`
                  : '—'}
              </span>
            </div>
          </div>
        ))}
      </div>
    </Card>
  );
}
