import { useState } from 'react';
import { Plus, ExternalLink, Trash2, Unplug, KeyRound } from 'lucide-react';
import { Card, Button, Badge, Input, Modal } from '../ui';
import {
  CONNECTION_NAME_PATTERN,
  DEFAULT_CONNECTION,
  useConnectIntegration,
  useDeleteCredentials,
  useDisconnectIntegration,
} from '../../hooks/useIntegrations';
import { useT, useI18nStore } from '../../i18n';
import { formatDateTime } from '../../utils/formatTime';
import { SetupGuidePanel } from './SetupGuidePanel';
import type { IntegrationConnectionInfo, IntegrationProviderInfo } from '../../types';

type Pending = { kind: 'disconnect' | 'delete'; name: string } | null;

function StatusBadge({ status }: { status: IntegrationConnectionInfo['status'] }) {
  const t = useT();
  if (status === 'CONNECTED') return <Badge variant="success">{t.integrations.statusConnected}</Badge>;
  if (status === 'EXPIRED') return <Badge variant="warning">{t.integrations.statusExpired}</Badge>;
  return <Badge variant="default">{t.integrations.statusNotConnected}</Badge>;
}

/**
 * The named connections of one provider (0b3cbcc3): list, add another, connect, disconnect and
 * delete each one. The `default` connection keeps being managed by the provider card and detail
 * panel above; here it is only listed.
 */
