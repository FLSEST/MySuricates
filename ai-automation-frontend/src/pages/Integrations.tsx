import { Hint } from '../components/hints/Hint';
import { useState } from 'react';
import { Plug } from 'lucide-react';
import { Button, Spinner, ConfirmModal } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { useIntegrations, useConnectIntegration, useDisconnectIntegration, useDeleteCredentials } from '../hooks/useIntegrations';
import { useProviderCopy } from '../hooks/useProviderCopy';
import { useT } from '../i18n';
import { SetupGuidePanel } from '../components/integrations/SetupGuidePanel';
import { ConnectionsPanel } from '../components/integrations/ConnectionsPanel';
import { VerificationNotice } from '../components/integrations/IntegrationVerification';
import { ProviderCard } from '../components/integrations/ProviderCard';
import { ProviderDetailPanel } from '../components/integrations/ProviderDetailPanel';
import type { IntegrationProviderInfo } from '../types';

export function Integrations() {
  const t = useT();
  const { data: providers, isLoading, error, refetch } = useIntegrations();
  const copy = useProviderCopy();
  const connect = useConnectIntegration();
  const disconnect = useDisconnectIntegration();
  const deleteCredentials = useDeleteCredentials();

  const [selected, setSelected] = useState<IntegrationProviderInfo | null>(null);
  const [showDisconnect, setShowDisconnect] = useState<string | null>(null);
  const [showDeleteCredentials, setShowDeleteCredentials] = useState<string | null>(null);
  const [selectedScopes, setSelectedScopes] = useState<Record<string, boolean>>({});

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <Spinner text={t.common.loading} />
      </div>
    );
  }

  if (error) {
    return <ErrorState message={error.message} onRetry={() => refetch()} />;
  }

  if (!providers?.length) {
    return (
      <EmptyState
        icon={<Plug className="w-12 h-12" />}
        title={t.integrations.empty}
        description={t.integrations.emptyDescription}
      />
    );
  }

  const handleConnect = (providerId: string) => {
    const provider = providers?.find(p => p.providerId === providerId);
    const scopes = provider?.optionalScopes
      ?.filter(s => selectedScopes[s.scope])
      .map(s => s.scope) ?? [];
    connect.mutate({ providerId, scopes: scopes.length > 0 ? scopes : undefined });
  };

  const handleDeleteCredentials = (providerId: string) => {
    deleteCredentials.mutate(providerId, {
      onSuccess: () => setShowDeleteCredentials(null),
    });
  };

  const handleDisconnect = (providerId: string) => {
    disconnect.mutate(providerId, {
      onSuccess: () => {
        setShowDisconnect(null);
        setSelected(null);
      },
    });
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center gap-3">
        <Plug className="w-6 h-6 text-accent" />
        <h1 className="text-2xl font-bold text-text">{t.integrations.title}</h1>
      </div>
      <p className="text-text-secondary">{t.integrations.subtitle}</p>

      <Hint id="integrations-connect" />

      {/* Provider Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {providers.map((provider) => (
          <ProviderCard
            key={provider.providerId}
            provider={provider}
            selected={selected?.providerId === provider.providerId}
            onToggle={() => setSelected(selected?.providerId === provider.providerId ? null : provider)}
            onSetup={() => setSelected(provider)}
            selectedScopes={selectedScopes}
            onScopeChange={(scope, checked) => setSelectedScopes(prev => ({ ...prev, [scope]: checked }))}
            onConnect={() => handleConnect(provider.providerId)}
            connecting={connect.isPending}
            onDisconnect={() => setShowDisconnect(provider.providerId)}
          />
        ))}
      </div>

      {/* Setup Guide (when not yet connected — incl. credentials saved but not connected) */}
      {selected && selected.status !== 'CONNECTED' && (
        <>
          <VerificationNotice provider={selected} />
          <SetupGuidePanel
            key={selected.providerId}
            providerId={selected.providerId}
            displayName={copy.name(selected)}
            requiresCredentials={selected.requiresCredentials !== false}
            onRefresh={() => refetch()}
          />
        </>
      )}

      {/* Detail Panel (when connected) — keyed: another provider starts folded and without a test result */}
      {selected && selected.status === 'CONNECTED' && (
        <ProviderDetailPanel key={selected.providerId} provider={selected} onRefresh={() => refetch()} />
      )}

      {/* Named connections of the selected provider (0b3cbcc3) — from the live list, so a new one shows up after saving */}
      {selected && (
        <ConnectionsPanel
          key={`connections-${selected.providerId}`}
          provider={providers.find((p) => p.providerId === selected.providerId) ?? selected}
          displayName={copy.name(selected)}
          onRefresh={() => refetch()}
        />
      )}

      {/* Disconnect Confirmation */}
      <ConfirmModal
        open={!!showDisconnect}
        onClose={() => setShowDisconnect(null)}
        onConfirm={() => showDisconnect && handleDisconnect(showDisconnect)}
        title={t.integrations.disconnectConfirm}
        confirmLabel={t.integrations.disconnect}
        variant="danger"
        isPending={disconnect.isPending}
        cancelVariant="ghost"
        size="md"
        extraAction={
          <Button
            variant="ghost"
            onClick={() => {
              const provider = showDisconnect;
              setShowDisconnect(null);
              setShowDeleteCredentials(provider);
            }}
          >
            {t.integrations.deleteCredentials}
          </Button>
        }
      >
        <p className="text-text-secondary">{t.integrations.disconnectWarning}</p>
      </ConfirmModal>

      {/* Delete Credentials Confirmation */}
      <ConfirmModal
        open={!!showDeleteCredentials}
        onClose={() => setShowDeleteCredentials(null)}
        onConfirm={() => showDeleteCredentials && handleDeleteCredentials(showDeleteCredentials)}
        title={t.integrations.deleteCredentialsConfirm}
        confirmLabel={t.integrations.deleteCredentials}
        variant="danger"
        isPending={deleteCredentials.isPending}
        cancelVariant="ghost"
        size="md"
      >
        <p className="text-text-secondary">{t.integrations.deleteCredentialsWarning}</p>
      </ConfirmModal>
    </div>
  );
}
