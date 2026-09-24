import { FlaskConical } from 'lucide-react';
import { Badge } from '../ui';
import { useIntegrations } from '../../hooks/useIntegrations';
import { useT } from '../../i18n';
import type { IntegrationProviderInfo } from '../../types';

/**
 * How far a provider was checked against the real service. Only SIMULATED gets a visible mark:
 * a provider tested against simulated responses alone may still disagree with the real API.
 * A missing field (older backend) counts as unknown and shows nothing.
 */
function isSimulated(provider: IntegrationProviderInfo | undefined): boolean {
  return provider?.verification === 'SIMULATED';
}

/** Small badge next to the connection status on the integrations page. */
export function VerificationBadge({ provider }: { provider: IntegrationProviderInfo }) {
  const t = useT();
  if (isSimulated(provider)) {
    return (
      <span title={t.integrations.verificationSimulatedHint} data-testid="verification-simulated">
        <Badge variant="info">
          <FlaskConical className="w-3 h-3" />
          {t.integrations.verificationSimulated}
        </Badge>
      </span>
    );
  }
  return null;
}

/** Full sentence for the detail and setup area; for VERIFIED it names what was checked. */
export function VerificationNotice({ provider }: { provider: IntegrationProviderInfo }) {
  const t = useT();
  if (isSimulated(provider)) {
    return (
      <p className="flex items-start gap-2 rounded-md border border-info/20 bg-info-subtle px-3 py-2 text-xs text-info">
        <FlaskConical className="mt-0.5 h-3.5 w-3.5 shrink-0" />
        <span>{t.integrations.verificationSimulatedHint}</span>
      </p>
    );
  }
  if (provider.verification === 'VERIFIED' && provider.verifiedAgainst) {
    return (
      <p className="text-xs text-text-tertiary">
        {t.integrations.verificationVerified.replace('{target}', provider.verifiedAgainst)}
      </p>
    );
  }
  return null;
}

function providerIdFromConfig(configJson: string): string | null {
  try {
    const provider = JSON.parse(configJson)?.provider;
    return typeof provider === 'string' ? provider : null;
  } catch {
    return null;
  }
}

/** In the block editor: warns when an integration block uses a provider that is only simulated. */
export function IntegrationBlockVerificationNotice({ configJson }: { configJson: string }) {
  const { data: providers } = useIntegrations();
  const providerId = providerIdFromConfig(configJson);
  const provider = providers?.find((p) => p.providerId === providerId);
  if (!provider || !isSimulated(provider)) return null;
  return <VerificationNotice provider={provider} />;
}
