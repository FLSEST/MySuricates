import { useState } from 'react';
import { Plus, Trash2, ArrowRight, ArrowLeft } from 'lucide-react';
import { Card, CardHeader, Button, Modal, Select, Input, Toggle, Badge, Spinner } from '../ui';
import { useT } from '../../i18n';
import {
  useCompletionTriggersAsSource,
  useCompletionTriggersAsTarget,
  useCreateCompletionTrigger,
  useToggleCompletionTrigger,
  useDeleteCompletionTrigger,
} from '../../hooks/useCompletionTriggers';
import type { Workflow, CreateCompletionTriggerRequest } from '../../types';

interface Props {
  workflowId: string;
  allWorkflows?: Workflow[];
}

const defaultForm: Omit<CreateCompletionTriggerRequest, 'sourceWorkflowId'> = {
  targetWorkflowId: '',
  triggerOnSuccess: true,
  triggerOnFailure: false,
  triggerAlways: false,
  description: '',
};

export function CompletionTriggerSection({ workflowId, allWorkflows }: Props) {
  const t = useT();
  const ct = t.completionTriggers;

  const { data: asSource = [], isLoading: loadingSource } = useCompletionTriggersAsSource(workflowId);
  const { data: asTarget = [], isLoading: loadingTarget } = useCompletionTriggersAsTarget(workflowId);
  const createTrigger = useCreateCompletionTrigger();
  const toggleTrigger = useToggleCompletionTrigger(workflowId);
  const deleteTrigger = useDeleteCompletionTrigger(workflowId);

  const [showCreate, setShowCreate] = useState(false);
  const [form, setForm] = useState(defaultForm);

  const otherWorkflows = (allWorkflows ?? []).filter((w) => w.id !== workflowId);

  function handleCreate() {
    if (!form.targetWorkflowId) return;
    createTrigger.mutate(
      { ...form, sourceWorkflowId: workflowId },
      { onSuccess: () => { setShowCreate(false); setForm(defaultForm); } }
    );
  }

  function conditionLabel(triggerOnSuccess: boolean, triggerOnFailure: boolean, triggerAlways: boolean) {
    if (triggerAlways) return ct.triggerAlways;
    if (triggerOnSuccess && triggerOnFailure) return `${ct.triggerOnSuccess} + ${ct.triggerOnFailure}`;
    if (triggerOnSuccess) return ct.triggerOnSuccess;
    if (triggerOnFailure) return ct.triggerOnFailure;
    return '—';
  }

  return (
    <>
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 mt-6">
        {/* Triggers fired BY this workflow */}
        <Card padding={false}>
          <div className="p-5 pb-0">
            <CardHeader
              title={ct.asSource}
              action={
                <Button
                  size="sm"
                  variant="secondary"
                  icon={<Plus className="w-4 h-4" />}
                  onClick={() => setShowCreate(true)}
                >
                  {ct.create}
                </Button>
              }
            />
          </div>
          <div className="p-5 pt-0 space-y-2">
            {loadingSource ? (
              <div className="flex justify-center py-4"><Spinner /></div>
            ) : asSource.length === 0 ? (
              <p className="text-sm text-text-tertiary py-2">{ct.noTriggersAsSource}</p>
            ) : (
              asSource.map((trigger) => (
                <div key={trigger.id} className="flex items-center justify-between group py-1.5">
                  <div className="flex items-center gap-2 min-w-0">
                    <ArrowRight className="w-4 h-4 text-accent shrink-0" />
                    <span className="text-sm text-text truncate">{trigger.targetWorkflowName}</span>
                    <Badge>{conditionLabel(trigger.triggerOnSuccess, trigger.triggerOnFailure, trigger.triggerAlways)}</Badge>
                    {trigger.triggerCount > 0 && (
                      <span className="text-xs text-text-tertiary">{trigger.triggerCount}×</span>
                    )}
                  </div>
                  <div className="flex items-center gap-2 shrink-0">
                    <Toggle
                      enabled={trigger.enabled}
                      onChange={(e) => toggleTrigger.mutate({ id: trigger.id, enable: e })}
                      disabled={toggleTrigger.isPending}
                    />
                    <button
                      onClick={() => deleteTrigger.mutate(trigger.id)}
                      disabled={deleteTrigger.isPending}
                      className="p-1 rounded-sm text-text-tertiary hover:text-danger opacity-0 group-hover:opacity-100 transition-all"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </button>
                  </div>
                </div>
              ))
            )}
          </div>
        </Card>

        {/* Triggers that fire THIS workflow */}
        <Card padding={false}>
          <div className="p-5 pb-0">
            <CardHeader title={ct.asTarget} />
          </div>
          <div className="p-5 pt-0 space-y-2">
            {loadingTarget ? (
              <div className="flex justify-center py-4"><Spinner /></div>
            ) : asTarget.length === 0 ? (
              <p className="text-sm text-text-tertiary py-2">{ct.noTriggersAsTarget}</p>
            ) : (
              asTarget.map((trigger) => (
                <div key={trigger.id} className="flex items-center gap-2 py-1.5">
                  <ArrowLeft className="w-4 h-4 text-text-secondary shrink-0" />
                  <span className="text-sm text-text truncate">{trigger.sourceWorkflowName}</span>
                  <Badge>{conditionLabel(trigger.triggerOnSuccess, trigger.triggerOnFailure, trigger.triggerAlways)}</Badge>
                  {!trigger.enabled && (
                    <Badge>disabled</Badge>
                  )}
                </div>
              ))
            )}
          </div>
        </Card>
      </div>

      <Modal
        open={showCreate}
        onClose={() => { setShowCreate(false); setForm(defaultForm); }}
        title={ct.create}
        size="md"
      >
        <div className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-text-secondary mb-1.5">
              {ct.targetWorkflow}
            </label>
            <Select
              value={form.targetWorkflowId}
              onChange={(e) => setForm((f) => ({ ...f, targetWorkflowId: e.target.value }))}
              options={[
                { value: '', label: ct.selectTargetWorkflow },
                ...otherWorkflows.map((w) => ({ value: w.id, label: w.name })),
              ]}
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-text-secondary mb-2">
              {ct.conditionLabel}
            </label>
            <div className="space-y-2">
              <Toggle
                enabled={form.triggerAlways}
                onChange={(v) => setForm((f) => ({ ...f, triggerAlways: v, triggerOnSuccess: !v, triggerOnFailure: !v }))}
                label={ct.triggerAlways}
              />
              {!form.triggerAlways && (
                <>
                  <Toggle
                    enabled={form.triggerOnSuccess}
                    onChange={(v) => setForm((f) => ({ ...f, triggerOnSuccess: v }))}
                    label={ct.triggerOnSuccess}
                  />
                  <Toggle
                    enabled={form.triggerOnFailure}
                    onChange={(v) => setForm((f) => ({ ...f, triggerOnFailure: v }))}
                    label={ct.triggerOnFailure}
                  />
                </>
              )}
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium text-text-secondary mb-1.5">
              {ct.description}
            </label>
            <Input
              value={form.description ?? ''}
              onChange={(e) => setForm((f) => ({ ...f, description: e.target.value }))}
              placeholder={ct.description}
            />
          </div>

          <div className="flex justify-end gap-3 pt-2">
            <Button variant="secondary" onClick={() => { setShowCreate(false); setForm(defaultForm); }}>
              {t.common.cancel}
            </Button>
            <Button
              onClick={handleCreate}
              disabled={!form.targetWorkflowId || createTrigger.isPending}
              loading={createTrigger.isPending}
            >
              {ct.create}
            </Button>
          </div>
        </div>
      </Modal>
    </>
  );
}
