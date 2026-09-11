import { useState } from 'react';
import { Plug, Calendar, KanbanSquare, TicketCheck, Mail, ExternalLink, Trash2, Play, CheckCircle2, XCircle, AlertTriangle, ChevronRight, Settings2, ListTodo } from 'lucide-react';
import { Card, Button, Badge, Spinner, Modal } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { useIntegrations, useConnectIntegration, useDisconnectIntegration, useDeleteCredentials, useTestIntegrationAction } from '../hooks/useIntegrations';
import { useT } from '../i18n';
import { useI18nStore } from '../i18n';
import { formatDateTime } from '../utils/formatTime';
import { SetupGuidePanel } from '../components/integrations/SetupGuidePanel';
import type { IntegrationProviderInfo, IntegrationActionInfo } from '../types';

const ICON_MAP: Record<string, typeof Calendar> = {
  Calendar,
  KanbanSquare,
  TicketCheck,
  Mail,
  ListTodo,
};

function ProviderIcon({ icon }: { icon: string }) {
  const Icon = ICON_MAP[icon] || Plug;
  return <Icon className="w-8 h-8" />;
}

function ProviderStatusBadge({ provider }: { provider: IntegrationProviderInfo }) {
  if (provider.status === 'CONNECTED') {
    return (
      <Badge variant="success">
        <CheckCircle2 className="w-3 h-3 mr-1" />
        Verbunden
      </Badge>
    );
  }
  if (provider.status === 'EXPIRED') {
    return (
      <Badge variant="warning">
        <AlertTriangle className="w-3 h-3 mr-1" />
        Abgelaufen
      </Badge>
    );
  }
  if (!provider.configured) {
    return (
      <Badge variant="warning">
        <Settings2 className="w-3 h-3 mr-1" />
        Einrichtung erforderlich
      </Badge>
    );
  }
  return (
    <Badge variant="default">
      <XCircle className="w-3 h-3 mr-1" />
      Nicht verbunden
    </Badge>
  );
}