export function ConnectionsPanel({ provider, displayName, onRefresh }: {
  provider: IntegrationProviderInfo;
  displayName: string;
  onRefresh: () => void;
}) {
  const t = useT();
  const tc = t.integrations.connections;
  const locale = useI18nStore((s) => s.locale);
  const connect = useConnectIntegration();
  const disconnect = useDisconnectIntegration();
  const deleteCredentials = useDeleteCredentials();

  const connections = provider.connections ?? [];
  const [adding, setAdding] = useState(false);
  const [newName, setNewName] = useState('');
  const [setupFor, setSetupFor] = useState<string | null>(null);
  const [pending, setPending] = useState<Pending>(null);

  const trimmed = newName.trim();
  const taken = trimmed === DEFAULT_CONNECTION || connections.some((c) => c.name === trimmed);
  const nameError = !trimmed ? undefined
    : !CONNECTION_NAME_PATTERN.test(trimmed) ? tc.nameInvalid
      : taken ? tc.nameTaken : undefined;
  const oauth = provider.requiresOAuthFlow === true;

  const startSetup = () => {
    if (!trimmed || nameError) return;
    setSetupFor(trimmed);
    setAdding(false);
    setNewName('');
  };

  const connectNamed = (name: string) =>
    connect.mutate({ providerId: provider.providerId, connection: name }, { onSuccess: () => onRefresh() });

  const confirmPending = () => {
    if (!pending) return;
    const ref = { providerId: provider.providerId, connection: pending.name };
    const done = { onSuccess: () => { setPending(null); onRefresh(); } };
    if (pending.kind === 'disconnect') disconnect.mutate(ref, done);
    else deleteCredentials.mutate(ref, done);
  };

  return (
    <Card>
      <div className="p-4 space-y-3">
        <div className="flex items-center justify-between gap-2">
          <h3 className="font-semibold text-text">{tc.title}</h3>
          {!adding && (
            <Button size="sm" variant="secondary" onClick={() => setAdding(true)}>
              <Plus className="w-3 h-3 mr-1" />
              {tc.add}
            </Button>
          )}
        </div>
        <p className="text-xs text-text-tertiary">{tc.hint}</p>

        {connections.length === 0 && <p className="text-sm text-text-secondary">{tc.none}</p>}
        <ul className="space-y-2">
          {connections.map((c) => {
            const named = c.name !== DEFAULT_CONNECTION;
            return (
              <li key={c.name} data-testid={`connection-${c.name}`}
                className="flex flex-wrap items-center gap-2 p-2 rounded-lg bg-surface-secondary">
                <code className="text-sm text-text">{c.name}</code>
                {!named && <span className="text-xs text-text-tertiary">({tc.defaultLabel})</span>}
                <StatusBadge status={c.status} />
                {(c.accountName || c.accountEmail) && (
                  <span className="text-xs text-text-secondary">{c.accountName || c.accountEmail}</span>
                )}
                {c.connectedSince && (
                  <span className="text-xs text-text-tertiary">{formatDateTime(c.connectedSince, locale)}</span>
                )}
                {named && (
                  <div className="flex flex-wrap gap-1 ml-auto">
                    {oauth && c.status !== 'CONNECTED' && c.configured && (
                      <Button size="sm" variant="primary" onClick={() => connectNamed(c.name)} loading={connect.isPending}>
                        <ExternalLink className="w-3 h-3 mr-1" />
                        {t.integrations.connect}
                      </Button>
                    )}
                    <Button size="sm" variant="ghost" onClick={() => setSetupFor(setupFor === c.name ? null : c.name)}>
                      <KeyRound className="w-3 h-3 mr-1" />
                      {tc.credentials}
                    </Button>
                    {c.status === 'CONNECTED' && (
                      <Button size="sm" variant="ghost" onClick={() => setPending({ kind: 'disconnect', name: c.name })}>
                        <Unplug className="w-3 h-3 mr-1" />
                        {t.integrations.disconnect}
                      </Button>
                    )}
                    <Button size="sm" variant="danger" onClick={() => setPending({ kind: 'delete', name: c.name })}>
                      <Trash2 className="w-3 h-3 mr-1" />
                      {tc.delete}
                    </Button>
                  </div>
                )}
              </li>
            );
          })}
        </ul>

        {adding && (
          <div className="flex flex-wrap items-start gap-2">
            <div className="flex-1 min-w-48">
              <Input
                label={tc.name}
                placeholder={tc.namePlaceholder}
                value={newName}
                error={nameError}
                autoFocus
                onChange={(e) => setNewName(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') startSetup(); }}
              />
            </div>
            <div className="flex gap-2 pt-7">
              <Button size="sm" variant="primary" onClick={startSetup} disabled={!trimmed || !!nameError}>
                {tc.continue}
              </Button>
              <Button size="sm" variant="ghost" onClick={() => { setAdding(false); setNewName(''); }}>
                {t.common.cancel}
              </Button>
            </div>
          </div>
        )}

        {setupFor && (
          <div className="space-y-2">
            {oauth && (
              <div className="flex flex-wrap items-center gap-2">
                <p className="text-xs text-text-secondary flex-1">{tc.oauthReuse}</p>
                {provider.configured && (
                  <Button size="sm" variant="primary" onClick={() => connectNamed(setupFor)} loading={connect.isPending}>
                    <ExternalLink className="w-3 h-3 mr-1" />
                    {t.integrations.connect}
                  </Button>
                )}
              </div>
            )}
            <SetupGuidePanel
              key={`${provider.providerId}/${setupFor}`}
              providerId={provider.providerId}
              displayName={tc.setupFor.replace('{provider}', displayName).replace('{name}', setupFor)}
              requiresCredentials={provider.requiresCredentials !== false}
              connection={setupFor}
              onRefresh={onRefresh}
            />
          </div>
        )}
      </div>

      <Modal
        open={!!pending}
        onClose={() => setPending(null)}
        title={pending ? (pending.kind === 'delete' ? tc.deleteConfirm : tc.disconnectConfirm).replace('{name}', pending.name) : ''}
      >
        <p className="text-text-secondary mb-4">
          {pending?.kind === 'delete' ? tc.deleteWarning : t.integrations.disconnectWarning}
        </p>
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => setPending(null)}>{t.common.cancel}</Button>
          <Button variant="danger" onClick={confirmPending} loading={disconnect.isPending || deleteCredentials.isPending}>
            {pending?.kind === 'delete' ? tc.delete : t.integrations.disconnect}
          </Button>
        </div>
      </Modal>
    </Card>
  );
}
