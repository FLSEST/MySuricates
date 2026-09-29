import { useState, useMemo } from 'react';
import { X } from 'lucide-react';
import { Button, Input, Modal, Toggle } from '../ui';
import { useT } from '../../i18n';
import type { WorkflowChain, CreateChainRequest, UpdateChainRequest, ChainEntryRequest } from '../../types';

interface ChainFormModalProps {
  title: string;
  chain?: WorkflowChain;
  workflows: Array<{ id: string; name: string; projectName: string }>;
  onSave: (data: CreateChainRequest | UpdateChainRequest) => void;
  onClose: () => void;
  saving: boolean;
}

export function ChainFormModal({ title, chain, workflows, onSave, onClose, saving }: ChainFormModalProps) {
  const t = useT();
  const [name, setName] = useState(chain?.name ?? '');
  const [description, setDescription] = useState(chain?.description ?? '');
  const [icon, setIcon] = useState(chain?.icon ?? '');
  const [stopOnFailure, setStopOnFailure] = useState(chain?.stopOnFailure ?? true);
  const [entries, setEntries] = useState<ChainEntryRequest[]>(
    chain?.entries.map(e => ({
      workflowId: e.workflowId,
      label: e.label ?? undefined,
      sortOrder: e.sortOrder,
      stopOnFailure: e.stopOnFailure,
      inputContext: e.inputContext,
      timeoutMinutes: e.timeoutMinutes ?? undefined,
    })) ?? []
  );
  const [workflowSearch, setWorkflowSearch] = useState('');

  const filteredWorkflows = useMemo(() => {
    if (!workflowSearch) return workflows;
    const q = workflowSearch.toLowerCase();
    return workflows.filter(w => w.name.toLowerCase().includes(q) || w.projectName.toLowerCase().includes(q));
  }, [workflows, workflowSearch]);

  const addEntry = (workflowId: string) => {
    setEntries(prev => [...prev, {
      workflowId,
      sortOrder: prev.length,
      stopOnFailure: true,
    }]);
    setWorkflowSearch('');
  };

  const removeEntry = (index: number) => {
    setEntries(prev => prev.filter((_, i) => i !== index).map((e, i) => ({ ...e, sortOrder: i })));
  };

  const moveEntry = (from: number, to: number) => {
    setEntries(prev => {
      const next = [...prev];
      const item = next.splice(from, 1)[0];
      if (!item) return prev;
      next.splice(to, 0, item);
      return next.map((e, i) => ({ ...e, sortOrder: i }));
    });
  };

  const handleSave = () => {
    onSave({
      name,
      description: description || undefined,
      icon: icon || undefined,
      entries,
      stopOnFailure,
    });
  };

  return (
    <Modal open onClose={onClose} title={title}>
      <div className="space-y-4">
        <div className="grid grid-cols-[1fr_auto] gap-3">
          <Input
            label={t.common.name}
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={t.chains.namePlaceholder}
          />
          <Input
            label={t.workflows.iconLabel}
            value={icon}
            onChange={(e) => setIcon(e.target.value)}
            placeholder="🔗"
            className="w-20"
          />
        </div>

        <Input
          label={t.common.description}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder=""
        />

        <Toggle
          label={t.chains.stopOnFailure}
          enabled={stopOnFailure}
          onChange={setStopOnFailure}
        />

        {/* Entries */}
        <div>
          <label className="block text-sm font-medium text-text-secondary mb-2">
            {t.chains.entries}
          </label>

          {entries.length > 0 && (
            <div className="space-y-2 mb-3">
              {entries.map((entry, i) => {
                const wf = workflows.find(w => w.id === entry.workflowId);
                return (
                  <div key={i} className="flex items-center gap-2 p-2 rounded-lg bg-surface-raised border border-border">
                    <span className="text-xs font-mono text-text-tertiary w-5 text-center">{i + 1}</span>
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-medium text-text-primary truncate">{wf?.name ?? entry.workflowId}</p>
                      {wf?.projectName && <p className="text-xs text-text-tertiary">{wf.projectName}</p>}
                    </div>
                    <div className="flex items-center gap-1">
                      {i > 0 && (
                        <Button variant="ghost" size="sm" onClick={() => moveEntry(i, i - 1)}>↑</Button>
                      )}
                      {i < entries.length - 1 && (
                        <Button variant="ghost" size="sm" onClick={() => moveEntry(i, i + 1)}>↓</Button>
                      )}
                      <Button variant="ghost" size="sm" icon={<X className="w-3 h-3" />} onClick={() => removeEntry(i)} title={t.common.delete} />
                    </div>
                  </div>
                );
              })}
            </div>
          )}

          {/* Add workflow */}
          <div className="relative">
            <Input
              value={workflowSearch}
              onChange={(e) => setWorkflowSearch(e.target.value)}
              placeholder={t.chains.selectWorkflow}
            />
            {workflowSearch && filteredWorkflows.length > 0 && (
              <div className="absolute z-10 mt-1 w-full max-h-48 overflow-y-auto rounded-lg border border-border bg-surface shadow-lg">
                {filteredWorkflows.slice(0, 20).map(wf => (
                  <button
                    key={wf.id}
                    className="w-full text-left px-3 py-2 hover:bg-surface-raised text-sm transition-colors"
                    onClick={() => addEntry(wf.id)}
                  >
                    <span className="font-medium text-text-primary">{wf.name}</span>
                    <span className="text-text-tertiary ml-2">{wf.projectName}</span>
                  </button>
                ))}
              </div>
            )}
          </div>
        </div>

        <div className="flex justify-end gap-2 pt-2">
          <Button variant="ghost" onClick={onClose}>{t.common.cancel}</Button>
          <Button onClick={handleSave} disabled={!name || entries.length === 0 || saving}>
            {saving ? t.common.loading : t.common.save}
          </Button>
        </div>
      </div>
    </Modal>
  );
}
