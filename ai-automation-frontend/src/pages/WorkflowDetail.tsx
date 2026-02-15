import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  ArrowLeft, Play, Plus, Trash2, Pencil, Zap, Clock, GripVertical,
} from 'lucide-react';
import { Card, CardHeader, Button, Input, Select, Modal, Spinner, Badge } from '../components/ui';
import { StatusBadge } from '../components/shared/StatusBadge';
import { ErrorState } from '../components/shared/ErrorState';
import { MonacoEditor } from '../components/shared/MonacoEditor';
import {
  useWorkflow, useWorkflowRuns, useUpdateWorkflow,
  useDeleteWorkflow, useExecuteWorkflow,
} from '../hooks/useWorkflows';
import { useSkills, useActivateSkills, useDeactivateSkill } from '../hooks/useSkills';
import { useSettingsStore } from '../stores/settingsStore';
import { useT } from '../i18n';
import type { BlockDefinition, BlockType, WorkflowRun } from '../types';

const BLOCK_TYPES: BlockType[] = ['simple', 'retry', 'foreach', 'while', 'conditional', 'skill-capability'];

function formatDuration(ms: number | null): string {
  if (!ms) return '-';
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  return `${(ms / 60_000).toFixed(1)}m`;
}

export function WorkflowDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const t = useT();
  const advancedMode = useSettingsStore((s) => s.advancedMode);

  const { data: workflow, isLoading, error, refetch } = useWorkflow(id!);
  const { data: runs } = useWorkflowRuns(id!);
  const { data: allSkills } = useSkills();
  const updateWorkflow = useUpdateWorkflow();
  const deleteWorkflow = useDeleteWorkflow();
  const executeWorkflow = useExecuteWorkflow();
  const activateSkills = useActivateSkills();
  const deactivateSkill = useDeactivateSkill();

  const [showEdit, setShowEdit] = useState(false);
  const [showAddBlock, setShowAddBlock] = useState(false);
  const [showAddSkill, setShowAddSkill] = useState(false);
  const [showDelete, setShowDelete] = useState(false);
  const [editForm, setEditForm] = useState({ name: '', description: '', icon: '' });
  const [newBlock, setNewBlock] = useState<{ type: BlockType; config: string }>({
    type: 'simple',
    config: JSON.stringify({ command: '' }, null, 2),
  });
  const [selectedSkillId, setSelectedSkillId] = useState('');

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

  const handleExecute = () => {
    executeWorkflow.mutate(
      { id: workflow.id },
      { onSuccess: (run) => navigate(`/workflows/${workflow.id}/run/${run.id}`) }
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
            icon={<Play className="w-4 h-4" />}
            onClick={handleExecute}
            loading={executeWorkflow.isPending}
          >
            {t.workflows.execute}
          </Button>
          <Button variant="ghost" size="sm" icon={<Pencil className="w-4 h-4" />} onClick={openEdit} aria-label={t.common.edit} />
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
              <div className="divide-y divide-border">
                {workflow.blocks.map((block, idx) => (
                  <div key={idx} className="flex items-start gap-3 px-5 py-4 group">
                    <div className="pt-1 text-text-tertiary">
                      <GripVertical className="w-4 h-4" />
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 mb-1">
                        <span className="text-xs font-mono px-2 py-0.5 rounded bg-surface text-text-secondary">
                          #{idx + 1}
                        </span>
                        <Badge variant="accent">{t.workflows.blockTypes[block.type]}</Badge>
                      </div>
                      {advancedMode ? (
                        <pre className="text-xs text-text-secondary font-mono bg-surface rounded-lg p-3 mt-2 overflow-x-auto">
                          {JSON.stringify(block.config, null, 2)}
                        </pre>
                      ) : (
                        <p className="text-sm text-text-secondary truncate">
                          {'command' in block.config
                            ? String(block.config.command)
                            : 'capability' in block.config
                              ? String(block.config.capability)
                              : JSON.stringify(block.config)}
                        </p>
                      )}
                    </div>
                    <button
                      onClick={() => handleRemoveBlock(idx)}
                      className="p-1.5 rounded-lg text-text-tertiary hover:text-danger opacity-0 group-hover:opacity-100 transition-all"
                      aria-label={t.common.delete}
                    >
                      <Trash2 className="w-4 h-4" />
                    </button>
                  </div>
                ))}
              </div>
            )}
          </Card>
        </div>

        {/* Sidebar - 1/3 */}
        <div className="space-y-4">
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
                      className="p-1 rounded text-text-tertiary hover:text-danger opacity-0 group-hover:opacity-100 transition-all"
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
                retry: { command: '', max_retries: 3, delay: 5 },
                foreach: { items_command: '', variable_name: 'item', command: '' },
                while: { condition_command: '', command: '', max_iterations: 10 },
                conditional: { condition: '', then_command: '', else_command: '' },
                'skill-capability': { capability: '', input: '{{ previous_output }}' },
              };
              setNewBlock({ type, config: JSON.stringify(defaultConfig[type], null, 2) });
            }}
            options={BLOCK_TYPES.map((type) => ({
              value: type,
              label: t.workflows.blockTypes[type],
            }))}
          />
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
    </div>
  );
}
