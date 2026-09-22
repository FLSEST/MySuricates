import { Hint } from '../components/hints/Hint';
import { useRef, useState, lazy, Suspense } from 'react';
import { useParams, useNavigate, useSearchParams } from 'react-router-dom';
import { Plus, History, PanelRightClose, PanelRightOpen } from 'lucide-react';

const WorkflowVersionsTab = lazy(() => import('../components/workflow/WorkflowVersionsTab'));
const WorkflowDiagramTab = lazy(() => import('../components/workflow/WorkflowDiagramTab'));
type DiagramSelection = import('../components/workflow/BpmnDiagramView').DiagramSelection;
import { Card, CardHeader, Button, Spinner } from '../components/ui';
import { ErrorState } from '../components/shared/ErrorState';
import { ResizableSplit } from '../components/shared/ResizableSplit';
import { apiErrorMessage } from '../api/client';
import { useToast } from '../hooks/useToast';
import {
  useWorkflow, useWorkflows as useWorkflowsList, useWorkflowRuns, useUpdateWorkflow,
  useDeleteWorkflow, useExecuteWorkflow, useOptimizeWorkflow, useRepairWorkflow, useCreateWorkflow,
} from '../hooks/useWorkflows';
import { useSkills, useActivateSkills, useDeactivateSkill } from '../hooks/useSkills';
import { useModuleEnabled } from '../hooks/useModules';
import { usePersistedState } from '../hooks/usePersistedState';
import { useProjects } from '../hooks/useProjects';
import { SortableBlockList } from '../components/workflow/SortableBlockList';
import { WorkflowDetailHeader } from '../components/workflow/WorkflowDetailHeader';
import { WorkflowDetailSidebar } from '../components/workflow/WorkflowDetailSidebar';
import { WorkflowDetailModals } from '../components/workflow/WorkflowDetailModals';
import { CompletionTriggerSection } from '../components/workflow/CompletionTriggerSection';
import { blockFromDraft, draftFromBlock, emptyDraft, type BlockDraft } from '../components/workflow/blockEdit';
import { useT } from '../i18n';
import { extractWorkflowVariables } from '../utils/extractWorkflowVariables';
import { buildDefinitionText, blockLineRanges } from '../utils/workflowDefinition';
import { readContextSchema } from '../utils/contextSchema';
import type { BlockType, OptimizeResult, UpdateWorkflowRequest } from '../types';

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
  const toast = useToast();
  const deleteWorkflow = useDeleteWorkflow();
  const executeWorkflow = useExecuteWorkflow();
  const optimizeWorkflow = useOptimizeWorkflow();
  const repairWorkflow = useRepairWorkflow();
  const bpmnModule = useModuleEnabled('bpmn');
  const createWorkflow = useCreateWorkflow();
  const activateSkills = useActivateSkills();
  const deactivateSkill = useDeactivateSkill();

  const [showEdit, setShowEdit] = useState(false);
  const [showAddBlock, setShowAddBlock] = useState(false);
  const [showEditBlock, setShowEditBlock] = useState(false);
  const [editBlockIndex, setEditBlockIndex] = useState<number>(-1);
  const [editBlock, setEditBlock] = useState<BlockDraft>(emptyDraft('simple', '{}'));
  const [showAddSkill, setShowAddSkill] = useState(false);
  const [showDelete, setShowDelete] = useState(false);
  const [showEditInstructions, setShowEditInstructions] = useState(false);
  const [showOptimizeResult, setShowOptimizeResult] = useState(false);
  const [optimizeResult, setOptimizeResult] = useState<OptimizeResult | null>(null);
  const [editInstructions, setEditInstructions] = useState('');
  const [editForm, setEditForm] = useState({ name: '', description: '', icon: '' });
  const [newBlock, setNewBlock] = useState<BlockDraft>(emptyDraft());
  const [selectedSkillId, setSelectedSkillId] = useState('');
  const [searchParams] = useSearchParams();
  const initialTab = searchParams.get('tab');
  // The diagram sits below the blocks; the legacy `?tab=diagram` link opens the blocks and scrolls to it
  const [activeTab, setActiveTab] = useState<'blocks' | 'versions'>(
    initialTab === 'versions' ? 'versions' : 'blocks',
  );
  const diagramScrollPending = useRef(initialTab === 'diagram');
  const [blockReveal, setBlockReveal] = useState<{ text: string; nonce: number } | null>(null);
  const [showExecuteDialog, setShowExecuteDialog] = useState(false);
  const [showClone, setShowClone] = useState(false);
  const [cloneForm, setCloneForm] = useState({ projectId: '', name: '' });
  const [sidebarCollapsed, setSidebarCollapsed] = usePersistedState('workflow-detail-sidebar-collapsed', false);

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
  // Günstig (JSON.stringify der Blöcke) und nach dem Early-Return — daher ohne useMemo (Hook-Regel)
  const definitionText = buildDefinitionText(workflow.blocks);
  const definitionLineRanges = blockLineRanges(workflow.blocks);

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
      const block = blockFromDraft(newBlock);
      updateWorkflow.mutate(
        { id: workflow.id, data: { blocks: [...workflow.blocks, block] } },
        {
          onSuccess: () => {
            setShowAddBlock(false);
            setNewBlock(emptyDraft());
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

  const handleOpenEditBlock = (index: number, revealText?: string) => {
    const block = workflow.blocks[index];
    if (!block) return;
    setEditBlockIndex(index);
    setEditBlock(draftFromBlock(block));
    setBlockReveal(revealText ? { text: revealText, nonce: Date.now() } : null);
    setShowEditBlock(true);
  };

  /** Primäres Config-Feld je Block-Typ — die Zeile, auf die ein Top-Level-Klick springt. */
  const PRIMARY_FIELD: Partial<Record<BlockType, string>> = {
    shell: 'command', simple: 'command', retry: 'command',
    agentic: 'goal', approval: 'question', 'human-input': 'prompt',
    'skill-capability': 'capability', 'sub-workflow': 'workflow_id',
    conditional: 'condition', switch: 'expression', foreach: 'variable_name',
    while: 'condition_command', document: 'file_path',
    compress: 'source', 'compress-ai': 'source',
  };

  /** Ermittelt aus einer Diagramm-Auswahl den JSON-Suchtext, auf den der Editor springen soll. */
  const revealTextFor = (sel: DiagramSelection): string | undefined => {
    const block = workflow.blocks[sel.blockIndex];
    const config = block?.config as Record<string, unknown> | undefined;
    if (!config) return undefined;
    if (sel.sub) {
      switch (sel.sub.kind) {
        case 'case': {
          const cases = Array.isArray(config.cases) ? (config.cases as Array<{ value?: unknown }>) : [];
          const value = cases[sel.sub.caseIndex]?.value;
          return typeof value === 'string' && value ? `"${value}"` : '"cases"';
        }
        case 'default': return '"default"';
        case 'then': return '"then_command"';
        case 'else': return '"else_command"';
        case 'body': return '"command"';
      }
    }
    // Top-Level: primäres Feld des Block-Typs, falls vorhanden
    const key = block ? PRIMARY_FIELD[block.type] : undefined;
    return key && key in config ? `"${key}"` : undefined;
  };

  const handleSelectFromDiagram = (sel: DiagramSelection) => {
    // Jeder Kasten öffnet den Block-Editor und springt zur passenden Definitionszeile
    handleOpenEditBlock(sel.blockIndex, revealTextFor(sel));
  };

  const handleSaveBlock = () => {
    try {
      const blocks = [...workflow.blocks];
      blocks[editBlockIndex] = blockFromDraft(editBlock, workflow.blocks[editBlockIndex]);
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
      {
        onSuccess: () => setShowOptimizeResult(false),
        onError: (err) => toast.error(apiErrorMessage(err, t.toast.workflowUpdateFailed)),
      }
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

      <Hint id="workflow-versions" />

      <ResizableSplit
        storageKey="workflow-detail-sidebar-width"
        separatorLabel={t.workflows.resizeSidebar}
        collapsed={sidebarCollapsed}
        sideId="workflow-detail-sidebar"
        main={
        <div className="space-y-4">
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
            <button
              type="button"
              onClick={() => setSidebarCollapsed((c) => !c)}
              aria-controls="workflow-detail-sidebar"
              aria-expanded={!sidebarCollapsed}
              className="ml-auto hidden lg:inline-flex items-center gap-1.5 self-center px-2 py-1 rounded-md text-sm text-text-secondary hover:text-text hover:bg-surface-raised transition-colors"
            >
              {sidebarCollapsed ? <PanelRightOpen className="w-4 h-4" /> : <PanelRightClose className="w-4 h-4" />}
              {sidebarCollapsed ? t.workflows.showSidebar : t.workflows.hideSidebar}
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
                      disabled={!!workflow.sealed}
                      title={workflow.sealed ? t.workflows.sealedLocked : undefined}
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
                // A sealed definition is read-only: no drag, edit or remove
                <fieldset disabled={!!workflow.sealed} className="min-w-0" title={workflow.sealed ? t.workflows.sealedLocked : undefined}>
                  <SortableBlockList
                    workflowId={workflow.id}
                    blocks={workflow.blocks}
                    onEditBlock={workflow.sealed ? () => {} : handleOpenEditBlock}
                    onRemoveBlock={handleRemoveBlock}
                    locked={!!workflow.sealed}
                  />
                </fieldset>
              )}
            </Card>
          )}

          {activeTab === 'blocks' && bpmnModule && id && workflow.blocks.length > 0 && (
            <section
              aria-label={t.workflows.diagramTab}
              ref={(el) => {
                if (el && diagramScrollPending.current) {
                  diagramScrollPending.current = false;
                  el.scrollIntoView({ block: 'start' });
                }
              }}
            >
              <h2 className="text-sm font-semibold text-text mb-2">{t.workflows.diagramTab}</h2>
              <Suspense fallback={<div className="p-6 text-sm text-text-secondary">{t.common.loading}</div>}>
                <WorkflowDiagramTab
                  workflowId={id}
                  workflowName={workflow.name}
                  definitionText={definitionText}
                  lineRanges={definitionLineRanges}
                  onSelectBlock={handleSelectFromDiagram}
                />
              </Suspense>
            </section>
          )}

          {activeTab === 'versions' && (
            <Suspense fallback={<div className="py-8 text-center text-text-secondary">{t.common.loading}</div>}>
              <WorkflowVersionsTab workflowId={workflow.id} currentHash={workflow.definitionHash} />
            </Suspense>
          )}

        </div>
        }
        side={
        <WorkflowDetailSidebar
          workflow={workflow}
          workflowVariables={workflowVariables}
          onOpenEditInstructions={() => {
            setEditInstructions(workflow.instructions || '');
            setShowEditInstructions(true);
          }}
          onOpenAddSkill={() => setShowAddSkill(true)}
          onDeactivateSkill={(wId, sId) => deactivateSkill.mutate({ workflowId: wId, skillId: sId })}
          onNavigateToRun={(runId) => navigate(`/workflows/${workflow.id}/run/${runId}`)}
        />
        }
      />

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
        editBlockRevealText={blockReveal?.text}
        editBlockRevealNonce={blockReveal?.nonce}
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
        optionalVariables={readContextSchema(workflow).optional}
        variableDescriptions={readContextSchema(workflow).descriptions}
        variablePlaceholders={readContextSchema(workflow).placeholders}
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
