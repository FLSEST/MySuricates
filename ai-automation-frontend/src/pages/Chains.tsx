import { useState, useMemo, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Link2, Plus, Play, Trash2, Pencil, ArrowDown, ChevronRight, X, Layers } from 'lucide-react';
import { Card, Button, Input, Modal, Spinner, Badge, Toggle } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { ExecuteDialog } from '../components/workflow/ExecuteDialog';
import { useChains, useCreateChain, useUpdateChain, useDeleteChain, useExecuteChain, useChainRuns } from '../hooks/useChains';
import { useChainTemplates, useInstallChainTemplate } from '../hooks/useChainTemplates';
import { useWorkflows } from '../hooks/useWorkflows';
import { useProjects } from '../hooks/useProjects';
import { useT } from '../i18n';
import { useI18nStore } from '../i18n';
import { formatDuration, formatRelativeTime } from '../utils/formatTime';
import { extractWorkflowVariables } from '../utils/extractWorkflowVariables';
import type { WorkflowChain, CreateChainRequest, UpdateChainRequest, ChainEntryRequest, ChainTemplate } from '../types';

function StatusBadge({ status }: { status: string }) {
  const variant = (status === 'COMPLETED' || status === 'COMPLETED_WITH_ERRORS') ? 'success'
    : status === 'FAILED' ? 'danger'
    : status === 'RUNNING' ? 'accent'
    : status === 'CANCELLED' ? 'warning'
    : 'default';
  const label = status === 'COMPLETED_WITH_ERRORS' ? 'Completed' : status;
  return <Badge variant={variant} dot>{label}</Badge>;
}

