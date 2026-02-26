import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  ArrowLeft, Play, Plus, Trash2, Pencil, Zap, Clock, Wand2, FileText, Bot, Copy, Download,
} from 'lucide-react';
import { Card, CardHeader, Button, Input, Select, Modal, Spinner } from '../components/ui';
import { StatusBadge } from '../components/shared/StatusBadge';
import { ErrorState } from '../components/shared/ErrorState';
import { MonacoEditor } from '../components/shared/MonacoEditor';
import {
  useWorkflow, useWorkflows as useWorkflowsList, useWorkflowRuns, useUpdateWorkflow,
  useDeleteWorkflow, useExecuteWorkflow, useOptimizeWorkflow, useCreateWorkflow,
  exportWorkflow,
} from '../hooks/useWorkflows';
import { useSkills, useActivateSkills, useDeactivateSkill } from '../hooks/useSkills';
import { useProjects } from '../hooks/useProjects';
import { SortableBlockList } from '../components/workflow/SortableBlockList';
import { ExecuteDialog } from '../components/workflow/ExecuteDialog';
import { WorkflowMemoryPanel } from '../components/workflow/WorkflowMemoryPanel';
import { useT } from '../i18n';
import { formatDuration } from '../utils/formatTime';
import { extractWorkflowVariables } from '../utils/extractWorkflowVariables';
import type { BlockDefinition, BlockType, WorkflowRun } from '../types';

const BLOCK_TYPES: BlockType[] = ['simple', 'shell', 'retry', 'foreach', 'while', 'conditional', 'skill-capability', 'approval', 'agentic', 'sub-workflow'];

const MODEL_OPTIONS = [
  { value: '', label: '' }, // will be replaced by t.workflows.modelDefault
  { value: 'claude-sonnet-4-6', label: 'Claude Sonnet 4.6 (fast)' },
  { value: 'claude-opus-4-6', label: 'Claude Opus 4.6 (powerful)' },
  { value: 'claude-haiku-4-5', label: 'Claude Haiku 4.5 (fastest)' },
];

