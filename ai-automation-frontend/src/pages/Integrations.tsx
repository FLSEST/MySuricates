import { useState } from 'react';
import { Plug, Calendar, ExternalLink, Trash2, Play, CheckCircle2, XCircle, AlertTriangle } from 'lucide-react';
import { Card, Button, Badge, Spinner, Modal } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import {
  useIntegrations,
  useConnectIntegration,
  useDisconnectIntegration,
  useTestIntegrationAction,
} from '../hooks/useIntegrations';
import { useT } from '../i18n';
import type { IntegrationProviderInfo, IntegrationActionInfo } from '../types';

const ICON_MAP: Record<string, typeof Calendar> = {
  Calendar,
};

function ProviderIcon({ icon }: { icon: string }) {
  const Icon = ICON_MAP[icon] || Plug;
  return <Icon className="w-8 h-8" />;
}

function StatusBadge({ status }: { status: string }) {
  switch (status) {
    case 'CONNECTED':
      return (
        <Badge variant="success">
          <CheckCircle2 className="w-3 h-3 mr-1" />
          Verbunden
        </Badge>
      );
    case 'EXPIRED':
      return (
        <Badge variant="warning">
          <AlertTriangle className="w-3 h-3 mr-1" />
          Abgelaufen
        </Badge>
      );
    default:
      return (
        <Badge variant="default">
          <XCircle className="w-3 h-3 mr-1" />
          Nicht verbunden
        </Badge>
      );
  }
}

export function Integrations() {
  const t = useT();
  const { data: providers, isLoading, error, refetch } = useIntegrations();
  const connect = useConnectIntegration();
  const disconnect = useDisconnectIntegration();
  const testAction = useTestIntegrationAction();

  const [selected, setSelected] = useState<IntegrationProviderInfo | null>(null);
  const [showDisconnect, setShowDisconnect] = useState<string | null>(null);
  const [testResult, setTestResult] = useState<string | null>(null);
  const [testingAction, setTestingAction] = useState<string | null>(null);

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
    connect.mutate(providerId);
  };

  const handleDisconnect = (providerId: string) => {
    disconnect.mutate(providerId, {
      onSuccess: () => {
        setShowDisconnect(null);
        setSelected(null);
      },
    });
  };

  const handleTestAction = (providerId: string, action: IntegrationActionInfo) => {
    setTestingAction(action.name);
    setTestResult(null);
    testAction.mutate(
      { providerId, action: action.name },
      {
        onSuccess: (data) => {
          setTestResult(JSON.stringify(data, null, 2));
          setTestingAction(null);
        },
        onError: () => setTestingAction(null),
      }
    );
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center gap-3">
        <Plug className="w-6 h-6 text-primary" />
        <h1 className="text-2xl font-bold text-text">{t.integrations.title}</h1>
      </div>
      <p className="text-text-secondary">{t.integrations.subtitle}</p>

      {/* Provider Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {providers.map((provider) => (
          <Card
            key={provider.providerId}
            className={`cursor-pointer transition-all hover:border-primary/50 ${
              selected?.providerId === provider.providerId ? 'border-primary ring-1 ring-primary/30' : ''
            }`}
            onClick={() => setSelected(selected?.providerId === provider.providerId ? null : provider)}
          >
            <div className="flex items-start gap-4 p-4">
              <div className={`p-3 rounded-lg ${
                provider.status === 'CONNECTED' ? 'bg-green-500/10 text-green-500' : 'bg-surface-secondary text-text-secondary'
              }`}>
                <ProviderIcon icon={provider.icon} />
              </div>
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 mb-1">
                  <h3 className="font-semibold text-text">{provider.displayName}</h3>
                  <StatusBadge status={provider.status} />
                </div>
                <p className="text-sm text-text-secondary mb-3">{provider.description}</p>

                {provider.status === 'CONNECTED' && provider.accountEmail && (
                  <p className="text-xs text-text-tertiary mb-3">
                    {provider.accountName || provider.accountEmail}
                  </p>
                )}

                <div className="flex gap-2">
                  {provider.status === 'CONNECTED' ? (
                    <Button
                      size="sm"
                      variant="danger"
                      onClick={(e) => { e.stopPropagation(); setShowDisconnect(provider.providerId); }}
                    >
                      <Trash2 className="w-3 h-3 mr-1" />
                      {t.integrations.disconnect}
                    </Button>
                  ) : (
                    <Button
                      size="sm"
                      variant="primary"
                      onClick={(e) => { e.stopPropagation(); handleConnect(provider.providerId); }}
                      loading={connect.isPending}
                    >
                      <ExternalLink className="w-3 h-3 mr-1" />
                      {t.integrations.connect}
                    </Button>
                  )}
                </div>
              </div>
            </div>
          </Card>
        ))}
      </div>

      {/* Detail Panel */}
      {selected && selected.status === 'CONNECTED' && (
        <Card>
          <div className="p-4">
            <h3 className="font-semibold text-text mb-4">
              {t.integrations.availableActions} — {selected.displayName}
            </h3>
            <div className="space-y-3">
              {selected.actions.map((action) => (
                <div key={action.name} className="flex items-center justify-between p-3 rounded-lg bg-surface-secondary">
                  <div>
                    <p className="font-medium text-text text-sm">{action.name}</p>
                    <p className="text-xs text-text-secondary">{action.description}</p>
                  </div>
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() => handleTestAction(selected.providerId, action)}
                    loading={testingAction === action.name}
                  >
                    <Play className="w-3 h-3 mr-1" />
                    {t.integrations.test}
                  </Button>
                </div>
              ))}
            </div>

            {testResult && (
              <div className="mt-4">
                <h4 className="text-sm font-medium text-text mb-2">{t.integrations.testResult}</h4>
                <pre className="p-3 rounded-lg bg-surface text-xs text-text-secondary overflow-auto max-h-64 border border-border">
                  {testResult}
                </pre>
              </div>
            )}
          </div>
        </Card>
      )}

      {/* Disconnect Confirmation */}
      <Modal
        open={!!showDisconnect}
        onClose={() => setShowDisconnect(null)}
        title={t.integrations.disconnectConfirm}
      >
        <p className="text-text-secondary mb-4">{t.integrations.disconnectWarning}</p>
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => setShowDisconnect(null)}>
            {t.common.cancel}
          </Button>
          <Button
            variant="danger"
            onClick={() => showDisconnect && handleDisconnect(showDisconnect)}
            loading={disconnect.isPending}
          >
            {t.integrations.disconnect}
          </Button>
        </div>
      </Modal>
    </div>
  );
}
