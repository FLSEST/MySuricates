import { useState } from 'react';
import { Bell, Plus, Trash2 } from 'lucide-react';
import { Card, CardHeader, Button, Modal, Input, Select, Toggle } from '../ui';
import { useT } from '../../i18n';
import { useNotifications, useCreateNotification, useToggleNotification, useDeleteNotification } from '../../hooks/useNotifications';
import type { CreateNotificationConfigRequest, NotificationChannelType } from '../../types';

const defaultForm: CreateNotificationConfigRequest = {
  name: '',
  channelType: 'WEBHOOK',
  webhookUrl: '',
  workflowId: undefined,
  triggerOnSuccess: true,
  triggerOnFailure: true,
  triggerAlways: false,
  messageTemplate: '',
};

export function NotificationsCard() {
  const t = useT();
  const nt = t.notifications;

  const { data: configs = [] } = useNotifications();
  const createNotification = useCreateNotification();
  const toggleNotification = useToggleNotification();
  const deleteNotification = useDeleteNotification();

  const [showCreate, setShowCreate] = useState(false);
  const [form, setForm] = useState<CreateNotificationConfigRequest>(defaultForm);

  function handleCreate() {
    if (!form.name) return;
    createNotification.mutate(
      { ...form, webhookUrl: form.webhookUrl || undefined, messageTemplate: form.messageTemplate || undefined },
      { onSuccess: () => { setShowCreate(false); setForm(defaultForm); } }
    );
  }

  return (
    <>
      <Card>
        <CardHeader
          title={nt.title}
          subtitle={nt.subtitle}
          action={
            <Button
              size="sm"
              variant="secondary"
              icon={<Plus className="w-4 h-4" />}
              onClick={() => setShowCreate(true)}
            >
              {nt.create}
            </Button>
          }
        />

        {configs.length === 0 ? (
          <div className="flex items-center gap-3 py-4 text-sm text-text-tertiary">
            <Bell className="w-4 h-4 shrink-0" />
            <span>{nt.noNotifications}</span>
          </div>
        ) : (
          <div className="space-y-3">
            {configs.map((config) => (
              <div key={config.id} className="flex items-center justify-between group py-2 border-b border-border last:border-0">
                <div className="min-w-0">
                  <div className="flex items-center gap-2">
                    <Bell className="w-4 h-4 text-accent shrink-0" />
                    <span className="text-sm font-medium text-text">{config.name}</span>
                    <span className="text-xs text-text-tertiary">
                      {config.channelType === 'WEBHOOK' ? 'Webhook' : 'Desktop'}
                    </span>
                    {config.workflowId == null && (
                      <span className="text-xs px-1.5 py-0.5 rounded-full bg-accent/10 text-accent">global</span>
                    )}
                  </div>
                  {config.webhookUrl && (
                    <p className="text-xs text-text-tertiary mt-0.5 ml-6 truncate max-w-xs">{config.webhookUrl}</p>
                  )}
                </div>
                <div className="flex items-center gap-2 shrink-0">
                  <Toggle
                    enabled={config.enabled}
                    onChange={(e) => toggleNotification.mutate({ id: config.id, enable: e })}
                    disabled={toggleNotification.isPending}
                  />
                  <button
                    onClick={() => deleteNotification.mutate(config.id)}
                    disabled={deleteNotification.isPending}
                    className="p-1 rounded-sm text-text-tertiary hover:text-danger opacity-0 group-hover:opacity-100 transition-all"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>

      <Modal
        open={showCreate}
        onClose={() => { setShowCreate(false); setForm(defaultForm); }}
        title={nt.create}
        size="md"
      >
        <div className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-text-secondary mb-1.5">{nt.name}</label>
            <Input
              value={form.name}
              onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))}
              placeholder={nt.name}
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-text-secondary mb-1.5">{nt.channel}</label>
            <Select
              value={form.channelType}
              onChange={(e) => setForm((f) => ({ ...f, channelType: e.target.value as NotificationChannelType }))}
              options={[
                { value: 'WEBHOOK', label: nt.webhook },
                { value: 'DESKTOP', label: nt.desktop },
              ]}
            />
          </div>

          {form.channelType === 'WEBHOOK' && (
            <div>
              <label className="block text-sm font-medium text-text-secondary mb-1.5">{nt.webhookUrl}</label>
              <Input
                value={form.webhookUrl ?? ''}
                onChange={(e) => setForm((f) => ({ ...f, webhookUrl: e.target.value }))}
                placeholder="https://hooks.slack.com/services/..."
              />
              <p className="text-xs text-text-tertiary mt-1">{nt.webhookUrlHint}</p>
            </div>
          )}

          <div>
            <label className="block text-sm font-medium text-text-secondary mb-2">{nt.conditionLabel}</label>
            <div className="space-y-2">
              <Toggle
                enabled={form.triggerAlways}
                onChange={(v) => setForm((f) => ({ ...f, triggerAlways: v, triggerOnSuccess: !v, triggerOnFailure: !v }))}
                label={nt.triggerAlways}
              />
              {!form.triggerAlways && (
                <>
                  <Toggle
                    enabled={form.triggerOnSuccess}
                    onChange={(v) => setForm((f) => ({ ...f, triggerOnSuccess: v }))}
                    label={nt.triggerOnSuccess}
                  />
                  <Toggle
                    enabled={form.triggerOnFailure}
                    onChange={(v) => setForm((f) => ({ ...f, triggerOnFailure: v }))}
                    label={nt.triggerOnFailure}
                  />
                </>
              )}
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium text-text-secondary mb-1.5">{nt.messageTemplate}</label>
            <Input
              value={form.messageTemplate ?? ''}
              onChange={(e) => setForm((f) => ({ ...f, messageTemplate: e.target.value }))}
              placeholder={nt.messageTemplatePlaceholder}
            />
          </div>

          <div className="flex justify-end gap-3 pt-2">
            <Button variant="secondary" onClick={() => { setShowCreate(false); setForm(defaultForm); }}>
              {t.common.cancel}
            </Button>
            <Button
              onClick={handleCreate}
              disabled={!form.name || createNotification.isPending}
              loading={createNotification.isPending}
            >
              {nt.create}
            </Button>
          </div>
        </div>
      </Modal>
    </>
  );
}
