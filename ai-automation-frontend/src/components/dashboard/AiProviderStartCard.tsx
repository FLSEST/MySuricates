import { Button, Card, CardHeader } from '../ui';
import { AiProviderStatusList } from '../shared/AiProviderStatusList';
import { aiStatusFingerprint, defaultProviderReady } from '../shared/aiProviderStatus';
import { useAiProviderStatus, useRefreshAiProviderStatus } from '../../hooks/useSystem';
import { useProjects } from '../../hooks/useProjects';
import { useSettingsStore } from '../../stores/settingsStore';
import { useT } from '../../i18n';

/**
 * The AI providers on the start page (f5a273ae), so nobody learns about a missing login from a failed
 * run: while the instance has no project yet — a newcomer sees "ready" or what is missing, with the
 * command — and whenever the default provider is not ready, because every AI block without its own
 * provider would fail. Hidden for exactly the status it was hidden at (some run MySuricates without
 * AI on purpose); a changed status shows it again. The settings always show the same list.
 */
export function AiProviderStartCard() {
  const t = useT().aiProviderStatus;
  const { data: report } = useAiProviderStatus();
  const { data: projects } = useProjects();
  const refresh = useRefreshAiProviderStatus();
  const dismissed = useSettingsStore((s) => s.aiStatusDismissed);
  const setDismissed = useSettingsStore((s) => s.setAiStatusDismissed);

  if (!report || projects === undefined) return null;
  const ready = defaultProviderReady(report);
  if (ready && projects.length > 0) return null;
  const fingerprint = aiStatusFingerprint(report);
  if (dismissed === fingerprint) return null;

  return (
    <Card data-testid="ai-provider-start" data-ready={ready}>
      <CardHeader
        title={ready ? t.titleReady : t.titleNotReady}
        action={
          <Button size="sm" variant="ghost" onClick={() => setDismissed(fingerprint)}>
            {t.dismiss}
          </Button>
        }
      />
      <p className="text-sm text-text-secondary mb-4">{t.intro}</p>
      <AiProviderStatusList report={report} onRefresh={() => refresh.mutate()} refreshing={refresh.isPending} />
    </Card>
  );
}