export function Chains() {
  const t = useT();
  const navigate = useNavigate();
  const { data: chains, isLoading, error, refetch } = useChains();
  const { data: workflows } = useWorkflows();
  const createChain = useCreateChain();
  const updateChain = useUpdateChain();
  const deleteChain = useDeleteChain();
  const executeChain = useExecuteChain();

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [showTemplateModal, setShowTemplateModal] = useState(false);
  const [editingChain, setEditingChain] = useState<WorkflowChain | null>(null);
  const [deleteConfirm, setDeleteConfirm] = useState<{ open: boolean; chain: WorkflowChain | null }>({ open: false, chain: null });
  const [executeTarget, setExecuteTarget] = useState<WorkflowChain | null>(null);

  const filtered = useMemo(() => {
    if (!chains) return [];
    if (!search) return chains;
    const q = search.toLowerCase();
    return chains.filter(c => c.name.toLowerCase().includes(q) || c.description?.toLowerCase().includes(q));
  }, [chains, search]);

  const selectedChain = useMemo(() => {
    if (selectedId) return filtered.find(c => c.id === selectedId) ?? filtered[0];
    return filtered[0];
  }, [filtered, selectedId]);

  // Collect all template variables and defaults across chain workflows
  const getChainVariables = useCallback((chain: WorkflowChain) => {
    if (!workflows) return { variables: [] as string[], defaults: {} as Record<string, string> };
    const allVars = new Set<string>();
    const defaults: Record<string, string> = {};
    for (const entry of chain.entries) {
      const wf = workflows.find(w => w.id === entry.workflowId);
      if (!wf) continue;
      for (const v of extractWorkflowVariables(wf.blocks)) {
        allVars.add(v);
      }
      if (wf.defaultContext) {
        for (const [k, v] of Object.entries(wf.defaultContext)) {
          if (!(k in defaults)) defaults[k] = v;
        }
      }
    }
    return { variables: Array.from(allVars), defaults };
  }, [workflows]);

  const handleExecute = useCallback((chain: WorkflowChain) => {
    const { variables } = getChainVariables(chain);
    if (variables.length > 0) {
      setExecuteTarget(chain);
    } else {
      executeChain.mutate({ id: chain.id }, {
        onSuccess: (run) => {
          navigate(`/chains/${chain.id}/run/${run.id}`);
        },
      });
    }
  }, [executeChain, navigate, getChainVariables]);

  const handleExecuteWithContext = useCallback((context: Record<string, string>) => {
    if (!executeTarget) return;
    executeChain.mutate({ id: executeTarget.id, inputContext: context }, {
      onSuccess: (run) => {
        setExecuteTarget(null);
        navigate(`/chains/${executeTarget.id}/run/${run.id}`);
      },
    });
  }, [executeChain, executeTarget, navigate]);

  const handleDelete = useCallback(() => {
    if (!deleteConfirm.chain) return;
    deleteChain.mutate(deleteConfirm.chain.id, {
      onSuccess: () => {
        setDeleteConfirm({ open: false, chain: null });
        if (selectedId === deleteConfirm.chain?.id) setSelectedId(null);
      },
    });
  }, [deleteChain, deleteConfirm, selectedId]);

  if (isLoading) {
    return <div className="flex items-center justify-center h-64"><Spinner text={t.common.loading} /></div>;
  }

  if (error) {
    return <ErrorState message={error.message} onRetry={() => refetch()} />;
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-text-primary">{t.chains.title}</h1>
          <p className="text-text-secondary mt-1">{t.chains.subtitle}</p>
        </div>
        <div className="flex gap-2">
          <Button variant="ghost" onClick={() => setShowTemplateModal(true)} icon={<Layers className="w-4 h-4" />}>
            {t.chains.fromTemplate}
          </Button>
          <Button onClick={() => setShowCreate(true)} icon={<Plus className="w-4 h-4" />}>
            {t.chains.create}
          </Button>
        </div>
      </div>

      {(!chains || chains.length === 0) ? (
        <EmptyState
          icon={<Link2 className="w-8 h-8 text-text-tertiary" />}
          title={t.chains.noChains}
          action={<Button onClick={() => setShowCreate(true)}>{t.chains.create}</Button>}
        />
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* Left: Chain List */}
          <div className="lg:col-span-1 space-y-3">
            <Input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder={t.common.search}
            />
            <div className="space-y-2">
              {filtered.map(chain => (
                <Card
                  key={chain.id}
                  className={`cursor-pointer transition-all ${selectedChain?.id === chain.id ? 'ring-2 ring-accent' : ''}`}
                  onClick={() => setSelectedId(chain.id)}
                >
                  <div className="p-3">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2 min-w-0">
                        <span className="text-lg">{chain.icon || '🔗'}</span>
                        <div className="min-w-0">
                          <p className="font-medium text-text-primary truncate">{chain.name}</p>
                          <p className="text-xs text-text-tertiary">
                            {chain.entries.length} {t.chains.entries} · {chain.runCount} {t.common.runs}
                          </p>
                        </div>
                      </div>
                      <Button
                        variant="ghost"
                        size="sm"
                        icon={<Play className="w-4 h-4" />}
                        onClick={(e) => { e.stopPropagation(); handleExecute(chain); }}
                        disabled={chain.entries.length === 0}
                      />
                    </div>
                  </div>
                </Card>
              ))}
            </div>
          </div>

          {/* Right: Detail Panel */}
          <div className="lg:col-span-2">
            {selectedChain ? (
              <ChainDetail
                chain={selectedChain}
                onExecute={() => handleExecute(selectedChain)}
                onEdit={() => setEditingChain(selectedChain)}
                onDelete={() => setDeleteConfirm({ open: true, chain: selectedChain })}
              />
            ) : (
              <Card className="p-8 text-center text-text-tertiary">
                {t.chains.noChains}
              </Card>
            )}
          </div>
        </div>
      )}

      {/* Create Modal */}
      {showCreate && (
        <ChainFormModal
          title={t.chains.create}
          workflows={workflows ?? []}
          onSave={(data) => {
            createChain.mutate(data as CreateChainRequest, { onSuccess: () => setShowCreate(false) });
          }}
          onClose={() => setShowCreate(false)}
          saving={createChain.isPending}
        />
      )}

      {/* Edit Modal */}
      {editingChain && (
        <ChainFormModal
          title={t.chains.editChain}
          chain={editingChain}
          workflows={workflows ?? []}
          onSave={(data) => {
            updateChain.mutate({ id: editingChain.id, data }, { onSuccess: () => setEditingChain(null) });
          }}
          onClose={() => setEditingChain(null)}
          saving={updateChain.isPending}
        />
      )}

      {/* Delete Confirm */}
      <Modal open={deleteConfirm.open} onClose={() => setDeleteConfirm({ open: false, chain: null })} title={t.common.delete}>
        <p className="text-text-secondary mb-4">{t.chains.deleteConfirm}</p>
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => setDeleteConfirm({ open: false, chain: null })}>{t.common.cancel}</Button>
          <Button variant="danger" onClick={handleDelete} disabled={deleteChain.isPending}>{t.common.delete}</Button>
        </div>
      </Modal>

      {/* Execute with variables dialog */}
      {executeTarget && (() => {
        const { variables, defaults } = getChainVariables(executeTarget);
        return (
          <ExecuteDialog
            open={true}
            onClose={() => setExecuteTarget(null)}
            onExecute={handleExecuteWithContext}
            variables={variables}
            defaultValues={defaults}
            loading={executeChain.isPending}
          />
        );
      })()}

      {/* Chain Template Modal */}
      {showTemplateModal && (
        <ChainTemplateModal onClose={() => setShowTemplateModal(false)} />
      )}
    </div>
  );
}

