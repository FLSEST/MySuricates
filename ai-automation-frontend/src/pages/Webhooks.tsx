import { useState } from 'react';
import { Webhook, Plus, Trash2, Copy, Check } from 'lucide-react';
import { Card, CardHeader, Button, Input, Select, Modal, Spinner, Table, Toggle, Badge } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { useWebhooks, useCreateWebhook, useToggleWebhook, useDeleteWebhook } from '../hooks/useWebhooks';
import { useWorkflows } from '../hooks/useWorkflows';
import { useT } from '../i18n';
import type { WebhookTrigger, CreateWebhookRequest } from '../types';

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);

  const handleCopy = (e: React.MouseEvent) => {
    e.stopPropagation();
    navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <button
      onClick={handleCopy}
      className="p-1 rounded text-text-tertiary hover:text-accent transition-colors"
      title="Copy"
      aria-label="Copy"
    >
      {copied ? <Check className="w-3.5 h-3.5 text-success" /> : <Copy className="w-3.5 h-3.5" />}
    </button>
  );
}

export function Webhooks() {
  const t = useT();
  const { data: webhooks, isLoading, error, refetch } = useWebhooks();
  const { data: workflows } = useWorkflows();
  const createWebhook = useCreateWebhook();
  const toggleWebhook = useToggleWebhook();
  const deleteWebhook = useDeleteWebhook();

  const [showCreate, setShowCreate] = useState(false);
  const [form, setForm] = useState<CreateWebhookRequest>({ workflowId: '' });

  const handleCreate = () => {
    createWebhook.mutate(form, {
      onSuccess: () => {
        setShowCreate(false);
        setForm({ workflowId: '' });
      },
    });
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
        <div className="flex items-center gap-2 min-w-0">
          <span className="font-mono text-xs text-text-secondary truncate max-w-[300px]">
            {w.webhookUrl}
          </span>
          <CopyButton text={w.webhookUrl} />
        </div>
      ),
    },
    {
      key: 'token',
      header: t.webhooks.token,
      render: (w: WebhookTrigger) => (
        <div className="flex items-center gap-2">
          <span className="font-mono text-xs text-text-tertiary">
            {w.secretToken.slice(0, 8)}...
          </span>
          <CopyButton text={w.secretToken} />
        </div>
      ),
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
        <button
          onClick={(e) => {
            e.stopPropagation();
            deleteWebhook.mutate(w.id);
          }}
          className="p-1.5 rounded-lg text-text-tertiary hover:text-danger transition-colors"
          aria-label={t.common.delete}
        >
          <Trash2 className="w-4 h-4" />
        </button>
      ),
      width: '48px',
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
    </div>
  );
}
