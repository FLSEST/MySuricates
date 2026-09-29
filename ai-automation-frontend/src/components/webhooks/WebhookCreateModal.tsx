import { useState } from 'react';
import { Button, Input, Select, Modal } from '../ui';
import { useCreateWebhook } from '../../hooks/useWebhooks';
import { useT } from '../../i18n';
import type { CreateWebhookRequest, WebhookTrigger, Workflow } from '../../types';

/**
 * "New webhook": pick the workflow, optionally describe the caller. The form keeps its input when
 * the dialog is closed without creating and starts empty after a webhook was created.
 */
export function WebhookCreateModal({ open, workflows, onClose, onCreated }: {
  open: boolean;
  workflows: Workflow[] | undefined;
  onClose: () => void;
  /** Receives the new webhook while its token is still readable in full. */
  onCreated: (webhook: WebhookTrigger) => void;
}) {
  const t = useT();
  const createWebhook = useCreateWebhook();
  const [form, setForm] = useState<CreateWebhookRequest>({ workflowId: '' });

  const handleCreate = () => {
    createWebhook.mutate(form, {
      onSuccess: (webhook) => {
        onClose();
        setForm({ workflowId: '' });
        onCreated(webhook);
        // The page shows the full token once; the dialog keeps no copy of it
        createWebhook.reset();
      },
    });
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={t.webhooks.create}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
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
          placeholder={t.webhooks.descriptionPlaceholder}
        />
      </div>
    </Modal>
  );
}
