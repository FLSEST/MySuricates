import { useState, useMemo, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Link2, Plus, Play, Layers } from 'lucide-react';
import { Card, Button, Input, Modal, Spinner } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { ExecuteDialog } from '../components/workflow/ExecuteDialog';
import { ChainDetail } from '../components/chains/ChainDetail';
import { ChainFormModal } from '../components/chains/ChainFormModal';
import { ChainTemplateModal } from '../components/chains/ChainTemplateModal';
import { useChains, useCreateChain, useUpdateChain, useDeleteChain, useExecuteChain } from '../hooks/useChains';
import { useWorkflows } from '../hooks/useWorkflows';
import { useT } from '../i18n';
import { extractWorkflowVariables } from '../utils/extractWorkflowVariables';
import { readContextSchema } from '../utils/contextSchema';
import type { WorkflowChain, CreateChainRequest } from '../types';


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
    if (!workflows) return {
      variables: [] as string[],
      defaults: {} as Record<string, string>,
      optional: [] as string[],
      descriptions: {} as Record<string, string>,
      placeholders: {} as Record<string, string>,
    };
    const allVars = new Set<string>();
    const defaults: Record<string, string> = {};
    const requiredVars = new Set<string>();
    const optionalVars = new Set<string>();
    const descriptions: Record<string, string> = {};
    const placeholders: Record<string, string> = {};
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
      const schema = readContextSchema(wf);
      for (const v of schema.optional) optionalVars.add(v);
      for (const [k, v] of Object.entries(schema.descriptions)) {
        if (!(k in descriptions)) descriptions[k] = v;
      }
      for (const [k, v] of Object.entries(schema.placeholders)) {
        if (!(k in placeholders)) placeholders[k] = v;
      }
      // A variable that is required in any workflow within the chain is required overall.
      const schemaMeta = wf.metadata?.contextSchema as Record<string, { required?: boolean }> | undefined;
      if (schemaMeta) {
        for (const [k, spec] of Object.entries(schemaMeta)) {
          if (spec?.required !== false) requiredVars.add(k);
        }
      }
    }
    // Drop variables from optional set if any chain workflow marks them required.
    const optional = Array.from(optionalVars).filter(v => !requiredVars.has(v));
    return { variables: Array.from(allVars), defaults, optional, descriptions, placeholders };
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
        const { variables, defaults, optional, descriptions, placeholders } = getChainVariables(executeTarget);
        return (
          <ExecuteDialog
            open={true}
            onClose={() => setExecuteTarget(null)}
            onExecute={handleExecuteWithContext}
            variables={variables}
            defaultValues={defaults}
            optionalVariables={optional}
            descriptions={descriptions}
            placeholders={placeholders}
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
