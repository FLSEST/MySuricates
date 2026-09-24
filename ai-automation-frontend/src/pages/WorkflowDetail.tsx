import { Hint } from '../components/hints/Hint';
import { useRef, useState, lazy, Suspense } from 'react';
import { useParams, useNavigate, useSearchParams } from 'react-router-dom';
import { History, PanelRightClose, PanelRightOpen } from 'lucide-react';

const WorkflowVersionsTab = lazy(() => import('../components/workflow/WorkflowVersionsTab'));
import { Spinner } from '../components/ui';
import { ErrorState } from '../components/shared/ErrorState';
import { ResizableSplit } from '../components/shared/ResizableSplit';
import { useWorkflow, useWorkflows as useWorkflowsList, useWorkflowRuns } from '../hooks/useWorkflows';
import { useSkills } from '../hooks/useSkills';
import { usePersistedState } from '../hooks/usePersistedState';
import { useProjects } from '../hooks/useProjects';
import { useWorkflowBlockEditor } from '../hooks/useWorkflowBlockEditor';
import { useWorkflowDetailActions } from '../hooks/useWorkflowDetailActions';
import { WorkflowDetailHeader } from '../components/workflow/WorkflowDetailHeader';
import { SealBanner } from '../components/workflow/SealBanner';
import { useSealStatus } from '../hooks/useSealStatus';
import { WorkflowDetailSidebar } from '../components/workflow/WorkflowDetailSidebar';
import { WorkflowDetailModals } from '../components/workflow/WorkflowDetailModals';
import { WorkflowBlocksTab } from '../components/workflow/WorkflowBlocksTab';
import { CompletionTriggerSection } from '../components/workflow/CompletionTriggerSection';
import { useT } from '../i18n';
import { readContextSchema } from '../utils/contextSchema';

export function WorkflowDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const t = useT();
  const { data: workflow, isLoading, error, refetch } = useWorkflow(id!);
  const sealStatus = useSealStatus(id);
  const { data: runs } = useWorkflowRuns(id!);
  const { data: allSkills } = useSkills();
  const { data: allWorkflows } = useWorkflowsList();
  const { data: projects } = useProjects();
  const blocks = useWorkflowBlockEditor(workflow);
  const actions = useWorkflowDetailActions(workflow);

  const [searchParams] = useSearchParams();
  const initialTab = searchParams.get('tab');
  // The diagram sits below the blocks; the legacy `?tab=diagram` link opens the blocks and scrolls to it
  const [activeTab, setActiveTab] = useState<'blocks' | 'versions'>(
    initialTab === 'versions' ? 'versions' : 'blocks',
  );
  const diagramScrollPending = useRef(initialTab === 'diagram');
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

  const hasFailedRuns = runs?.content?.some((r) => r.status === 'FAILED') ?? false;
  const contextSchema = readContextSchema(workflow);
  const availableSkills = allSkills?.filter(
    (s) => !workflow.activatedSkillIds.includes(s.id)
  ) ?? [];

  return (
    <div className="space-y-6 animate-fade-in">
      <WorkflowDetailHeader
        workflow={workflow}
        t={t}
        onNavigateBack={() => navigate('/workflows')}
        onEdit={actions.openEdit}
        onExecute={actions.execute}
        onOptimize={actions.optimize}
        onRepair={actions.repair}
        repairLoading={actions.repairing}
        hasFailedRuns={hasFailedRuns}
        onClone={actions.openClone}
        onDelete={() => actions.setShowDelete(true)}
        executeLoading={actions.executing}
        optimizeLoading={actions.optimizing}
      />

      <SealBanner workflow={workflow} status={sealStatus} />

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
            <WorkflowBlocksTab
              workflow={workflow}
              onAddBlock={() => blocks.setShowAddBlock(true)}
              onEditBlock={blocks.openEditBlock}
              onRemoveBlock={blocks.removeBlock}
              onSelectFromDiagram={blocks.selectFromDiagram}
              diagramRef={(el) => {
                if (el && diagramScrollPending.current) {
                  diagramScrollPending.current = false;
                  el.scrollIntoView({ block: 'start' });
                }
              }}
            />
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
          workflowVariables={actions.workflowVariables}
          onOpenEditInstructions={actions.openInstructions}
          onOpenAddSkill={() => actions.setShowAddSkill(true)}
          onDeactivateSkill={actions.deactivateSkill}
          onNavigateToRun={(runId) => navigate(`/workflows/${workflow.id}/run/${runId}`)}
        />
        }
      />

      <CompletionTriggerSection workflowId={workflow.id} allWorkflows={allWorkflows} />

      <WorkflowDetailModals
        workflow={workflow}
        workflowId={id!}
        showEdit={actions.showEdit}
        setShowEdit={actions.setShowEdit}
        editForm={actions.editForm}
        setEditForm={actions.setEditForm}
        onUpdate={actions.update}
        // Edit, blocks, instructions and optimize all save through a workflow update
        updateLoading={actions.saving || blocks.saving}
        showAddBlock={blocks.showAddBlock}
        setShowAddBlock={blocks.setShowAddBlock}
        newBlock={blocks.newBlock}
        setNewBlock={blocks.setNewBlock}
        onAddBlock={blocks.addBlock}
        allWorkflows={allWorkflows}
        allSkills={allSkills ?? []}
        showEditBlock={blocks.showEditBlock}
        setShowEditBlock={blocks.setShowEditBlock}
        editBlockIndex={blocks.editBlockIndex}
        editBlock={blocks.editBlock}
        setEditBlock={blocks.setEditBlock}
        onSaveBlock={blocks.saveBlock}
        editBlockRevealText={blocks.reveal?.text}
        editBlockRevealNonce={blocks.reveal?.nonce}
        showAddSkill={actions.showAddSkill}
        setShowAddSkill={actions.setShowAddSkill}
        selectedSkillId={actions.selectedSkillId}
        setSelectedSkillId={actions.setSelectedSkillId}
        availableSkills={availableSkills}
        onActivateSkill={actions.activateSkill}
        activateLoading={actions.activating}
        showDelete={actions.showDelete}
        setShowDelete={actions.setShowDelete}
        onDelete={actions.remove}
        deleteLoading={actions.deleting}
        showClone={actions.showClone}
        setShowClone={actions.setShowClone}
        cloneForm={actions.cloneForm}
        setCloneForm={actions.setCloneForm}
        projects={projects}
        onClone={actions.clone}
        cloneLoading={actions.cloning}
        showExecuteDialog={actions.showExecuteDialog}
        setShowExecuteDialog={actions.setShowExecuteDialog}
        workflowVariables={actions.workflowVariables}
        defaultValues={workflow.defaultContext}
        optionalVariables={contextSchema.optional}
        variableDescriptions={contextSchema.descriptions}
        variablePlaceholders={contextSchema.placeholders}
        onExecuteWithContext={actions.executeWithContext}
        executeLoading={actions.executing}
        showEditInstructions={actions.showEditInstructions}
        setShowEditInstructions={actions.setShowEditInstructions}
        editInstructions={actions.editInstructions}
        setEditInstructions={actions.setEditInstructions}
        onSaveInstructions={actions.saveInstructions}
        showOptimizeResult={actions.showOptimizeResult}
        setShowOptimizeResult={actions.setShowOptimizeResult}
        optimizeResult={actions.optimizeResult}
        onApplyOptimize={actions.applyOptimize}
      />
    </div>
  );
}
