import { useState } from 'react';
import { Webhook, Plus, Trash2, RefreshCw, KeyRound, ShieldCheck, ShieldOff } from 'lucide-react';
import { Card, Button, Input, Select, Modal, ModalFooter, Spinner, Table, Toggle, Badge } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { CopyButton } from '../components/shared/CopyButton';
import {
  useWebhooks,
  useCreateWebhook,
  useToggleWebhook,
  useDeleteWebhook,
  useRegenerateWebhookToken,
  useGenerateWebhookSecret,
  useRemoveWebhookSecret,
} from '../hooks/useWebhooks';
import { useWorkflows } from '../hooks/useWorkflows';
import { useModuleEnabled } from '../hooks/useModules';
import { useT } from '../i18n';
import type { WebhookTrigger, CreateWebhookRequest, WebhookSigningSecret } from '../types';

export function Webhooks() {
  const t = useT();
  const { data: webhooks, isLoading, error, refetch } = useWebhooks();
  const { data: workflows } = useWorkflows();
  const createWebhook = useCreateWebhook();
  const toggleWebhook = useToggleWebhook();
  const deleteWebhook = useDeleteWebhook();
  const regenerateToken = useRegenerateWebhookToken();
  const generateSecret = useGenerateWebhookSecret();
  const removeSecret = useRemoveWebhookSecret();
  const signaturesModule = useModuleEnabled('webhook-signatures');

  const [showCreate, setShowCreate] = useState(false);
  const [form, setForm] = useState<CreateWebhookRequest>({ workflowId: '' });
  // Holds the freshly created/regenerated webhook while its token is still visible in full —
  // the token is never revealed again after this one-time modal is closed.
  const [revealedWebhook, setRevealedWebhook] = useState<WebhookTrigger | null>(null);
  // Pending destructive action awaiting confirmation in the project Modal (no window.confirm).
  const [confirmAction, setConfirmAction] = useState<
    { kind: ConfirmKind; webhook: WebhookTrigger } | null
  >(null);
  // Signing secret right after it was set — like the token, shown exactly once
  const [revealedSecret, setRevealedSecret] = useState<WebhookSigningSecret | null>(null);

  const handleCreate = () => {
    createWebhook.mutate(form, {
      onSuccess: (webhook) => {
        setShowCreate(false);
        setForm({ workflowId: '' });
        setRevealedWebhook(webhook);
      },
    });
  };

  const setSigningSecret = (webhook: WebhookTrigger, onDone?: () => void) => {
    generateSecret.mutate(webhook.id, {
      onSuccess: (secret) => {
        onDone?.();
        setRevealedSecret(secret);
      },
    });
  };

  const runConfirmedAction = () => {
    if (!confirmAction) return;
    const { kind, webhook } = confirmAction;
    if (kind === 'rotateSecret') {
      setSigningSecret(webhook, () => setConfirmAction(null));
    } else if (kind === 'removeSecret') {
      removeSecret.mutate(webhook.id, { onSuccess: () => setConfirmAction(null) });
    } else if (kind === 'regenerate') {
      regenerateToken.mutate(webhook.id, {
        onSuccess: (regenerated) => {
          setConfirmAction(null);
          setRevealedWebhook(regenerated);
        },
      });
    } else {
      deleteWebhook.mutate(webhook.id, { onSuccess: () => setConfirmAction(null) });
    }
  };

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

  const columns = [
    {
      key: 'workflow',
      header: t.common.workflow,
      render: (w: WebhookTrigger) => (
        <span className="font-medium text-text">{w.workflowName}</span>
      ),
    },
    {
      key: 'url',
      header: t.webhooks.triggerUrl,
      render: (w: WebhookTrigger) => (
        <span className="font-mono text-xs text-text-secondary truncate max-w-[300px] block">
          {w.webhookUrl}
        </span>
      ),
    },
    {
      key: 'token',
      header: t.webhooks.token,
      render: (w: WebhookTrigger) => (
        <span
          className="font-mono text-xs text-text-tertiary"
          title={w.tokenVisible ? undefined : t.webhooks.tokenMaskedHint}
        >
          {w.secretToken}
        </span>
      ),
    },
    {
      key: 'signature',
      header: t.webhooks.signature,
      render: (w: WebhookTrigger) => (w.signatureRequired ? (
        <span title={t.webhooks.signedHint} data-testid="webhook-signed">
          <Badge variant="success"><ShieldCheck className="w-3 h-3" />{t.webhooks.signed}</Badge>
        </span>
      ) : (
        <span className="text-xs text-text-tertiary">{t.webhooks.unsigned}</span>
      )),
      width: '120px',
    },
    {
      key: 'triggers',
      header: t.webhooks.triggerCount,
      render: (w: WebhookTrigger) => (
        <Badge>{String(w.triggerCount)}</Badge>
      ),
      width: '100px',
    },
    {
      key: 'enabled',
      header: t.common.status,
      render: (w: WebhookTrigger) => (
        <Toggle
          enabled={w.enabled}
          onChange={(enabled) => toggleWebhook.mutate({ id: w.id, enable: enabled })}
        />
      ),
      width: '80px',
    },
    {
      key: 'actions',
      header: '',
      render: (w: WebhookTrigger) => (
        <div className="flex items-center gap-1">
          {signaturesModule && (
            <button
              onClick={(e) => {
                e.stopPropagation();
                if (w.signatureRequired) {
                  setConfirmAction({ kind: 'rotateSecret', webhook: w });
                } else {
                  setSigningSecret(w);
                }
              }}
              className="p-1.5 rounded-lg text-text-tertiary hover:text-accent transition-colors"
              aria-label={w.signatureRequired ? t.webhooks.rotateSecret : t.webhooks.setSecret}
              title={w.signatureRequired ? t.webhooks.rotateSecret : t.webhooks.setSecret}
            >
              <KeyRound className="w-4 h-4" />
            </button>
          )}
          {signaturesModule && w.signatureRequired && (
            <button
              onClick={(e) => {
                e.stopPropagation();
                setConfirmAction({ kind: 'removeSecret', webhook: w });
              }}
              className="p-1.5 rounded-lg text-text-tertiary hover:text-danger transition-colors"
              aria-label={t.webhooks.removeSecret}
              title={t.webhooks.removeSecret}
            >
              <ShieldOff className="w-4 h-4" />
            </button>
          )}
          <button
            onClick={(e) => {
              e.stopPropagation();
              setConfirmAction({ kind: 'regenerate', webhook: w });
            }}
            className="p-1.5 rounded-lg text-text-tertiary hover:text-accent transition-colors"
            aria-label={t.webhooks.regenerate}
            title={t.webhooks.regenerate}
          >
            <RefreshCw className="w-4 h-4" />
          </button>
          <button
            onClick={(e) => {
              e.stopPropagation();
              setConfirmAction({ kind: 'delete', webhook: w });
            }}
            className="p-1.5 rounded-lg text-text-tertiary hover:text-danger transition-colors"
            aria-label={t.common.delete}
          >
            <Trash2 className="w-4 h-4" />
          </button>
        </div>
      ),
      width: signaturesModule ? '140px' : '80px',
    },
  ];

  return (
    <div className="space-y-6 animate-fade-in">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-text">{t.webhooks.title}</h1>
          <p className="text-sm text-text-secondary mt-1">{t.webhooks.subtitle}</p>
        </div>
        <Button icon={<Plus className="w-4 h-4" />} onClick={() => setShowCreate(true)}>
          {t.webhooks.create}
        </Button>
      </div>

      {/* Table */}
      {!webhooks?.length ? (
        <EmptyState
          icon={<Webhook className="w-8 h-8 text-text-tertiary" />}
          title={t.webhooks.title}
          description={t.webhooks.noWebhooks}
          action={
            <Button size="sm" onClick={() => setShowCreate(true)}>
              {t.webhooks.create}
            </Button>
          }
        />
      ) : (
        <Card padding={false}>
          <Table
            columns={columns}
            data={webhooks}
            keyExtractor={(w) => w.id}
          />
        </Card>
      )}

      {/* Create modal */}
      <Modal
        open={showCreate}
        onClose={() => setShowCreate(false)}
        title={t.webhooks.create}
        footer={
          <>
            <Button variant="secondary" onClick={() => setShowCreate(false)}>
              {t.common.cancel}
            </Button>
            <Button
              onClick={handleCreate}
              loading={createWebhook.isPending}
              disabled={!form.workflowId}
            >
              {t.common.create}
            </Button>
          </>
        }
      >
        <div className="space-y-4">
          <Select
            label={t.common.workflow}
            value={form.workflowId}
            onChange={(e) => setForm({ ...form, workflowId: e.target.value })}
            options={[
              { value: '', label: t.common.selectWorkflow },
              ...(workflows?.map((w) => ({ value: w.id, label: w.name })) ?? []),
            ]}
          />
          <Input
            label={t.common.description}
            value={form.description ?? ''}
            onChange={(e) => setForm({ ...form, description: e.target.value })}
            placeholder="GitHub push event handler"
          />
        </div>
      </Modal>

      {/* Confirmation for regenerate / delete */}
      <Modal
        open={confirmAction !== null}
        onClose={() => setConfirmAction(null)}
        title={confirmAction ? CONFIRM_TEXT[confirmAction.kind](t).title : ''}
        size="sm"
        footer={
          <ModalFooter
            onCancel={() => setConfirmAction(null)}
            onAction={runConfirmedAction}
            cancelLabel={t.common.cancel}
            actionLabel={confirmAction ? CONFIRM_TEXT[confirmAction.kind](t).title : ''}
            actionVariant="danger"
            actionLoading={regenerateToken.isPending || deleteWebhook.isPending || generateSecret.isPending || removeSecret.isPending}
          />
        }
      >
        <p className="text-sm text-text-secondary">
          {confirmAction && CONFIRM_TEXT[confirmAction.kind](t).text}
        </p>
        {confirmAction && (
          <p className="text-xs font-mono text-text-tertiary mt-3 break-all">{confirmAction.webhook.webhookUrl}</p>
        )}
      </Modal>

      {/* One-time token reveal modal — shown right after create/regenerate, never again */}
      <Modal
        open={revealedWebhook !== null}
        onClose={() => setRevealedWebhook(null)}
        title={t.webhooks.tokenShownOnce}
        footer={
          <Button onClick={() => setRevealedWebhook(null)}>{t.common.close}</Button>
        }
      >
        {revealedWebhook && (
          <div className="space-y-4">
            <p className="text-sm text-text-secondary">{t.webhooks.tokenShownOnceHint}</p>
            <div>
              <label className="block text-xs font-medium text-text-tertiary mb-1">
                {t.webhooks.triggerUrl}
              </label>
              <div className="flex items-center gap-2 min-w-0">
                <span className="font-mono text-xs text-text break-all">
                  {revealedWebhook.webhookUrl}
                </span>
                <CopyButton text={revealedWebhook.webhookUrl} />
              </div>
            </div>
            <div>
              <label className="block text-xs font-medium text-text-tertiary mb-1">
                {t.webhooks.token}
              </label>
              <div className="flex items-center gap-2 min-w-0">
                <span className="font-mono text-xs text-text break-all">
                  {revealedWebhook.secretToken}
                </span>
                <CopyButton text={revealedWebhook.secretToken} />
              </div>
            </div>
          </div>
        )}
      </Modal>

      {/* One-time signing secret reveal */}
      <Modal
        open={revealedSecret !== null}
        onClose={() => setRevealedSecret(null)}
        title={t.webhooks.secretShownOnce}
        footer={<Button onClick={() => setRevealedSecret(null)}>{t.common.close}</Button>}
      >
        {revealedSecret && (
          <div className="space-y-4">
            <div className="flex items-center gap-2 min-w-0">
              <span className="font-mono text-xs text-text break-all" data-testid="webhook-secret">
                {revealedSecret.secret}
              </span>
              <CopyButton text={revealedSecret.secret} />
            </div>
            <p className="text-sm text-text-secondary">{t.webhooks.secretShownOnceHint}</p>
            <code className="block font-mono text-xs text-text bg-surface-secondary rounded-lg px-3 py-2 break-all">
              {revealedSecret.header}: sha256=&lt;hex&gt;
            </code>
          </div>
        )}
      </Modal>
    </div>
  );
}

type ConfirmKind = 'regenerate' | 'delete' | 'rotateSecret' | 'removeSecret';

const CONFIRM_TEXT: Record<ConfirmKind, (t: ReturnType<typeof useT>) => { title: string; text: string }> = {
  regenerate: (t) => ({ title: t.webhooks.regenerate, text: t.webhooks.regenerateConfirm }),
  delete: (t) => ({ title: t.common.delete, text: t.webhooks.deleteConfirm }),
  rotateSecret: (t) => ({ title: t.webhooks.rotateSecret, text: t.webhooks.rotateSecretConfirm }),
  removeSecret: (t) => ({ title: t.webhooks.removeSecret, text: t.webhooks.removeSecretConfirm }),
};