/* ── Chain Template Modal ────────────────────────────── */

function ChainTemplateModal({ onClose }: { onClose: () => void }) {
  const t = useT();
  const locale = useI18nStore(s => s.locale);
  const { data: templates, isLoading } = useChainTemplates();
  const { data: projects } = useProjects();
  const installTemplate = useInstallChainTemplate();

  const [selectedTemplate, setSelectedTemplate] = useState<ChainTemplate | null>(null);
  const [projectId, setProjectId] = useState('');
  const [chainName, setChainName] = useState('');

  const activeProjects = (projects ?? []).filter(p => !p.archivedAt);

  const handleInstall = () => {
    if (!selectedTemplate || !projectId) return;
    installTemplate.mutate(
      { templateId: selectedTemplate.id, projectId, chainName: chainName || undefined },
      { onSuccess: () => onClose() }
    );
  };

  const resolveName = (names: Record<string, string>) =>
    names[locale] ?? names['de'] ?? names['en'] ?? Object.values(names)[0] ?? '';

  return (
    <Modal open onClose={onClose} title={t.chains.chainTemplates} size="lg">
      <p className="text-sm text-text-secondary mb-4">{t.chains.chainTemplatesSubtitle}</p>
      {isLoading ? (
        <div className="flex justify-center py-8"><Spinner text={t.common.loading} /></div>
      ) : (
        <div className="space-y-4">
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 max-h-64 overflow-y-auto">
            {(templates ?? []).map(tmpl => (
              <Card
                key={tmpl.id}
                className={`cursor-pointer transition-all ${selectedTemplate?.id === tmpl.id ? 'ring-2 ring-accent' : 'hover:border-border-strong'}`}
                onClick={() => setSelectedTemplate(tmpl)}
              >
                <div className="flex items-start gap-3">
                  <span className="text-2xl">{tmpl.icon}</span>
                  <div className="min-w-0">
                    <p className="font-semibold text-text-primary text-sm">{resolveName(tmpl.name)}</p>
                    <p className="text-xs text-text-secondary mt-0.5">{resolveName(tmpl.description)}</p>
                    <p className="text-xs text-text-tertiary mt-1">
                      {t.chains.installSteps.replace('{count}', String(tmpl.entries.length))}
                    </p>
                  </div>
                </div>
              </Card>
            ))}
          </div>

          {selectedTemplate && (
            <div className="space-y-3 pt-2 border-t border-border">
              <div>
                <label className="text-sm font-medium text-text-secondary mb-1 block">{t.chains.selectProject}</label>
                <select
                  value={projectId}
                  onChange={e => setProjectId(e.target.value)}
                  className="w-full px-3 py-2 rounded-lg bg-surface border border-border text-text-primary text-sm focus:outline-none focus:ring-2 focus:ring-accent"
                >
                  <option value="">— {t.chains.selectProject} —</option>
                  {activeProjects.map(p => (
                    <option key={p.id} value={p.id}>{p.name}</option>
                  ))}
                </select>
              </div>
              <div>
                <label className="text-sm font-medium text-text-secondary mb-1 block">{t.chains.chainName}</label>
                <Input
                  value={chainName}
                  onChange={e => setChainName(e.target.value)}
                  placeholder={t.chains.chainNamePlaceholder}
                />
              </div>
            </div>
          )}
        </div>
      )}
      <div className="flex justify-end gap-2 pt-4">
        <Button variant="ghost" onClick={onClose}>{t.common.cancel}</Button>
        <Button
          onClick={handleInstall}
          disabled={!selectedTemplate || !projectId || installTemplate.isPending}
        >
          {installTemplate.isPending ? t.common.loading : t.chains.installTemplate}
        </Button>
      </div>
    </Modal>
  );
}

/* ── Chain Detail Panel ──────────────────────────────── */

