import { Coins, Cpu, Zap, Wrench } from 'lucide-react';
import { Card, CardHeader, Badge } from '../ui';
import { useRunInsights } from '../../hooks/useInsights';
import { useI18nStore, useT, type Locale } from '../../i18n';
import { formatDecimal, formatUsd } from '../../utils/localeFormat';

function formatTokens(count: number, locale: Locale): string {
  if (count >= 1_000_000) return `${formatDecimal(count / 1_000_000, locale)}M`;
  if (count >= 1_000) return `${formatDecimal(count / 1_000, locale)}k`;
  return String(count);
}

export function InsightsPanel({ runId }: { runId: string }) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const { data: insights, isLoading } = useRunInsights(runId);

  if (isLoading || !insights || insights.totalTokens === 0) return null;

  return (
    <Card>
      <CardHeader title={t.insights.title} />

      {/* Summary stats */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mb-4">
        <div className="flex items-center gap-2 p-2 rounded-lg bg-surface">
          <Coins className="w-4 h-4 text-warning shrink-0" />
          <div>
            <p className="text-xs text-text-tertiary">{t.insights.totalCost}</p>
            <p className="text-sm font-semibold text-text">{formatUsd(insights.totalCostUsd, locale)}</p>
          </div>
        </div>
        <div className="flex items-center gap-2 p-2 rounded-lg bg-surface">
          <Cpu className="w-4 h-4 text-accent shrink-0" />
          <div>
            <p className="text-xs text-text-tertiary">{t.insights.totalTokens}</p>
            <p className="text-sm font-semibold text-text">{formatTokens(insights.totalTokens, locale)}</p>
          </div>
        </div>
        <div className="flex items-center gap-2 p-2 rounded-lg bg-surface">
          <Zap className="w-4 h-4 text-success shrink-0" />
          <div>
            <p className="text-xs text-text-tertiary">{t.insights.inputTokens}</p>
            <p className="text-sm font-semibold text-text">{formatTokens(insights.totalInputTokens, locale)}</p>
          </div>
        </div>
        <div className="flex items-center gap-2 p-2 rounded-lg bg-surface">
          <Zap className="w-4 h-4 text-danger shrink-0" />
          <div>
            <p className="text-xs text-text-tertiary">{t.insights.outputTokens}</p>
            <p className="text-sm font-semibold text-text">{formatTokens(insights.totalOutputTokens, locale)}</p>
          </div>
        </div>
      </div>

      {/* Per-step breakdown */}
      {insights.steps.some(s => s.costUsd > 0 || s.inputTokens > 0) && (
        <div className="space-y-2">
          <h4 className="text-xs font-semibold uppercase tracking-wider text-text-tertiary">
            {t.insights.perStep}
          </h4>
          {insights.steps.map((step) => (
            (step.costUsd > 0 || step.inputTokens > 0) && (
              <div key={step.stepIndex} className="flex items-center justify-between py-1.5 px-2 rounded-sm bg-surface text-sm">
                <div className="flex items-center gap-2">
                  <span className="text-text-tertiary font-mono text-xs">#{step.stepIndex + 1}</span>
                  {step.model && <Badge variant="default">{step.model}</Badge>}
                  {step.provider && <Badge variant="accent">{step.provider}</Badge>}
                </div>
                <div className="flex items-center gap-4 text-xs text-text-secondary">
                  {step.toolUseCount != null && step.toolUseCount > 0 && (
                    <div className="flex items-center gap-1 flex-wrap">
                      <Wrench className="w-3 h-3 shrink-0" />
                      {(step.toolNames || []).slice(0, 5).map((name, i) => (
                        <span key={i} className="px-1 py-0.5 rounded-sm text-[10px] bg-surface-hover">{name}</span>
                      ))}
                      {(step.toolNames || []).length > 5 && (
                        <span
                          className="px-1 py-0.5 rounded-sm text-[10px] bg-surface-hover text-text-tertiary"
                          title={(step.toolNames || []).slice(5).join(', ')}
                        >
                          +{(step.toolNames || []).length - 5}
                        </span>
                      )}
                    </div>
                  )}
                  <span>{formatTokens(step.inputTokens + step.outputTokens, locale)} tok</span>
                  <span className="font-medium text-text">{formatUsd(step.costUsd, locale)}</span>
                </div>
              </div>
            )
          ))}
        </div>
      )}
    </Card>
  );
}
