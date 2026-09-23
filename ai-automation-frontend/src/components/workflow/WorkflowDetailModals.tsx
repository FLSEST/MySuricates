import { ExecuteDialog } from './ExecuteDialog';
import { WorkflowEditModal, type WorkflowEditForm } from './WorkflowEditModal';
import { BlockEditorModal, type SkillOption, type WorkflowOption } from './BlockEditorModal';
import { ActivateSkillModal } from './ActivateSkillModal';
import { DeleteWorkflowModal } from './DeleteWorkflowModal';
import { CloneWorkflowModal, type CloneForm } from './CloneWorkflowModal';
import { InstructionsModal } from './InstructionsModal';
import { OptimizeResultModal } from './OptimizeResultModal';
import type { BlockDraft } from './blockEdit';
import type { Workflow, OptimizeResult, Project } from '../../types';

/**
 * All dialogs of the workflow detail page. Each dialog lives in its own file (TODO 7473fdcb);
 * this component only wires the page's state to them, so WorkflowDetail keeps one mount point.
 */
interface WorkflowDetailModalsProps {
  workflow: Workflow;
  workflowId: string;

  showEdit: boolean;
  setShowEdit: (v: boolean) => void;
  editForm: WorkflowEditForm;
  setEditForm: (v: WorkflowEditForm) => void;
  onUpdate: () => void;
  updateLoading: boolean;

  showAddBlock: boolean;
  setShowAddBlock: (v: boolean) => void;
  newBlock: BlockDraft;
  setNewBlock: (v: BlockDraft) => void;
  onAddBlock: () => void;
  allWorkflows: WorkflowOption[] | undefined;
  allSkills: SkillOption[];

  showEditBlock: boolean;
  setShowEditBlock: (v: boolean) => void;
  editBlockIndex: number;
  editBlock: BlockDraft;
  setEditBlock: (v: BlockDraft) => void;
  onSaveBlock: () => void;
  editBlockRevealText?: string;
  editBlockRevealNonce?: number;

  showAddSkill: boolean;
  setShowAddSkill: (v: boolean) => void;
  selectedSkillId: string;
  setSelectedSkillId: (v: string) => void;
  availableSkills: SkillOption[];
  onActivateSkill: () => void;
  activateLoading: boolean;

  showDelete: boolean;
  setShowDelete: (v: boolean) => void;
  onDelete: () => void;
  deleteLoading: boolean;

  showClone: boolean;
  setShowClone: (v: boolean) => void;
  cloneForm: CloneForm;
  setCloneForm: (v: CloneForm) => void;
  projects: Project[] | undefined;
  onClone: () => void;
  cloneLoading: boolean;

  showExecuteDialog: boolean;
  setShowExecuteDialog: (v: boolean) => void;
  workflowVariables: string[];
  defaultValues: Record<string, string> | undefined;
  optionalVariables?: string[];
  variableDescriptions?: Record<string, string>;
  variablePlaceholders?: Record<string, string>;
  onExecuteWithContext: (context: Record<string, string>) => void;
  executeLoading: boolean;

  showEditInstructions: boolean;
  setShowEditInstructions: (v: boolean) => void;
  editInstructions: string;
  setEditInstructions: (v: string) => void;
  onSaveInstructions: () => void;

  showOptimizeResult: boolean;
  setShowOptimizeResult: (v: boolean) => void;
  optimizeResult: OptimizeResult | null;
  onApplyOptimize: () => void;
}

export function WorkflowDetailModals({
  workflow,
  workflowId,

  showEdit,
  setShowEdit,
  editForm,
  setEditForm,
  onUpdate,
  updateLoading,

  showAddBlock,
  setShowAddBlock,
  newBlock,
  setNewBlock,
  onAddBlock,
  allWorkflows,
  allSkills,

  showEditBlock,
  setShowEditBlock,
  editBlockIndex,
  editBlock,
  setEditBlock,
  onSaveBlock,
  editBlockRevealText,
  editBlockRevealNonce,

  showAddSkill,
  setShowAddSkill,
  selectedSkillId,
  setSelectedSkillId,
  availableSkills,
  onActivateSkill,
  activateLoading,

  showDelete,
  setShowDelete,
  onDelete,
  deleteLoading,

  showClone,
  setShowClone,
  cloneForm,
  setCloneForm,
  projects,
  onClone,
  cloneLoading,

  showExecuteDialog,
  setShowExecuteDialog,
  workflowVariables,
  defaultValues,
  optionalVariables,
  variableDescriptions,
  variablePlaceholders,
  onExecuteWithContext,
  executeLoading,

  showEditInstructions,
  setShowEditInstructions,
  editInstructions,
  setEditInstructions,
  onSaveInstructions,

  showOptimizeResult,
  setShowOptimizeResult,
  optimizeResult,
  onApplyOptimize,
}: WorkflowDetailModalsProps) {
  return (
    <>
      <WorkflowEditModal
        open={showEdit}
        onClose={() => setShowEdit(false)}
        form={editForm}
        setForm={setEditForm}
        onSave={onUpdate}
        saving={updateLoading}
      />

      <BlockEditorModal
        mode="add"
        open={showAddBlock}
        onClose={() => setShowAddBlock(false)}
        workflow={workflow}
        workflowId={workflowId}
        position={workflow.blocks.length}
        block={newBlock}
        setBlock={setNewBlock}
        onSave={onAddBlock}
        saving={updateLoading}
        allWorkflows={allWorkflows}
        allSkills={allSkills}
      />

      <BlockEditorModal
        mode="edit"
        open={showEditBlock}
        onClose={() => setShowEditBlock(false)}
        workflow={workflow}
        workflowId={workflowId}
        position={editBlockIndex}
        block={editBlock}
        setBlock={setEditBlock}
        onSave={onSaveBlock}
        saving={updateLoading}
        allWorkflows={allWorkflows}
        allSkills={allSkills}
        revealText={editBlockRevealText}
        revealNonce={editBlockRevealNonce}
      />

      <ActivateSkillModal
        open={showAddSkill}
        onClose={() => setShowAddSkill(false)}
        selectedSkillId={selectedSkillId}
        setSelectedSkillId={setSelectedSkillId}
        availableSkills={availableSkills}
        onActivate={onActivateSkill}
        activating={activateLoading}
      />

      <DeleteWorkflowModal
        open={showDelete}
        onClose={() => setShowDelete(false)}
        workflowName={workflow.name}
        onDelete={onDelete}
        deleting={deleteLoading}
      />

      <CloneWorkflowModal
        open={showClone}
        onClose={() => setShowClone(false)}
        form={cloneForm}
        setForm={setCloneForm}
        projects={projects}
        onClone={onClone}
        cloning={cloneLoading}
      />

      <ExecuteDialog
        open={showExecuteDialog}
        onClose={() => setShowExecuteDialog(false)}
        onExecute={onExecuteWithContext}
        variables={workflowVariables}
        defaultValues={defaultValues}
        optionalVariables={optionalVariables}
        descriptions={variableDescriptions}
        placeholders={variablePlaceholders}
        loading={executeLoading}
      />

      <InstructionsModal
        open={showEditInstructions}
        onClose={() => setShowEditInstructions(false)}
        instructions={editInstructions}
        setInstructions={setEditInstructions}
        onSave={onSaveInstructions}
        saving={updateLoading}
      />

      <OptimizeResultModal
        open={showOptimizeResult}
        onClose={() => setShowOptimizeResult(false)}
        result={optimizeResult}
        onApply={onApplyOptimize}
        applying={updateLoading}
      />
    </>
  );
}