function ChainDetail({ chain, onExecute, onEdit, onDelete }: {
  chain: WorkflowChain;
  onExecute: () => void;
  onEdit: () => void;
  onDelete: () => void;
}) {
  const t = useT();
  const navigate = useNavigate();
  const { data: runs } = useChainRuns(chain.id);

  return (
    <div className="space-y-4">
      <Card>
        <div className="p-4">
          <div className="flex items-start justify-between mb-3">
            <div>
              <h2 className="text-xl font-bold text-text-primary flex items-center gap-2">
                <span>{chain.icon || '🔗'}</span>
                {chain.name}
              </h2>
              {chain.description && (
                <p className="text-text-secondary mt-1">{chain.description}</p>
              )}
            </div>
            <div className="flex items-center gap-2">
              <Button variant="ghost" size="sm" icon={<Pencil className="w-4 h-4" />} onClick={onEdit} title={t.common.edit} />
              <Button variant="ghost" size="sm" icon={<Trash2 className="w-4 h-4" />} onClick={onDelete} title={t.common.delete} />
              <Button icon={<Play className="w-4 h-4" />} onClick={onExecute} disabled={chain.entries.length === 0}>
                {t.chains.execute}
              </Button>
            </div>
          </div>

          {chain.stopOnFailure && (
            <Badge variant="warning">{t.chains.stopOnFailure}</Badge>
          )}
        </div>
      </Card>

      {/* Pipeline Visualization */}
      <Card>
        <div className="p-4">
          <h3 className="text-sm font-semibold text-text-secondary uppercase tracking-wide mb-3">
            {t.chains.entries} ({chain.entries.length})
          </h3>
          {chain.entries.length === 0 ? (
            <p className="text-text-tertiary text-sm">{t.chains.noEntries}</p>
          ) : (
            <div className="space-y-2">
              {[...chain.entries]
                .sort((a, b) => a.sortOrder - b.sortOrder)
                .map((entry, i) => (
                  <div key={i}>
                    {i > 0 && (
                      <div className="flex justify-center py-1">
                        <ArrowDown className="w-4 h-4 text-text-tertiary" />
                      </div>
                    )}
                    <div className="flex items-center gap-3 p-3 rounded-lg bg-surface-raised border border-border">
                      <span className="text-xs font-mono text-text-tertiary w-6 text-center">{i + 1}</span>
                      <div className="flex-1 min-w-0">
                        <p className="font-medium text-text-primary truncate">
                          {entry.label || entry.workflowName || entry.workflowId}
                        </p>
                        {entry.projectName && (
                          <p className="text-xs text-text-tertiary">{entry.projectName}</p>
                        )}
                      </div>
                      {entry.stopOnFailure && (
                        <Badge variant="warning" dot>Stop</Badge>
                      )}
                    </div>
                  </div>
                ))}
            </div>
          )}
        </div>
      </Card>

      {/* Recent Runs */}
      <Card>
        <div className="p-4">
          <h3 className="text-sm font-semibold text-text-secondary uppercase tracking-wide mb-3">
            {t.chains.runs}
          </h3>
          {(!runs || runs.length === 0) ? (
            <p className="text-text-tertiary text-sm">{t.chains.noRuns}</p>
          ) : (
            <div className="space-y-2">
              {runs.slice(0, 10).map(run => (
                <div
                  key={run.id}
                  className="flex items-center justify-between p-2 rounded-lg hover:bg-surface-raised cursor-pointer transition-colors"
                  onClick={() => navigate(`/chains/${chain.id}/run/${run.id}`)}
                >
                  <div className="flex items-center gap-3">
                    <StatusBadge status={run.status} />
                    <span className="text-sm text-text-secondary">
                      {(() => {
                        const completed = run.entryRuns.filter(e => e.status === 'COMPLETED').length;
                        const failed = run.entryRuns.filter(e => e.status === 'FAILED').length;
                        const cancelled = run.entryRuns.filter(e => e.status === 'CANCELLED').length;
                        return (
                          <>
                            {completed > 0 && <span className="text-success">{completed} ✓</span>}
                            {completed > 0 && (failed > 0 || cancelled > 0) && ' '}
                            {failed > 0 && <span className="text-danger">{failed} ✗</span>}
                            {failed > 0 && cancelled > 0 && ' '}
                            {cancelled > 0 && <span className="text-warning">{cancelled} ⊘</span>}
                            {completed === 0 && failed === 0 && cancelled === 0 && '0'}
                            {' / '}{run.entryRuns.length} {t.chains.entries}
                          </>
                        );
                      })()}
                    </span>
                  </div>
                  <div className="flex items-center gap-3">
                    <span className="text-xs text-text-tertiary">{formatDuration(run.durationMs)}</span>
                    {run.startedAt && (
                      <span className="text-xs text-text-tertiary">{formatRelativeTime(run.startedAt)}</span>
                    )}
                    <ChevronRight className="w-4 h-4 text-text-tertiary" />
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </Card>
    </div>
  );
}

/* ── Chain Form Modal ────────────────────────────────── */

interface ChainFormModalProps {
  title: string;
  chain?: WorkflowChain;
  workflows: Array<{ id: string; name: string; projectName: string }>;
  onSave: (data: CreateChainRequest | UpdateChainRequest) => void;
  onClose: () => void;
  saving: boolean;
}

function ChainFormModal({ title, chain, workflows, onSave, onClose, saving }: ChainFormModalProps) {
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
            placeholder="My Chain"
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
