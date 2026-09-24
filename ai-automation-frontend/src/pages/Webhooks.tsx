import { Hint } from '../components/hints/Hint';
import { useState } from 'react';
import { Webhook, Plus } from 'lucide-react';
import { Card, Button, ConfirmModal, Spinner, Table } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { webhookColumns, type WebhookConfirmKind } from '../components/webhooks/webhookColumns';
import { WebhookCreateModal } from '../components/webhooks/WebhookCreateModal';
import { SecretRevealModal } from '../components/webhooks/SecretRevealModal';
import {
  useWebhooks,
  useToggleWebhook,
  useDeleteWebhook,
  useRegenerateWebhookToken,
  useGenerateWebhookSecret,
  useRemoveWebhookSecret,
} from '../hooks/useWebhooks';
import { useWorkflows } from '../hooks/useWorkflows';
import { useModuleEnabled } from '../hooks/useModules';
import { useT } from '../i18n';
import type { WebhookTrigger, WebhookSigningSecret } from '../types';

export function Webhooks() {
  const t = useT();
  const { data: webhooks, isLoading, error, refetch } = useWebhooks();
  const { data: workflows } = useWorkflows();
  const toggleWebhook = useToggleWebhook();
  const deleteWebhook = useDeleteWebhook();
  const regenerateToken = useRegenerateWebhookToken();
  const generateSecret = useGenerateWebhookSecret();
  const removeSecret = useRemoveWebhookSecret();
  const signaturesModule = useModuleEnabled('webhook-signatures');

  const [showCreate, setShowCreate] = useState(false);
  // Holds the freshly created/regenerated webhook while its token is still visible in full —
  // the token is never revealed again after this one-time modal is closed.
  const [revealedWebhook, setRevealedWebhook] = useState<WebhookTrigger | null>(null);
  // Pending destructive action awaiting confirmation in the project Modal (no window.confirm).
  const [confirmAction, setConfirmAction] = useState<
    { kind: WebhookConfirmKind; webhook: WebhookTrigger } | null
  >(null);
  // Signing secret right after it was set — like the token, shown exactly once
  const [revealedSecret, setRevealedSecret] = useState<WebhookSigningSecret | null>(null);

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

  const columns = webhookColumns({
    t,
    signaturesModule,
    onToggle: (w, enabled) => toggleWebhook.mutate({ id: w.id, enable: enabled }),
    onSetSecret: (w) => setSigningSecret(w),
    onConfirm: (kind, webhook) => setConfirmAction({ kind, webhook }),
  });

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

      <Hint id="webhooks-trigger" />

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

      <WebhookCreateModal
        open={showCreate}
        workflows={workflows}
        onClose={() => setShowCreate(false)}
        onCreated={setRevealedWebhook}
      />

      {/* Confirmation for regenerate / delete */}
      <ConfirmModal
        open={confirmAction !== null}
        onClose={() => setConfirmAction(null)}
        onConfirm={runConfirmedAction}
        title={confirmAction ? CONFIRM_TEXT[confirmAction.kind](t).title : ''}
        message={confirmAction && CONFIRM_TEXT[confirmAction.kind](t).text}
        confirmLabel={confirmAction ? CONFIRM_TEXT[confirmAction.kind](t).title : ''}
        variant="danger"
        isPending={regenerateToken.isPending || deleteWebhook.isPending || generateSecret.isPending || removeSecret.isPending}
      >
        {confirmAction && (
          <p className="text-xs font-mono text-text-tertiary mt-3 break-all">{confirmAction.webhook.webhookUrl}</p>
        )}
      </ConfirmModal>

      {/* One-time reveals: the token right after create/regenerate, the signing secret right after it was set */}
      <SecretRevealModal
        title={t.webhooks.tokenShownOnce}
        hint={t.webhooks.tokenShownOnceHint}
        values={revealedWebhook && [
          { label: t.webhooks.triggerUrl, value: revealedWebhook.webhookUrl },
          { label: t.webhooks.token, value: revealedWebhook.secretToken },
        ]}
        onClose={() => {
          setRevealedWebhook(null);
          // The token must not outlive the dialog in the mutation either (a created one is
          // dropped by WebhookCreateModal right after handing it over)
          regenerateToken.reset();
        }}
      />
      <SecretRevealModal
        title={t.webhooks.secretShownOnce}
        values={revealedSecret && [{ value: revealedSecret.secret, testId: 'webhook-secret' }]}
        onClose={() => {
          setRevealedSecret(null);
          generateSecret.reset();
        }}
      >
        <p className="text-sm text-text-secondary">{t.webhooks.secretShownOnceHint}</p>
        <code className="block font-mono text-xs text-text bg-surface-secondary rounded-lg px-3 py-2 break-all">
          {revealedSecret?.header}: sha256=&lt;hex&gt;
        </code>
      </SecretRevealModal>
    </div>
  );
}

const CONFIRM_TEXT: Record<WebhookConfirmKind, (t: ReturnType<typeof useT>) => { title: string; text: string }> = {
  regenerate: (t) => ({ title: t.webhooks.regenerate, text: t.webhooks.regenerateConfirm }),
  delete: (t) => ({ title: t.common.delete, text: t.webhooks.deleteConfirm }),
  rotateSecret: (t) => ({ title: t.webhooks.rotateSecret, text: t.webhooks.rotateSecretConfirm }),
  removeSecret: (t) => ({ title: t.webhooks.removeSecret, text: t.webhooks.removeSecretConfirm }),
};
