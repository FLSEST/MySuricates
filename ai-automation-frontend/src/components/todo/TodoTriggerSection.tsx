import { useEffect, useRef, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { Plus, Trash2, Zap, CheckCircle } from 'lucide-react';
import { Card, CardHeader, Button, Modal, Select, Input, Toggle, Badge, Spinner } from '../ui';
import { useT } from '../../i18n';
import {
  useTodoTriggers,
  useCreateTodoTrigger,
  useToggleTodoTrigger,
  useDeleteTodoTrigger,
} from '../../hooks/useTodoTriggers';
import { useWorkflows } from '../../hooks/useWorkflows';
import type { CreateTodoTriggerRequest } from '../../types';

const defaultForm: CreateTodoTriggerRequest = {
  tagPattern: '',
  targetWorkflowId: '',
  description: '',
  maxAttempts: 3,
  autoClose: true,
};

export function TodoTriggerSection() {
  const t = useT();
  const tt = t.todoTriggers;

  const { data: triggers = [], isLoading } = useTodoTriggers();
  const { data: workflows = [] } = useWorkflows();
  const createTrigger = useCreateTodoTrigger();
  const toggleTrigger = useToggleTodoTrigger();
  const deleteTrigger = useDeleteTodoTrigger();

  const [showCreate, setShowCreate] = useState(false);
  // Sprungziel „TodoTrigger verwalten“ aus der Relationskarte eines Ziels (TODO c6ea8f5c)
  const location = useLocation();
  const anchorRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (location.hash === '#todo-triggers') anchorRef.current?.scrollIntoView?.({ block: 'start', behavior: 'smooth' });
  }, [location.hash]);
  const [form, setForm] = useState<CreateTodoTriggerRequest>(defaultForm);

  function handleCreate() {
    if (!form.tagPattern || !form.targetWorkflowId) return;
    createTrigger.mutate(form, {
      onSuccess: () => {
        setShowCreate(false);
        setForm(defaultForm);
      },
    });
  }

  return (
    <>
      <div id="todo-triggers" ref={anchorRef} className="scroll-mt-4" />
      <Card padding={false}>
        <div className="p-5 pb-0">
          <CardHeader
            title={tt.title}
            subtitle={tt.subtitle}
            action={
              <Button
                size="sm"
                variant="secondary"
                icon={<Plus className="w-4 h-4" />}
                onClick={() => setShowCreate(true)}
              >
                {tt.create}
              </Button>
            }
          />
        </div>
        <div className="p-5 pt-3 space-y-2">
          {isLoading ? (
            <div className="flex justify-center py-4"><Spinner /></div>
          ) : triggers.length === 0 ? (
            <p className="text-sm text-text-tertiary py-2">{tt.noTriggers}</p>
          ) : (
            triggers.map((trigger) => (
              <div key={trigger.id} className="flex items-center justify-between group py-2 border-b border-border last:border-0">
                <div className="flex items-center gap-2 min-w-0">
                  <Zap className="w-4 h-4 text-accent shrink-0" />
                  <div className="min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="text-sm font-medium text-text truncate">
                        {trigger.description || trigger.tagPattern}
                      </span>
                      {trigger.autoClose && (
                        <CheckCircle className="w-3.5 h-3.5 text-success shrink-0" />
                      )}
                    </div>
                    <div className="flex items-center gap-2 mt-0.5">
                      <Badge>{trigger.tagPattern}</Badge>
                      <span className="text-xs text-text-tertiary">
                        → {trigger.targetWorkflowName}
                      </span>
                      {trigger.triggerCount > 0 && (
                        <span className="text-xs text-text-tertiary">
                          {trigger.triggerCount}×
                        </span>
                      )}
                      <span className="text-xs text-text-tertiary">
                        max {trigger.maxAttempts}
                      </span>
                    </div>
                  </div>
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

      <Modal
        open={showCreate}
        onClose={() => { setShowCreate(false); setForm(defaultForm); }}
        title={tt.create}
        size="md"
      >
        <div className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-text-secondary mb-1.5">
              {tt.tagPattern}
            </label>
            <Input
              value={form.tagPattern}
              onChange={(e) => setForm((f) => ({ ...f, tagPattern: e.target.value }))}
              placeholder="backend,testing"
            />
            <p className="text-xs text-text-tertiary mt-1">{tt.tagPatternHelp}</p>
          </div>

          <div>
            <label className="block text-sm font-medium text-text-secondary mb-1.5">
              {tt.targetWorkflow}
            </label>
            <Select
              value={form.targetWorkflowId}
              onChange={(e) => setForm((f) => ({ ...f, targetWorkflowId: e.target.value }))}
              options={[
                { value: '', label: tt.selectTargetWorkflow },
                ...workflows.map((w) => ({ value: w.id, label: w.name })),
              ]}
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-text-secondary mb-1.5">
              {tt.description}
            </label>
            <Input
              value={form.description ?? ''}
              onChange={(e) => setForm((f) => ({ ...f, description: e.target.value }))}
              placeholder={tt.description}
            />
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-text-secondary mb-1.5">
                {tt.maxAttempts}
              </label>
              <Input
                type="number"
                value={String(form.maxAttempts ?? 3)}
                onChange={(e) => setForm((f) => ({ ...f, maxAttempts: parseInt(e.target.value) || 3 }))}
                min={1}
                max={10}
              />
            </div>
            <div className="flex items-end pb-1">
              <Toggle
                enabled={form.autoClose ?? true}
                onChange={(v) => setForm((f) => ({ ...f, autoClose: v }))}
                label={tt.autoClose}
              />
            </div>
          </div>

          <div className="flex justify-end gap-3 pt-2">
            <Button variant="secondary" onClick={() => { setShowCreate(false); setForm(defaultForm); }}>
              {t.common.cancel}
            </Button>
            <Button
              onClick={handleCreate}
              disabled={!form.tagPattern || !form.targetWorkflowId || createTrigger.isPending}
              loading={createTrigger.isPending}
            >
              {tt.create}
            </Button>
          </div>
        </div>
      </Modal>
    </>
  );
}
