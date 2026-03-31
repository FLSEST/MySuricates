import { useState, lazy, Suspense } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Plus, History } from 'lucide-react';

const WorkflowVersionsTab = lazy(() => import('../components/workflow/WorkflowVersionsTab'));
import { Card, CardHeader, Button, Spinner } from '../components/ui';
import { ErrorState } from '../components/shared/ErrorState';
import {
  useWorkflow, useWorkflows as useWorkflowsList, useWorkflowRuns, useUpdateWorkflow,
  useDeleteWorkflow, useExecuteWorkflow, useOptimizeWorkflow, useRepairWorkflow, useCreateWorkflow,
} from '../hooks/useWorkflows';
import { useSkills, useActivateSkills, useDeactivateSkill } from '../hooks/useSkills';
import { useProjects } from '../hooks/useProjects';
import { SortableBlockList } from '../components/workflow/SortableBlockList';
import { WorkflowDetailHeader } from '../components/workflow/WorkflowDetailHeader';
import { WorkflowDetailSidebar } from '../components/workflow/WorkflowDetailSidebar';
import { WorkflowDetailModals } from '../components/workflow/WorkflowDetailModals';
import { CompletionTriggerSection } from '../components/workflow/CompletionTriggerSection';
import { useT } from '../i18n';
import { extractWorkflowVariables } from '../utils/extractWorkflowVariables';
import type { BlockDefinition, BlockType, OptimizeResult, UpdateWorkflowRequest } from '../types';

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
  const repairWorkflow = useRepairWorkflow();
  const createWorkflow = useCreateWorkflow();
  const activateSkills = useActivateSkills();
  const deactivateSkill = useDeactivateSkill();

  const [showEdit, setShowEdit] = useState(false);
  const [showAddBlock, setShowAddBlock] = useState(false);
  const [showEditBlock, setShowEditBlock] = useState(false);
  const [editBlockIndex, setEditBlockIndex] = useState<number>(-1);
  const [editBlock, setEditBlock] = useState<{ type: BlockType; config: string; skillIds: string[] }>({
    type: 'simple',
    config: '{}',
    skillIds: [],
  });
  const [showAddSkill, setShowAddSkill] = useState(false);
  const [showDelete, setShowDelete] = useState(false);
  const [showEditInstructions, setShowEditInstructions] = useState(false);
  const [showOptimizeResult, setShowOptimizeResult] = useState(false);
  const [optimizeResult, setOptimizeResult] = useState<OptimizeResult | null>(null);
  const [editInstructions, setEditInstructions] = useState('');
  const [editForm, setEditForm] = useState({ name: '', description: '', icon: '' });
  const [newBlock, setNewBlock] = useState<{ type: BlockType; config: string; skillIds: string[] }>({
    type: 'simple',
    config: JSON.stringify({ command: '' }, null, 2),
    skillIds: [],
  });
  const [selectedSkillId, setSelectedSkillId] = useState('');
  const [activeTab, setActiveTab] = useState<'blocks' | 'versions'>('blocks');
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

  const hasFailedRuns = runs?.content?.some((r) => r.status === 'FAILED') ?? false;

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
      const block: BlockDefinition = { type: newBlock.type, config, ...(newBlock.skillIds.length > 0 ? { skillIds: newBlock.skillIds } : {}) };
      updateWorkflow.mutate(
        { id: workflow.id, data: { blocks: [...workflow.blocks, block] } },
        {
          onSuccess: () => {
            setShowAddBlock(false);
            setNewBlock({ type: 'simple', config: JSON.stringify({ command: '' }, null, 2), skillIds: [] });
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
    setEditBlock({ type: block.type, config: JSON.stringify(block.config, null, 2), skillIds: block.skillIds ?? [] });
    setShowEditBlock(true);
  };

  const handleSaveBlock = () => {
    try {
      const config = JSON.parse(editBlock.config);
      const blocks = [...workflow.blocks];
      blocks[editBlockIndex] = { type: editBlock.type, config, ...(editBlock.skillIds.length > 0 ? { skillIds: editBlock.skillIds } : {}) };
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

  const handleSaveInstructions = () => {
    updateWorkflow.mutate(
      { id: workflow.id, data: { instructions: editInstructions } },
      { onSuccess: () => setShowEditInstructions(false) }
    );
  };

  const handleApplyOptimize = () => {
    if (!optimizeResult) return;
    const data: UpdateWorkflowRequest = {};
    if (optimizeResult.suggestedName) data.name = optimizeResult.suggestedName;
    if (optimizeResult.suggestedDescription) data.description = optimizeResult.suggestedDescription;
    if (optimizeResult.suggestedInstructions) data.instructions = optimizeResult.suggestedInstructions;
    if (optimizeResult.blocks && optimizeResult.blocks.length > 0) {
      data.blocks = optimizeResult.blocks;
    }
    updateWorkflow.mutate(
      { id: workflow.id, data },
      { onSuccess: () => setShowOptimizeResult(false) }
    );
  };

  const handleClone = () => {
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
  };

  const availableSkills = allSkills?.filter(
    (s) => !workflow.activatedSkillIds.includes(s.id)
  ) ?? [];

  return (
    <div className="space-y-6 animate-fade-in">
      <WorkflowDetailHeader
        workflow={workflow}
        t={t}
        onNavigateBack={() => navigate('/workflows')}
        onEdit={openEdit}
        onExecute={handleExecute}
        onOptimize={() => {
          optimizeWorkflow.mutate(workflow.id, {
            onSuccess: (data) => {
              setOptimizeResult(data);
              setShowOptimizeResult(true);
            },
          });
        }}
        onRepair={() => repairWorkflow.mutate(workflow.id)}
        repairLoading={repairWorkflow.isPending}
        hasFailedRuns={hasFailedRuns}
        onClone={() => {
          setCloneForm({ projectId: workflow.projectId, name: `${workflow.name} (Copy)` });
          setShowClone(true);
        }}
        onDelete={() => setShowDelete(true)}
        executeLoading={executeWorkflow.isPending}
        optimizeLoading={optimizeWorkflow.isPending}
      />

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="lg:col-span-2 space-y-4">
          <div className="flex border-b border-border mb-0">
            <button
              className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
                activeTab === 'blocks'
                  ? 'border-accent text-accent'
                  : 'border-transparent text-text-secondary hover:text-text hover:border-border'
              }`}
              onClick={() => setActiveTab('blocks')}
            >
              {t.workflows.blocksTab} ({workflow.blocks.length})
            </button>
            <button
              className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors inline-flex items-center gap-1.5 ${
                activeTab === 'versions'
                  ? 'border-accent text-accent'
                  : 'border-transparent text-text-secondary hover:text-text hover:border-border'
              }`}
              onClick={() => setActiveTab('versions')}
            >
              <History className="w-3.5 h-3.5" />
              {t.workflows.versionsTab}
            </button>
          </div>

          {activeTab === 'blocks' && (
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
          )}

          {activeTab === 'versions' && (
            <Suspense fallback={<div className="py-8 text-center text-text-secondary">{t.common.loading}</div>}>
              <WorkflowVersionsTab workflowId={workflow.id} />
            </Suspense>
          )}
        </div>

        <WorkflowDetailSidebar
          workflow={workflow}
          runs={runs}
          workflowVariables={workflowVariables}
          onOpenEditInstructions={() => {
            setEditInstructions(workflow.instructions || '');
            setShowEditInstructions(true);
          }}
          onOpenAddSkill={() => setShowAddSkill(true)}
          onDeactivateSkill={(wId, sId) => deactivateSkill.mutate({ workflowId: wId, skillId: sId })}
          onNavigateToRun={(runId) => navigate(`/workflows/${workflow.id}/run/${runId}`)}
        />
      </div>

      <CompletionTriggerSection workflowId={workflow.id} allWorkflows={allWorkflows} />

      <WorkflowDetailModals
        workflow={workflow}
        workflowId={id!}
        showEdit={showEdit}
        setShowEdit={setShowEdit}
        editForm={editForm}
        setEditForm={setEditForm}
        onUpdate={handleUpdate}
        updateLoading={updateWorkflow.isPending}
        showAddBlock={showAddBlock}
        setShowAddBlock={setShowAddBlock}
        newBlock={newBlock}
        setNewBlock={setNewBlock}
        onAddBlock={handleAddBlock}
        allWorkflows={allWorkflows}
        allSkills={allSkills ?? []}
        showEditBlock={showEditBlock}
        setShowEditBlock={setShowEditBlock}
        editBlockIndex={editBlockIndex}
        editBlock={editBlock}
        setEditBlock={setEditBlock}
        onSaveBlock={handleSaveBlock}
        showAddSkill={showAddSkill}
        setShowAddSkill={setShowAddSkill}
        selectedSkillId={selectedSkillId}
        setSelectedSkillId={setSelectedSkillId}
        availableSkills={availableSkills}
        onActivateSkill={handleActivateSkill}
        activateLoading={activateSkills.isPending}
        showDelete={showDelete}
        setShowDelete={setShowDelete}
        onDelete={handleDelete}
        deleteLoading={deleteWorkflow.isPending}
        showClone={showClone}
        setShowClone={setShowClone}
        cloneForm={cloneForm}
        setCloneForm={setCloneForm}
        projects={projects}
        onClone={handleClone}
        cloneLoading={createWorkflow.isPending}
        showExecuteDialog={showExecuteDialog}
        setShowExecuteDialog={setShowExecuteDialog}
        workflowVariables={workflowVariables}
        defaultValues={workflow.defaultContext}
        onExecuteWithContext={handleExecuteWithContext}
        executeLoading={executeWorkflow.isPending}
        showEditInstructions={showEditInstructions}
        setShowEditInstructions={setShowEditInstructions}
        editInstructions={editInstructions}
        setEditInstructions={setEditInstructions}
        onSaveInstructions={handleSaveInstructions}
        showOptimizeResult={showOptimizeResult}
        setShowOptimizeResult={setShowOptimizeResult}
        optimizeResult={optimizeResult}
        onApplyOptimize={handleApplyOptimize}
      />
    </div>
  );
}
