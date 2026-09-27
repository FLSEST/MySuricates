import { ChartCard } from './ChartCard';
import { ModelStatsChart } from './ModelStatsChart';
import { usePersistedState } from '../../hooks/usePersistedState';
import { useI18nStore, useT } from '../../i18n';
import { formatNumber, formatUsd } from '../../utils/localeFormat';
import type { ModelStats } from '../../types';

interface ModelStatsCardProps {
  data?: ModelStats[];
}

export function ModelStatsCard({ data = [] }: ModelStatsCardProps) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const [view, setView] = usePersistedState<'simple' | 'details'>('model-stats-view', 'simple');
  const showDetails = view === 'details';
  const maxTokens = Math.max(1, ...data.map((entry) => entry.totalTokens));
  const display = (value: string | null | undefined) => value?.trim() || t.charts.modelNotReported;

  return (
    <ChartCard title={t.charts.modelStats} isEmpty={!data.length}>
      <div role="group" aria-label={t.charts.modelView} className="inline-flex rounded-lg bg-surface p-1 gap-1 mb-3">
        {(['simple', 'details'] as const).map((mode) => (
          <button key={mode} type="button" aria-pressed={mode === (showDetails ? 'details' : 'simple')}
            onClick={() => setView(mode)}
            className={`px-3 py-1 rounded-md text-xs font-medium transition-colors ${mode === (showDetails ? 'details' : 'simple') ? 'bg-accent/15 text-accent' : 'text-text-tertiary hover:text-text'}`}>
            {mode === 'simple' ? t.charts.modelSimple : t.charts.modelDetails}
          </button>
        ))}
      </div>
      {showDetails ? <ul aria-label={t.charts.modelStats} className="max-h-80 overflow-y-auto space-y-4">
        {data.map((entry) => (
          <li key={JSON.stringify([entry.requestedModel, entry.provider, entry.model])}>
            <dl className="grid grid-cols-3 gap-2 text-xs">
              <div className="min-w-0">
                <dt className="text-text-tertiary">{t.charts.modelRequested}</dt>
                <dd className="mt-1 font-medium text-text break-all">{display(entry.requestedModel)}</dd>
              </div>
              <div className="min-w-0">
                <dt className="text-text-tertiary">{t.charts.modelProvider}</dt>
                <dd className="mt-1 font-medium text-text break-all">{display(entry.provider)}</dd>
              </div>
              <div className="min-w-0">
                <dt className="text-text-tertiary">{t.charts.modelReported}</dt>
                <dd className="mt-1 font-medium text-text break-all">{display(entry.model)}</dd>
              </div>
            </dl>
            <div aria-hidden="true" className="h-1.5 rounded-full bg-surface overflow-hidden my-2">
              <div className="h-full rounded-full bg-accent" style={{ width: `${entry.totalTokens / maxTokens * 100}%` }} />
            </div>
            <div className="flex flex-wrap justify-between gap-x-2 text-[11px] text-text-tertiary">
              <span>{formatNumber(entry.stepCount, locale)} {entry.stepCount === 1 ? t.charts.modelStep : t.charts.modelSteps}</span>
              <span>{formatNumber(entry.totalTokens, locale)} {t.charts.tokens} · {formatUsd(entry.totalCostUsd, locale)}</span>
            </div>
          </li>
        ))}
      </ul> : <ModelStatsChart data={data} />}
    </ChartCard>
  );
}
