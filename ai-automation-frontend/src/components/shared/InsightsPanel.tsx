import { Coins, Cpu, Zap, Wrench } from 'lucide-react';
import { Card, CardHeader, Badge } from '../ui';
import { useRunInsights } from '../../hooks/useInsights';
import { useI18nStore, useT, type Locale } from '../../i18n';
import { formatTokenCount, formatUsd } from '../../utils/localeFormat';


/**
 * Claude reports the input its prompt cache served apart from inputTokens — with Claude Code's cached
 * system prompt that is nearly all of it (TODO 20adda30). Older backends and other providers send no
 * cache figures: then there is nothing to add and no label.
 */
function CacheTokens({ read, write, locale, className = '' }: {
  read?: number; write?: number; locale: Locale; className?: string;
}) {
  const t = useT();
  const total = (read ?? 0) + (write ?? 0);
  if (total <= 0) return null;
  const hint = t.insights.cacheTokensHint
    .replace('{read}', formatTokenCount(read ?? 0, locale))
    .replace('{write}', formatTokenCount(write ?? 0, locale));
  return (
    <span className={`text-text-tertiary ${className}`} title={hint}>
      +{formatTokenCount(total, locale)} {t.insights.cacheTokens}
    </span>
  );
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
            <p className="text-sm font-semibold text-text">{formatTokenCount(insights.totalTokens, locale)}</p>
          </div>
        </div>
        <div className="flex items-center gap-2 p-2 rounded-lg bg-surface">
          <Zap className="w-4 h-4 text-success shrink-0" />
          <div>
            <p className="text-xs text-text-tertiary">{t.insights.inputTokens}</p>
            <p className="text-sm font-semibold text-text">{formatTokenCount(insights.totalInputTokens, locale)}</p>
            <CacheTokens read={insights.totalCacheReadInputTokens} write={insights.totalCacheCreationInputTokens} locale={locale}
              className="block text-[11px]" />
          </div>
        </div>
        <div className="flex items-center gap-2 p-2 rounded-lg bg-surface">
          <Zap className="w-4 h-4 text-danger shrink-0" />
          <div>
            <p className="text-xs text-text-tertiary">{t.insights.outputTokens}</p>
            <p className="text-sm font-semibold text-text">{formatTokenCount(insights.totalOutputTokens, locale)}</p>
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
                  <span>{formatTokenCount(step.inputTokens + step.outputTokens, locale)} tok</span>
                  <CacheTokens read={step.cacheReadInputTokens} write={step.cacheCreationInputTokens} locale={locale} />
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