export function Integrations() {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const { data: providers, isLoading, error, refetch } = useIntegrations();
  // Localized provider copy (DE/EN) with fallback to the backend-provided text.
  const providerCopy = (t.integrations as unknown as {
    providers?: Record<string, { displayName?: string; description?: string; actions?: Record<string, string> }>;
  }).providers;
  const localizedName = (p: { providerId: string; displayName: string }) =>
    providerCopy?.[p.providerId]?.displayName ?? p.displayName;
  const localizedDesc = (p: { providerId: string; description: string }) =>
    providerCopy?.[p.providerId]?.description ?? p.description;
  const localizedActionDesc = (providerId: string, a: { name: string; description: string }) =>
    providerCopy?.[providerId]?.actions?.[a.name] ?? a.description;
  const connect = useConnectIntegration();
  const disconnect = useDisconnectIntegration();
  const deleteCredentials = useDeleteCredentials();
  const testAction = useTestIntegrationAction();

  const [selected, setSelected] = useState<IntegrationProviderInfo | null>(null);
  const [showDisconnect, setShowDisconnect] = useState<string | null>(null);
  const [showDeleteCredentials, setShowDeleteCredentials] = useState<string | null>(null);
  const [testResult, setTestResult] = useState<string | null>(null);
  const [testingAction, setTestingAction] = useState<string | null>(null);
  const [selectedScopes, setSelectedScopes] = useState<Record<string, boolean>>({});
  const [showConfig, setShowConfig] = useState(false);

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
            onClick={() => {
              setShowConfig(false);
              setTestResult(null);
              setSelected(selected?.providerId === provider.providerId ? null : provider);
            }}
          >
            <div className="flex items-start gap-4 p-4">
              <div className={`p-3 rounded-lg ${
                provider.status === 'CONNECTED'
                  ? 'bg-green-500/10 text-green-500'
                  : !provider.configured
                    ? 'bg-amber-500/10 text-amber-500'
                    : 'bg-surface-secondary text-text-secondary'
              }`}>
                <ProviderIcon icon={provider.icon} />
              </div>
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 mb-1">
                  <h3 className="font-semibold text-text">{localizedName(provider)}</h3>
                  <ProviderStatusBadge provider={provider} />
                </div>
                <p className="text-sm text-text-secondary mb-3">{localizedDesc(provider)}</p>

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
                  ) : provider.configured ? (
                    <div className="space-y-2 w-full" onClick={(e) => e.stopPropagation()}>
                      {provider.optionalScopes?.length > 0 && (
                        <div className="space-y-1">
                          <p className="text-xs text-text-tertiary">{t.integrations.optionalPermissions}</p>
                          {provider.optionalScopes.map((scope) => (
                            <label key={scope.scope} className="flex items-center gap-2 text-xs text-text-secondary cursor-pointer">
                              <input
                                type="checkbox"
                                checked={selectedScopes[scope.scope] ?? false}
                                onChange={(e) => setSelectedScopes(prev => ({ ...prev, [scope.scope]: e.target.checked }))}
                                className="rounded border-border"
                              />
                              {scope.label}
                            </label>
                          ))}
                        </div>
                      )}
                      <div className="flex gap-2">
                        <Button
                          size="sm"
                          variant="primary"
                          onClick={() => handleConnect(provider.providerId)}
                          loading={connect.isPending}
                        >
                          <ExternalLink className="w-3 h-3 mr-1" />
                          {t.integrations.connect}
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => setShowDisconnect(provider.providerId)}
                        >
                          <Trash2 className="w-3 h-3 mr-1" />
                          {t.integrations.reset}
                        </Button>
                      </div>
                    </div>
                  ) : (
                    <Button
                      size="sm"
                      variant="secondary"
                      onClick={(e) => { e.stopPropagation(); setSelected(provider); }}
                    >
                      <Settings2 className="w-3 h-3 mr-1" />
                      {t.integrations.setupRequired}
                    </Button>
                  )}
                </div>
              </div>
            </div>
          </Card>
        ))}
      </div>

      {/* Setup Guide (when not yet connected — incl. credentials saved but not connected) */}
      {selected && selected.status !== 'CONNECTED' && (
        <SetupGuidePanel
          key={selected.providerId}
          providerId={selected.providerId}
          displayName={localizedName(selected)}
          requiresCredentials={selected.requiresCredentials !== false}
          onRefresh={() => refetch()}
        />
      )}

      {/* Detail Panel (when connected) */}
      {selected && selected.status === 'CONNECTED' && (
        <Card>
          <div className="p-4">
            {/* Connection details */}
            <div className="mb-6">
              <h3 className="font-semibold text-text mb-3">{t.integrations.connectionDetails}</h3>
              <dl className="space-y-2 text-sm">
                {(selected.accountName || selected.accountEmail) && (
                  <div className="flex gap-2">
                    <dt className="text-text-tertiary w-40 shrink-0">{t.integrations.account}</dt>
                    <dd className="text-text-secondary">{selected.accountName || selected.accountEmail}</dd>
                  </div>
                )}
                {selected.connectedSince && (
                  <div className="flex gap-2">
                    <dt className="text-text-tertiary w-40 shrink-0">{t.integrations.connectedSince}</dt>
                    <dd className="text-text-secondary">{formatDateTime(selected.connectedSince, locale)}</dd>
                  </div>
                )}
                <div className="flex gap-2">
                  <dt className="text-text-tertiary w-40 shrink-0">{t.integrations.grantedPermissions}</dt>
                  <dd className="flex flex-wrap gap-1">
                    {selected.scopes?.trim()
                      ? selected.scopes.trim().split(/\s+/).map((s) => (
                          <Badge key={s} variant="success">{s}</Badge>
                        ))
                      : <span className="text-text-secondary">—</span>}
                  </dd>
                </div>
                {selected.optionalScopes?.length > 0 && (
                  <div className="flex gap-2">
                    <dt className="text-text-tertiary w-40 shrink-0">{t.integrations.optionalPermissions}</dt>
                    <dd className="flex flex-wrap gap-1">
                      {selected.optionalScopes.map((s) => (
                        <Badge key={s.scope} variant={s.granted ? 'success' : 'default'}>{s.label}</Badge>
                      ))}
                    </dd>
                  </div>
                )}
              </dl>
            </div>

            {/* Configuration & setup guide (collapsible) */}
            <div className="mb-6 border border-border rounded-lg overflow-hidden">
              <button
                onClick={() => setShowConfig((v) => !v)}
                className="flex items-center gap-2 w-full p-3 text-left hover:bg-surface-secondary transition-colors"
              >
                <Settings2 className="w-4 h-4 text-text-secondary shrink-0" />
                <span className="font-medium text-sm text-text flex-1">{t.integrations.configuration}</span>
                <ChevronRight className={`w-4 h-4 text-text-secondary transition-transform duration-200 shrink-0 ${showConfig ? 'rotate-90' : ''}`} />
              </button>
              <div className={`grid transition-all duration-200 ease-in-out ${showConfig ? 'grid-rows-[1fr] opacity-100' : 'grid-rows-[0fr] opacity-0'}`}>
                <div className="overflow-hidden">
                  <div className="px-3 pb-3">
                    <SetupGuidePanel
                      key={selected.providerId}
                      providerId={selected.providerId}
                      displayName={localizedName(selected)}
                      requiresCredentials={selected.requiresCredentials !== false}
                      connected
                      onRefresh={() => refetch()}
                    />
                  </div>
                </div>
              </div>
            </div>

            <h3 className="font-semibold text-text mb-4">
              {t.integrations.availableActions} — {localizedName(selected)}
            </h3>
            <div className="space-y-3">
              {selected.actions.map((action) => (
                <div key={action.name} className={`flex items-center justify-between p-3 rounded-lg bg-surface-secondary ${!action.available ? 'opacity-50' : ''}`}>
                  <div>
                    <p className={`font-medium text-sm ${action.available ? 'text-text' : 'text-text-tertiary'}`}>{action.name}</p>
                    <p className="text-xs text-text-secondary">{localizedActionDesc(selected.providerId, action)}</p>
                    {!action.available && (
                      <p className="text-xs text-amber-500 mt-1">{t.integrations.scopeMissing}</p>
                    )}
                  </div>
                  {action.available && (
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => handleTestAction(selected.providerId, action)}
                      loading={testingAction === action.name}
                    >
                      <Play className="w-3 h-3 mr-1" />
                      {t.integrations.test}
                    </Button>
                  )}
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
        <div className="flex justify-between gap-2">
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
          <div className="flex gap-2">
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
        </div>
      </Modal>

      {/* Delete Credentials Confirmation */}
      <Modal
        open={!!showDeleteCredentials}
        onClose={() => setShowDeleteCredentials(null)}
        title={t.integrations.deleteCredentialsConfirm}
      >
        <p className="text-text-secondary mb-4">{t.integrations.deleteCredentialsWarning}</p>
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => setShowDeleteCredentials(null)}>
            {t.common.cancel}
          </Button>
          <Button
            variant="danger"
            onClick={() => showDeleteCredentials && handleDeleteCredentials(showDeleteCredentials)}
            loading={deleteCredentials.isPending}
          >
            {t.integrations.deleteCredentials}
          </Button>
        </div>
      </Modal>
    </div>
  );
}
