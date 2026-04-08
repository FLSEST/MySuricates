import { useState } from 'react';
import {
  Plug, Calendar, KanbanSquare, TicketCheck, ExternalLink, Trash2, Play, CheckCircle2, XCircle,
  AlertTriangle, ChevronRight, Copy, Settings2,
} from 'lucide-react';
import { Card, Button, Badge, Spinner, Modal, Input } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import {
  useIntegrations,
  useConnectIntegration,
  useDisconnectIntegration,
  useTestIntegrationAction,
  useSaveCredentials,
} from '../hooks/useIntegrations';
import { useT } from '../i18n';
import { useI18nStore } from '../i18n';
import { SETUP_GUIDES } from '../data/integrationSetupGuides';
import type { IntegrationProviderInfo, IntegrationActionInfo } from '../types';
import type { SetupStep } from '../data/integrationSetupGuides';

const ICON_MAP: Record<string, typeof Calendar> = {
  Calendar,
  KanbanSquare,
  TicketCheck,
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

function SetupGuidePanel({ providerId, displayName, onRefresh }: {
  providerId: string;
  displayName: string;
  onRefresh: () => void;
}) {
  const lang = useI18nStore.getState().locale;
  const t = useT();
  const guide = SETUP_GUIDES[providerId];
  const saveCredentials = useSaveCredentials();
  const [openStep, setOpenStep] = useState(0);
  const [copied, setCopied] = useState<number | null>(null);
  const [clientId, setClientId] = useState('');
  const [clientSecret, setClientSecret] = useState('');

  if (!guide) return null;

  const copyToClipboard = (text: string, stepIndex: number) => {
    navigator.clipboard.writeText(text);
    setCopied(stepIndex);
    setTimeout(() => setCopied(null), 2000);
  };

  const handleSaveCredentials = () => {
    saveCredentials.mutate(
      { providerId, clientId, clientSecret },
      { onSuccess: () => onRefresh() }
    );
  };

  return (
    <Card>
      <div className="p-4">
        <div className="flex items-center gap-2 mb-1">
          <Settings2 className="w-5 h-5 text-amber-500" />
          <h3 className="font-semibold text-text">
            {t.integrations.setupTitle.replace('{provider}', displayName)}
          </h3>
        </div>
        <p className="text-sm text-text-secondary mb-4">{t.integrations.setupDescription}</p>

        <div className="space-y-2">
          {guide.steps.map((step: SetupStep, i: number) => {
            const isOpen = openStep === i;
            return (
              <div key={i} className="border border-border rounded-lg overflow-hidden">
                <button
                  onClick={() => setOpenStep(isOpen ? -1 : i)}
                  className="flex items-center gap-3 w-full p-3 text-left hover:bg-surface-secondary transition-colors"
                >
                  <span className="flex items-center justify-center w-6 h-6 rounded-full bg-primary/10 text-primary text-xs font-bold shrink-0">
                    {i + 1}
                  </span>
                  <ChevronRight className={`w-4 h-4 text-text-secondary transition-transform duration-200 shrink-0 ${isOpen ? 'rotate-90' : ''}`} />
                  <span className="font-medium text-sm text-text">{step.title[lang]}</span>
                </button>

                <div className={`grid transition-all duration-200 ease-in-out ${isOpen ? 'grid-rows-[1fr] opacity-100' : 'grid-rows-[0fr] opacity-0'}`}>
                  <div className="overflow-hidden">
                    <div className="px-3 pb-3 pl-12 space-y-2">
                      <ul className="list-disc list-inside space-y-1">
                        {step.instructions[lang].map((line, j) => (
                          <li key={j} className="text-sm text-text-secondary">{line}</li>
                        ))}
                      </ul>

                      {step.code && (
                        <div className="relative">
                          <pre className="p-2 rounded bg-surface text-xs text-text font-mono border border-border overflow-x-auto">
                            {step.code}
                          </pre>
                          <button
                            onClick={() => copyToClipboard(step.code!, i)}
                            className="absolute top-1 right-1 p-1 rounded hover:bg-surface-secondary text-text-tertiary hover:text-text transition-colors"
                            title="Copy"
                          >
                            {copied === i ? <CheckCircle2 className="w-3.5 h-3.5 text-green-500" /> : <Copy className="w-3.5 h-3.5" />}
                          </button>
                        </div>
                      )}

                      {step.link && (
                        <a
                          href={step.link.url}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="inline-flex items-center gap-1 text-sm text-primary hover:underline"
                        >
                          <ExternalLink className="w-3 h-3" />
                          {step.link.label[lang]}
                        </a>
                      )}
                    </div>
                  </div>
                </div>
              </div>
            );
          })}
        </div>

        {/* Credential input fields */}
        <div className="mt-4 p-3 rounded-lg border border-border bg-surface-secondary space-y-3">
          <h4 className="text-sm font-medium text-text">{t.integrations.enterCredentials}</h4>
          <Input
            placeholder={guide.credentialLabels?.clientIdPlaceholder[lang] ?? 'Client ID'}
            value={clientId}
            onChange={(e) => setClientId(e.target.value)}
          />
          {providerId === 'trello' && clientId.trim() && (
            <a
              href={`https://trello.com/1/authorize?expiration=never&scope=read,write&response_type=token&name=MySuricates&key=${encodeURIComponent(clientId.trim())}`}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 text-sm text-primary hover:underline"
            >
              <ExternalLink className="w-3 h-3" />
              {lang === 'de' ? 'Token generieren' : 'Generate Token'}
            </a>
          )}
          <Input
            type="password"
            placeholder={guide.credentialLabels?.clientSecretPlaceholder[lang] ?? 'Client Secret'}
            value={clientSecret}
            onChange={(e) => setClientSecret(e.target.value)}
          />
          <Button
            size="sm"
            variant="primary"
            onClick={handleSaveCredentials}
            loading={saveCredentials.isPending}
            disabled={!clientId.trim() || !clientSecret.trim()}
          >
            <CheckCircle2 className="w-3 h-3 mr-1" />
            {t.integrations.saveCredentials}
          </Button>
        </div>
      </div>
    </Card>
  );
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
                  <h3 className="font-semibold text-text">{provider.displayName}</h3>
                  <ProviderStatusBadge provider={provider} />
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

      {/* Setup Guide (when not configured) */}
      {selected && !selected.configured && selected.status !== 'CONNECTED' && (
        <SetupGuidePanel
          key={selected.providerId}
          providerId={selected.providerId}
          displayName={selected.displayName}
          onRefresh={() => refetch()}
        />
      )}

      {/* Detail Panel (when connected) */}
      {selected && selected.status === 'CONNECTED' && (
        <Card>
          <div className="p-4">
            <h3 className="font-semibold text-text mb-4">
              {t.integrations.availableActions} — {selected.displayName}
            </h3>
            <div className="space-y-3">
              {selected.actions.map((action) => (
                <div key={action.name} className={`flex items-center justify-between p-3 rounded-lg bg-surface-secondary ${!action.available ? 'opacity-50' : ''}`}>
                  <div>
                    <p className={`font-medium text-sm ${action.available ? 'text-text' : 'text-text-tertiary'}`}>{action.name}</p>
                    <p className="text-xs text-text-secondary">{action.description}</p>
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
