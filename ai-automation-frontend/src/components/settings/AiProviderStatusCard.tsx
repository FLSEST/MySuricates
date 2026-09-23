import { Card, CardHeader, Spinner } from '../ui';
import { AiProviderStatusList } from '../shared/AiProviderStatusList';
import { useAiProviderStatus, useRefreshAiProviderStatus } from '../../hooks/useSystem';
import { useT } from '../../i18n';

/**
 * Whether the AI CLIs are ready, always visible in the settings (f5a273ae) — the start page shows the
 * same list only while it matters there. The Simple Mode hides the provider choice, not this.
 */
export function AiProviderStatusCard() {
  const t = useT();
  const { data: report, isLoading } = useAiProviderStatus();
  const refresh = useRefreshAiProviderStatus();
  // An instance without the endpoint (older backend) or a failed request: no card instead of a broken one
  if (!report && !isLoading) return null;

  return (
    <Card data-testid="ai-provider-status-card">
      <CardHeader title={t.aiProviderStatus.title} />
      <p className="text-sm text-text-secondary mb-4">{t.aiProviderStatus.intro}</p>
      {report ? (
        <AiProviderStatusList report={report} onRefresh={() => refresh.mutate()} refreshing={refresh.isPending} />
      ) : (
        <Spinner text={t.common.loading} />
      )}
      <p className="text-xs text-text-tertiary mt-3">{t.aiProviderStatus.heuristic}</p>
    </Card>
  );
}