export function WorkflowDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const t = useT();
  const { data: workflow, isLoading, error, refetch } = useWorkflow(id!);
  const { data: runs } = useWorkflowRuns(id!);
  const { data: allSkills } = useSkills();
  const { data: allWorkflows } = useWorkflowsList();
  const { data: projects } = useProjects();
  const updateWorkflow = useUpdateWorkflow();
  const deleteWorkflow = useDeleteWorkflow();
  const executeWorkflow = useExecuteWorkflow();
  const optimizeWorkflow = useOptimizeWorkflow();
  const createWorkflow = useCreateWorkflow();
  const activateSkills = useActivateSkills();
  const deactivateSkill = useDeactivateSkill();

  const [showEdit, setShowEdit] = useState(false);
  const [showAddBlock, setShowAddBlock] = useState(false);
  const [showEditBlock, setShowEditBlock] = useState(false);
  const [editBlockIndex, setEditBlockIndex] = useState<number>(-1);
  const [editBlock, setEditBlock] = useState<{ type: BlockType; config: string }>({
    type: 'simple',
    config: '{}',
  });
  const [showAddSkill, setShowAddSkill] = useState(false);
  const [showDelete, setShowDelete] = useState(false);
  const [showEditInstructions, setShowEditInstructions] = useState(false);
  const [showOptimizeResult, setShowOptimizeResult] = useState(false);
  const [optimizeResult, setOptimizeResult] = useState<Record<string, unknown> | null>(null);
  const [editInstructions, setEditInstructions] = useState('');
  const [editForm, setEditForm] = useState({ name: '', description: '', icon: '' });
  const [newBlock, setNewBlock] = useState<{ type: BlockType; config: string }>({
    type: 'simple',
    config: JSON.stringify({ command: '' }, null, 2),
  });
  const [selectedSkillId, setSelectedSkillId] = useState('');
  const [showExecuteDialog, setShowExecuteDialog] = useState(false);
  const [showClone, setShowClone] = useState(false);
  const [cloneForm, setCloneForm] = useState({ projectId: '', name: '' });

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <Spinner text={t.common.loading} />
      </div>
    );
  }

  if (error || !workflow) {
    return <ErrorState message={error?.message} onRetry={() => refetch()} />;
  }

  const openEdit = () => {
    setEditForm({ name: workflow.name, description: workflow.description, icon: workflow.icon });
    setShowEdit(true);
  };

  const handleUpdate = () => {
    updateWorkflow.mutate(
      { id: workflow.id, data: editForm },
      { onSuccess: () => setShowEdit(false) }
    );
  };

  const handleDelete = () => {
    deleteWorkflow.mutate(workflow.id, {
      onSuccess: () => navigate('/workflows'),
    });
  };

  const workflowVariables = extractWorkflowVariables(workflow.blocks);

  const handleExecute = () => {
    if (workflowVariables.length > 0) {
      setShowExecuteDialog(true);
    } else {
      executeWorkflow.mutate(
        { id: workflow.id },
        { onSuccess: (run) => navigate(`/workflows/${workflow.id}/run/${run.id}`) }
      );
    }
  };

  const handleExecuteWithContext = (context: Record<string, string>) => {
    executeWorkflow.mutate(
      { id: workflow.id, context },
      {
        onSuccess: (run) => {
          setShowExecuteDialog(false);
          navigate(`/workflows/${workflow.id}/run/${run.id}`);
        },
      }
    );
  };

  const handleAddBlock = () => {
    try {
      const config = JSON.parse(newBlock.config);
      const block: BlockDefinition = { type: newBlock.type, config };
      updateWorkflow.mutate(
        { id: workflow.id, data: { blocks: [...workflow.blocks, block] } },
        {
          onSuccess: () => {
            setShowAddBlock(false);
            setNewBlock({ type: 'simple', config: JSON.stringify({ command: '' }, null, 2) });
          },
        }
      );
    } catch {
      // Invalid JSON - Monaco will show the error
    }
  };

  const handleRemoveBlock = (index: number) => {
    const blocks = workflow.blocks.filter((_, i) => i !== index);
    updateWorkflow.mutate({ id: workflow.id, data: { blocks } });
  };

  const handleOpenEditBlock = (index: number) => {
    const block = workflow.blocks[index];
    if (!block) return;
    setEditBlockIndex(index);
    setEditBlock({ type: block.type, config: JSON.stringify(block.config, null, 2) });
    setShowEditBlock(true);
  };

  const handleSaveBlock = () => {
    try {
      const config = JSON.parse(editBlock.config);
      const blocks = [...workflow.blocks];
      blocks[editBlockIndex] = { type: editBlock.type, config };
      updateWorkflow.mutate(
        { id: workflow.id, data: { blocks } },
        { onSuccess: () => setShowEditBlock(false) }
      );
    } catch {
      // Invalid JSON - Monaco will show the error
    }
  };

  const handleActivateSkill = () => {
    if (!selectedSkillId) return;
    activateSkills.mutate(
      { workflowId: workflow.id, skillIds: [...workflow.activatedSkillIds, selectedSkillId] },
      {
        onSuccess: () => {
          setShowAddSkill(false);
          setSelectedSkillId('');
        },
      }
    );
  };

  const availableSkills = allSkills?.filter(
    (s) => !workflow.activatedSkillIds.includes(s.id)
  ) ?? [];

  return (
    <div className="space-y-6 animate-fade-in">
      {/* Header */}
      <div className="flex items-center gap-4">
        <button
          onClick={() => navigate('/workflows')}
          className="p-2 rounded-lg hover:bg-surface-raised text-text-secondary hover:text-text transition-colors"
          aria-label={t.common.back}
        >
          <ArrowLeft className="w-5 h-5" />
        </button>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-3">
            <span className="text-2xl">{workflow.icon || '📋'}</span>
            <h1 className="text-2xl font-bold text-text truncate">{workflow.name}</h1>
          </div>
          {workflow.description && (
            <p className="text-sm text-text-secondary mt-0.5">{workflow.description}</p>
          )}
        </div>
        <div className="flex items-center gap-2">
          <Button
            variant="secondary"
            size="sm"
            icon={<Wand2 className="w-4 h-4" />}
            onClick={() => {
              optimizeWorkflow.mutate(workflow.id, {
                onSuccess: (data) => {
                  setOptimizeResult(data);
                  setShowOptimizeResult(true);
                },
              });
            }}
            loading={optimizeWorkflow.isPending}
          >
            {optimizeWorkflow.isPending ? t.workflows.optimizing : t.workflows.optimize}
          </Button>
          <Button
            icon={<Play className="w-4 h-4" />}
            onClick={handleExecute}
            loading={executeWorkflow.isPending}
          >
            {t.workflows.execute}
          </Button>
          <Button variant="ghost" size="sm" icon={<Pencil className="w-4 h-4" />} onClick={openEdit} aria-label={t.common.edit} />
          <Button
            variant="ghost"
            size="sm"
            icon={<Copy className="w-4 h-4" />}
            onClick={() => {
              setCloneForm({ projectId: workflow.projectId, name: `${workflow.name} (Copy)` });
              setShowClone(true);
            }}
            aria-label={t.workflows.clone}
          />
          <Button
            variant="ghost"
            size="sm"
            icon={<Download className="w-4 h-4" />}
            onClick={() => {
              const safeName = workflow.name.replace(/[^a-zA-Z0-9_-]/g, '_');
              exportWorkflow(workflow.id, `${safeName}.json`);
            }}
            aria-label={t.workflows.exportWorkflow}
          />
          <Button variant="ghost" size="sm" icon={<Trash2 className="w-4 h-4" />} onClick={() => setShowDelete(true)} aria-label={t.common.delete} />
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Blocks - 2/3 */}
        <div className="lg:col-span-2 space-y-4">
          <Card padding={false}>
            <div className="p-5 pb-0">
              <CardHeader
                title={`${t.workflows.blocks} (${workflow.blocks.length})`}
                action={
                  <Button
                    size="sm"
                    variant="secondary"
                    icon={<Plus className="w-4 h-4" />}
                    onClick={() => setShowAddBlock(true)}
                  >
                    {t.workflows.addBlock}
                  </Button>
                }
              />
            </div>
            {workflow.blocks.length === 0 ? (
              <div className="px-5 py-12 text-center text-sm text-text-tertiary">
                {t.workflows.noBlocks}
              </div>
            ) : (
              <SortableBlockList
                workflowId={workflow.id}
                blocks={workflow.blocks}
                onEditBlock={handleOpenEditBlock}
                onRemoveBlock={handleRemoveBlock}
              />
            )}
          </Card>
        </div>

        {/* Sidebar - 1/3 */}
        <div className="space-y-4">
          {/* Agent Instructions */}
          <Card>
            <CardHeader
              title={t.workflows.instructions}
              action={
                <Button
                  size="sm"
                  variant="ghost"
                  icon={<Pencil className="w-3.5 h-3.5" />}
                  onClick={() => {
                    setEditInstructions(workflow.instructions || '');
                    setShowEditInstructions(true);
                  }}
                >
                  {t.common.edit}
                </Button>
              }
            />
            {workflow.instructions ? (
              <pre className="text-xs text-text-secondary whitespace-pre-wrap font-mono bg-surface rounded-lg p-3 max-h-40 overflow-y-auto">
                {workflow.instructions}
              </pre>
            ) : (
              <p className="text-sm text-text-tertiary flex items-center gap-2">
                <FileText className="w-4 h-4" />
                {t.workflows.noInstructions}
              </p>
            )}
          </Card>

          {/* Workflow Memory */}
          <WorkflowMemoryPanel workflowId={workflow.id} runs={runs} />

          {/* Model Selection */}
          <Card>
            <CardHeader title={t.workflows.model} />
            <Select
              value={workflow.model || ''}
              onChange={(e) => {
                updateWorkflow.mutate({ id: workflow.id, data: { model: e.target.value } });
              }}
              options={MODEL_OPTIONS.map((opt) => ({
                value: opt.value,
                label: opt.value === '' ? t.workflows.modelDefault : opt.label,
              }))}
            />
            <p className="text-xs text-text-tertiary mt-2 flex items-center gap-1.5">
              <Bot className="w-3.5 h-3.5" />
              {t.workflows.modelHint}
            </p>
          </Card>

          {/* Default Context */}
          {workflowVariables.length > 0 && (
            <DefaultContextEditor
              workflowId={workflow.id}
              variables={workflowVariables}
              defaultContext={workflow.defaultContext ?? {}}
            />
          )}

          {/* Activated Skills */}
          <Card>
            <CardHeader
              title={t.workflows.activatedSkills}
              action={
                <Button
                  size="sm"
                  variant="ghost"
                  icon={<Plus className="w-3.5 h-3.5" />}
                  onClick={() => setShowAddSkill(true)}
                >
                  {t.workflows.activateSkill}
                </Button>
              }
            />
            {workflow.activatedSkillNames.length === 0 ? (
              <p className="text-sm text-text-tertiary">{t.workflows.noSkillsActivated}</p>
            ) : (
              <div className="space-y-2">
                {workflow.activatedSkillIds.map((skillId, idx) => (
                  <div key={skillId} className="flex items-center justify-between group">
                    <div className="flex items-center gap-2">
                      <Zap className="w-4 h-4 text-accent" />
                      <span className="text-sm text-text">{workflow.activatedSkillNames[idx]}</span>
                    </div>
                    <button
                      onClick={() => deactivateSkill.mutate({ workflowId: workflow.id, skillId })}
                      className="p-1 rounded text-text-tertiary hover:text-danger sm:opacity-0 sm:group-hover:opacity-100 transition-all"
                      aria-label={t.skills.deactivate}
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </button>
                  </div>
                ))}
              </div>
            )}
          </Card>

          {/* Recent Runs */}
          <Card padding={false}>
            <div className="p-5 pb-0">
              <CardHeader title={`${t.dashboard.recentRuns} (${runs?.length ?? 0})`} />
            </div>
            {!runs?.length ? (
              <div className="px-5 py-8 text-center text-sm text-text-tertiary">{t.workflows.noRuns}</div>
            ) : (
              <div className="divide-y divide-border">
                {runs.slice(0, 5).map((run: WorkflowRun) => (
                  <div
                    key={run.id}
                    className="flex items-center justify-between px-5 py-3 hover:bg-surface transition-colors cursor-pointer"
                    onClick={() => navigate(`/workflows/${workflow.id}/run/${run.id}`)}
                  >
                    <StatusBadge status={run.status} />
                    <div className="flex items-center gap-2 text-xs text-text-tertiary">
                      <Clock className="w-3 h-3" />
                      {formatDuration(run.durationMs)}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </Card>

          {/* Workflow Info */}
          <Card>
            <CardHeader title={t.common.info} />
            <div className="space-y-2 text-sm">
              <div className="flex justify-between">
                <span className="text-text-secondary">{t.common.project}</span>
                <span className="text-text">{workflow.projectName}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-text-secondary">{t.common.version}</span>
                <span className="text-text font-mono">{workflow.version}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-text-secondary">{t.common.created}</span>
                <span className="text-text">{new Date(workflow.createdAt).toLocaleDateString()}</span>
              </div>
            </div>
          </Card>
        </div>
      </div>

      {/* Edit modal */}
      <Modal
        open={showEdit}
        onClose={() => setShowEdit(false)}
        title={t.workflows.editWorkflow}
        footer={
          <>
            <Button variant="secondary" onClick={() => setShowEdit(false)}>{t.common.cancel}</Button>
            <Button onClick={handleUpdate} loading={updateWorkflow.isPending}>{t.common.save}</Button>
          </>
        }
      >
        <div className="space-y-4">
          <Input
            label={t.common.name}
            value={editForm.name}
            onChange={(e) => setEditForm({ ...editForm, name: e.target.value })}
          />
          <Input
            label={t.common.description}
            value={editForm.description}
            onChange={(e) => setEditForm({ ...editForm, description: e.target.value })}
          />
          <Input
            label={t.workflows.iconLabel}
            value={editForm.icon}
            onChange={(e) => setEditForm({ ...editForm, icon: e.target.value })}
            placeholder="🚀"
          />
        </div>
      </Modal>

      {/* Add block modal */}
      <Modal
        open={showAddBlock}
        onClose={() => setShowAddBlock(false)}
        title={t.workflows.addBlock}
        size="lg"
        footer={
          <>
            <Button variant="secondary" onClick={() => setShowAddBlock(false)}>{t.common.cancel}</Button>
            <Button onClick={handleAddBlock} loading={updateWorkflow.isPending}>
              {t.workflows.addBlock}
            </Button>
          </>
        }
      >
        <div className="space-y-4">
          <Select
            label={t.workflows.blockType}
            value={newBlock.type}
            onChange={(e) => {
              const type = e.target.value as BlockType;
              const defaultConfig: Record<BlockType, object> = {
                simple: { command: '' },
                shell: { command: '', timeout_seconds: 300 },
                retry: { command: '', max_retries: 3, delay: 5 },
                foreach: { items_command: '', variable_name: 'item', command: '' },
                while: { condition_command: '', command: '', max_iterations: 10 },
                conditional: { condition: '', then_command: '', else_command: '' },
                'skill-capability': { capability: '', input: '{{ previous_output }}' },
                approval: { question: '', options: ['Yes', 'Yes always', 'No'], mode: 'ask', auto_answer: null, on_no: 'continue', variable_name: 'approval_answer', timeout_minutes: 30 },
                agentic: { goal: '', success_criteria: '', max_iterations: 10, iteration_timeout_seconds: 300, total_timeout_seconds: 1800, max_total_tokens: 200000, max_repeated_actions: 3, require_approval: false },
                'sub-workflow': { workflow_id: '' },
              };
              setNewBlock({ type, config: JSON.stringify(defaultConfig[type], null, 2) });
            }}
            options={BLOCK_TYPES.map((type) => ({
              value: type,
              label: t.workflows.blockTypes[type],
            }))}
          />
          {newBlock.type === 'sub-workflow' && (
            <Select
              label={t.workflows.selectSubWorkflow}
              value={(() => { try { return JSON.parse(newBlock.config).workflow_id || ''; } catch { return ''; } })()}
              onChange={(e) => setNewBlock({ ...newBlock, config: JSON.stringify({ workflow_id: e.target.value }, null, 2) })}
              options={[
                { value: '', label: t.common.selectWorkflow },
                ...(allWorkflows?.filter((w) => w.id !== id).map((w) => ({ value: w.id, label: `${w.icon || ''} ${w.name}`.trim() })) ?? []),
              ]}
            />
          )}
          <div>
            <label className="block text-sm font-medium text-text mb-1.5">{t.workflows.blockConfig}</label>
            <MonacoEditor
              value={newBlock.config}
              onChange={(val) => setNewBlock({ ...newBlock, config: val })}
              language="json"
              height="200px"
            />
          </div>
        </div>
      </Modal>

      {/* Edit block modal */}
      <Modal
        open={showEditBlock}
        onClose={() => setShowEditBlock(false)}
        title={`${t.workflows.editBlock} #${editBlockIndex + 1}`}
        size="lg"
        footer={
          <>
            <Button variant="secondary" onClick={() => setShowEditBlock(false)}>{t.common.cancel}</Button>
            <Button onClick={handleSaveBlock} loading={updateWorkflow.isPending}>
              {t.common.save}
            </Button>
          </>
        }
      >
        <div className="space-y-4">
          <Select
            label={t.workflows.blockType}
            value={editBlock.type}
            onChange={(e) => {
              const type = e.target.value as BlockType;
              setEditBlock({ ...editBlock, type });
            }}
            options={BLOCK_TYPES.map((type) => ({
              value: type,
              label: t.workflows.blockTypes[type],
            }))}
          />
          {editBlock.type === 'sub-workflow' && (
            <Select
              label={t.workflows.selectSubWorkflow}
              value={(() => { try { return JSON.parse(editBlock.config).workflow_id || ''; } catch { return ''; } })()}
              onChange={(e) => setEditBlock({ ...editBlock, config: JSON.stringify({ workflow_id: e.target.value }, null, 2) })}
              options={[
                { value: '', label: t.common.selectWorkflow },
                ...(allWorkflows?.filter((w) => w.id !== id).map((w) => ({ value: w.id, label: `${w.icon || ''} ${w.name}`.trim() })) ?? []),
              ]}
            />
          )}
          <div>
            <label className="block text-sm font-medium text-text mb-1.5">{t.workflows.blockConfig}</label>
            <MonacoEditor
              value={editBlock.config}
              onChange={(val) => setEditBlock({ ...editBlock, config: val })}
              language="json"
              height="250px"
            />
          </div>
        </div>
      </Modal>

      {/* Activate skill modal */}
      <Modal
        open={showAddSkill}
        onClose={() => setShowAddSkill(false)}
        title={t.workflows.activateSkill}
        size="sm"
        footer={
          <>
            <Button variant="secondary" onClick={() => setShowAddSkill(false)}>{t.common.cancel}</Button>
            <Button onClick={handleActivateSkill} disabled={!selectedSkillId} loading={activateSkills.isPending}>
              {t.skills.activate}
            </Button>
          </>
        }
      >
        <Select
          label={t.skills.title}
          value={selectedSkillId}
          onChange={(e) => setSelectedSkillId(e.target.value)}
          options={[
            { value: '', label: t.common.selectSkill },
            ...availableSkills.map((s) => ({ value: s.id, label: s.name })),
          ]}
        />
      </Modal>

      {/* Delete confirmation */}
      <Modal
        open={showDelete}
        onClose={() => setShowDelete(false)}
        title={`${t.common.delete} "${workflow.name}"?`}
        size="sm"
        footer={
          <>
            <Button variant="secondary" onClick={() => setShowDelete(false)}>{t.common.cancel}</Button>
            <Button variant="danger" onClick={handleDelete} loading={deleteWorkflow.isPending}>
              {t.common.delete}
            </Button>
          </>
        }
      >
        <p className="text-sm text-text-secondary">
          {t.common.deleteConfirm} {t.workflows.deleteConfirm}
        </p>
      </Modal>

      {/* Clone modal */}
      <Modal
        open={showClone}
        onClose={() => setShowClone(false)}
        title={t.workflows.cloneWorkflow}
        size="sm"
        footer={
          <>
            <Button variant="secondary" onClick={() => setShowClone(false)}>{t.common.cancel}</Button>
            <Button
              onClick={() => {
                createWorkflow.mutate(
                  {
                    projectId: cloneForm.projectId,
                    name: cloneForm.name,
                    description: workflow.description,
                    icon: workflow.icon,
                    blocks: workflow.blocks,
                    activatedSkillIds: workflow.activatedSkillIds,
                    instructions: workflow.instructions ?? undefined,
                    model: workflow.model ?? undefined,
                  },
                  {
                    onSuccess: (newWorkflow) => {
                      setShowClone(false);
                      navigate(`/workflows/${newWorkflow.id}`);
                    },
                  }
                );
              }}
              loading={createWorkflow.isPending}
              disabled={!cloneForm.projectId || !cloneForm.name.trim()}
            >
              {t.common.create}
            </Button>
          </>
        }
      >
        <div className="space-y-4">
          <Select
            label={t.workflows.cloneProject}
            value={cloneForm.projectId}
            onChange={(e) => setCloneForm({ ...cloneForm, projectId: e.target.value })}
            options={[
              { value: '', label: t.common.selectProject },
              ...(projects?.map((p) => ({ value: p.id, label: p.name })) ?? []),
            ]}
          />
          <Input
            label={t.workflows.cloneName}
            value={cloneForm.name}
            onChange={(e) => setCloneForm({ ...cloneForm, name: e.target.value })}
          />
        </div>
      </Modal>

      {/* Execute with variables dialog */}
      <ExecuteDialog
        open={showExecuteDialog}
        onClose={() => setShowExecuteDialog(false)}
        onExecute={handleExecuteWithContext}
        variables={workflowVariables}
        defaultValues={workflow.defaultContext}
        loading={executeWorkflow.isPending}
      />

      {/* Edit instructions modal */}
      <Modal
        open={showEditInstructions}
        onClose={() => setShowEditInstructions(false)}
        title={t.workflows.editInstructions}
        size="lg"
        footer={
          <>
            <Button variant="secondary" onClick={() => setShowEditInstructions(false)}>{t.common.cancel}</Button>
            <Button
              onClick={() => {
                updateWorkflow.mutate(
                  { id: workflow.id, data: { instructions: editInstructions } },
                  { onSuccess: () => setShowEditInstructions(false) }
                );
              }}
              loading={updateWorkflow.isPending}
            >
              {t.common.save}
            </Button>
          </>
        }
      >
        <div className="space-y-3">
          <p className="text-sm text-text-secondary">{t.workflows.instructionsHint}</p>
          <MonacoEditor
            value={editInstructions}
            onChange={setEditInstructions}
            language="markdown"
            height="300px"
          />
        </div>
      </Modal>

      {/* Optimize result modal */}
      <Modal
        open={showOptimizeResult}
        onClose={() => setShowOptimizeResult(false)}
        title={t.workflows.optimizeResult}
        size="lg"
        footer={
          <>
            <Button variant="secondary" onClick={() => setShowOptimizeResult(false)}>{t.common.cancel}</Button>
            <Button
              onClick={() => {
                if (!optimizeResult) return;
                const data: Record<string, unknown> = {};
                if (optimizeResult.suggestedName) data.name = optimizeResult.suggestedName;
                if (optimizeResult.suggestedDescription) data.description = optimizeResult.suggestedDescription;
                if (optimizeResult.suggestedInstructions) data.instructions = optimizeResult.suggestedInstructions;
                if (Array.isArray(optimizeResult.blocks) && optimizeResult.blocks.length > 0) {
                  data.blocks = optimizeResult.blocks;
                }
                updateWorkflow.mutate(
                  { id: workflow.id, data: data as import('../types').UpdateWorkflowRequest },
                  { onSuccess: () => setShowOptimizeResult(false) }
                );
              }}
              loading={updateWorkflow.isPending}
            >
              {t.workflows.optimizeApply}
            </Button>
          </>
        }
      >
        {optimizeResult && (
          <div className="space-y-4">
            {optimizeResult.error ? (
              <p className="text-sm text-danger">{String(optimizeResult.message)}</p>
            ) : (
              <>
                <div>
                  <h4 className="text-sm font-medium text-text mb-2">{t.workflows.optimizeChanges}</h4>
                  {Array.isArray(optimizeResult.changes) && optimizeResult.changes.length > 0 ? (
                    <ul className="space-y-2">
                      {(optimizeResult.changes as Array<{ type: string; description: string }>).map((change, i) => (
                        <li key={i} className="flex items-start gap-2 text-sm">
                          <span className="shrink-0 px-1.5 py-0.5 rounded text-[10px] font-medium bg-accent/10 text-accent">
                            {change.type}
                          </span>
                          <span className="text-text-secondary">{change.description}</span>
                        </li>
                      ))}
                    </ul>
                  ) : (
                    <p className="text-sm text-text-tertiary">{t.workflows.optimizeNoChanges}</p>
                  )}
                </div>
                {optimizeResult.suggestedInstructions && (
                  <div>
                    <h4 className="text-sm font-medium text-text mb-1">{t.workflows.instructions}</h4>
                    <pre className="text-xs text-text-secondary whitespace-pre-wrap font-mono bg-surface rounded-lg p-3 max-h-40 overflow-y-auto">
                      {String(optimizeResult.suggestedInstructions)}
                    </pre>
                  </div>
                )}
                {Array.isArray(optimizeResult.blocks) && optimizeResult.blocks.length > 0 && (
                  <div>
                    <h4 className="text-sm font-medium text-text mb-1">{t.workflows.blocks} ({(optimizeResult.blocks as unknown[]).length})</h4>
                    <pre className="text-xs text-text-secondary font-mono bg-surface rounded-lg p-3 max-h-60 overflow-y-auto">
                      {JSON.stringify(optimizeResult.blocks, null, 2)}
                    </pre>
                  </div>
                )}
              </>
            )}
          </div>
        )}
      </Modal>
    </div>
  );
}

function DefaultContextEditor({
  workflowId,
  variables,
  defaultContext,
}: {
  workflowId: string;
  variables: string[];
  defaultContext: Record<string, string>;
}) {
  const t = useT();
  const updateWorkflow = useUpdateWorkflow();
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(variables.map((v) => [v, defaultContext[v] ?? '']))
  );

  // Sync if defaultContext changes externally
  const contextKey = JSON.stringify(defaultContext);
  const [prevContextKey, setPrevContextKey] = useState(contextKey);
  if (contextKey !== prevContextKey) {
    setPrevContextKey(contextKey);
    setValues(Object.fromEntries(variables.map((v) => [v, defaultContext[v] ?? ''])));
  }

  const hasChanges = variables.some((v) => (values[v] ?? '') !== (defaultContext[v] ?? ''));

  const handleSave = () => {
    // Only save non-empty values
    const ctx: Record<string, string> = {};
    for (const v of variables) {
      if (values[v]?.trim()) {
        ctx[v] = values[v];
      }
    }
    updateWorkflow.mutate({ id: workflowId, data: { defaultContext: ctx } });
  };

  return (
    <Card>
      <CardHeader
        title={t.workflows.defaultValues}
        action={
          hasChanges ? (
            <Button
              size="sm"
              variant="secondary"
              onClick={handleSave}
              loading={updateWorkflow.isPending}
            >
              {t.common.save}
            </Button>
          ) : undefined
        }
      />
      <div className="space-y-3">
        <p className="text-xs text-text-tertiary">{t.workflows.defaultValuesHint}</p>
        {variables.map((variable) => (
          <Input
            key={variable}
            label={variable}
            value={values[variable] ?? ''}
            onChange={(e) => setValues({ ...values, [variable]: e.target.value })}
            placeholder={`${t.workflows.enterValue} ${variable}`}
          />
        ))}
      </div>
    </Card>
  );
}
