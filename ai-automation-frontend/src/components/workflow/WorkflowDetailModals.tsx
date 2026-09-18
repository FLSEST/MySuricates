import { Button, Input, Select, Modal, ModalFooter } from '../ui';
import { MonacoEditor } from '../shared/MonacoEditor';
import { ExecuteDialog } from './ExecuteDialog';
import { BlockAiOverrideFields } from './BlockAiOverrideFields';
import { DashboardReportFields } from './DashboardReportFields';
import type { BlockDraft } from './blockEdit';
import { applyPreset, COMPRESS_PRESETS, type CompressPresetId } from './compression';
import { useT } from '../../i18n';
import { useBlockTypes } from '../../hooks/useBlockTypes';
import type {
  BlockType, Workflow, OptimizeResult, Project,
} from '../../types';

interface AvailableSkill {
  id: string;
  name: string;
}

interface WorkflowListItem {
  id: string;
  name: string;
  icon: string;
}

interface WorkflowDetailModalsProps {
  workflow: Workflow;
  workflowId: string;

  showEdit: boolean;
  setShowEdit: (v: boolean) => void;
  editForm: { name: string; description: string; icon: string };
  setEditForm: (v: { name: string; description: string; icon: string }) => void;
  onUpdate: () => void;
  updateLoading: boolean;

  showAddBlock: boolean;
  setShowAddBlock: (v: boolean) => void;
  newBlock: BlockDraft;
  setNewBlock: (v: BlockDraft) => void;
  onAddBlock: () => void;
  allWorkflows: WorkflowListItem[] | undefined;
  allSkills: AvailableSkill[];

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
  availableSkills: AvailableSkill[];
  onActivateSkill: () => void;
  activateLoading: boolean;

  showDelete: boolean;
  setShowDelete: (v: boolean) => void;
  onDelete: () => void;
  deleteLoading: boolean;

  showClone: boolean;
  setShowClone: (v: boolean) => void;
  cloneForm: { projectId: string; name: string };
  setCloneForm: (v: { projectId: string; name: string }) => void;
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
  const t = useT();
  // What THIS backend can execute (BlockTypeRegistry); the static list is only the fallback
  const blockTypes = useBlockTypes();
  // A type only the server knows has no icon and no label in this build — offer its raw name
  const blockTypeOptions = blockTypes.map((type) => ({
    value: type,
    label: t.workflows.blockTypes[type as BlockType] ?? type,
  }));

  return (
    <>
      <Modal
        open={showEdit}
        onClose={() => setShowEdit(false)}
        title={t.workflows.editWorkflow}
        footer={
          <ModalFooter
            onCancel={() => setShowEdit(false)}
            onAction={onUpdate}
            cancelLabel={t.common.cancel}
            actionLabel={t.common.save}
            actionLoading={updateLoading}
          />
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

      <Modal
        open={showAddBlock}
        onClose={() => setShowAddBlock(false)}
        title={t.workflows.addBlock}
        size="lg"
        footer={
          <ModalFooter
            onCancel={() => setShowAddBlock(false)}
            onAction={onAddBlock}
            cancelLabel={t.common.cancel}
            actionLabel={t.workflows.addBlock}
            actionLoading={updateLoading}
          />
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
                'human-input': { prompt: '', input_type: 'text', variable_name: 'user_input', options: [], default_value: '', required: true, timeout_minutes: 30, use_default_on_timeout: true },
                agentic: { goal: '', success_criteria: '', max_iterations: 10, iteration_timeout_seconds: 300, total_timeout_seconds: 1800, max_total_tokens: 200000, max_repeated_actions: 3, require_approval: false },
                'sub-workflow': { workflow_id: '' },
                switch: { expression: '', cases: [{ value: 'default', blocks: [] }] },
                document: { file_path: '', variable_name: 'extracted_text', fail_on_empty: false, min_characters: 1 },
                integration: { provider: '', action: '', params: {} },
                'todo-create': { title: '', description: '', priority: 'MEDIUM', tags: [], variable_name: 'todo_id' },
                'todo-update': { id: '{{ todo_id }}', title: '', description: '' },
                'todo-close': { id: '{{ todo_id }}' },
                compress: { source: '', stages: [{ op: 'text.normalize' }, { op: 'text.truncate', max_chars: 18000, keep: 'head_tail' }], max_chars: 20000 },
                'compress-ai': { source: '', instruction: '', max_chars: 4000, provider: 'CLAUDE' },
                'dashboard-report': { status: 'GREEN', title: '', summary: '', kpis: [], items: [] },
              };
              // A type only the server knows has no default config here — start from an empty one
              setNewBlock({ ...newBlock, type, config: JSON.stringify(defaultConfig[type] ?? {}, null, 2) });
            }}
            options={blockTypeOptions}
          />
          <Input
            label={t.workflows.blockName}
            value={newBlock.name}
            onChange={(e) => setNewBlock({ ...newBlock, name: e.target.value })}
            placeholder="backend_tests"
            hint={t.workflows.blockNameHint}
          />
          {newBlock.type === 'compress' && (
            <Select
              label={t.workflows.compression.preset}
              value=""
              onChange={(e) => {
                const preset = e.target.value as CompressPresetId;
                if (preset) setNewBlock({ ...newBlock, config: applyPreset(newBlock.config, preset) });
              }}
              options={[
                { value: '', label: t.workflows.compression.presetNone },
                ...(Object.keys(COMPRESS_PRESETS) as CompressPresetId[]).map((id) => ({ value: id, label: t.workflows.compression.presets[id] })),
              ]}
            />
          )}
          {newBlock.type === 'sub-workflow' && (
            <Select
              label={t.workflows.selectSubWorkflow}
              value={(() => { try { return JSON.parse(newBlock.config).workflow_id || ''; } catch { return ''; } })()}
              onChange={(e) => setNewBlock({ ...newBlock, config: JSON.stringify({ workflow_id: e.target.value }, null, 2) })}
              options={[
                { value: '', label: t.common.selectWorkflow },
                ...(allWorkflows?.filter((w) => w.id !== workflowId).map((w) => ({ value: w.id, label: `${w.icon || ''} ${w.name}`.trim() })) ?? []),
              ]}
            />
          )}
          <DashboardReportFields
            blockType={newBlock.type}
            configJson={newBlock.config}
            onConfigChange={(config) => setNewBlock({ ...newBlock, config })}
            idSuffix="-new-block"
          />
          <BlockAiOverrideFields
            blockType={newBlock.type}
            configJson={newBlock.config}
            onConfigChange={(config) => setNewBlock({ ...newBlock, config })}
            workflowProvider={workflow.provider}
            idSuffix="-new-block"
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
          {allSkills.length > 0 && (
            <div>
              <label className="block text-sm font-medium text-text mb-1">{t.workflows.blockSkills}</label>
              <p className="text-xs text-text-tertiary mb-2">{t.workflows.blockSkillsHint}</p>
              <div className="flex flex-wrap gap-1.5">
                {allSkills.map((skill) => (
                  <button
                    key={skill.id}
                    type="button"
                    onClick={() => {
                      const ids = newBlock.skillIds;
                      const next = ids.includes(skill.id)
                        ? ids.filter((id) => id !== skill.id)
                        : [...ids, skill.id];
                      setNewBlock({ ...newBlock, skillIds: next });
                    }}
                    className={`px-2 py-1 rounded-md text-xs border transition-colors ${
                      newBlock.skillIds.includes(skill.id)
                        ? 'bg-accent/10 border-accent text-accent'
                        : 'border-border text-text-tertiary hover:border-accent/50'
                    }`}
                  >
                    {skill.name}
                  </button>
                ))}
              </div>
            </div>
          )}
        </div>
      </Modal>

      <Modal
        open={showEditBlock}
        onClose={() => setShowEditBlock(false)}
        title={`${t.workflows.editBlock} #${editBlockIndex + 1}`}
        size="lg"
        footer={
          <ModalFooter
            onCancel={() => setShowEditBlock(false)}
            onAction={onSaveBlock}
            cancelLabel={t.common.cancel}
            actionLabel={t.common.save}
            actionLoading={updateLoading}
          />
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
            options={blockTypeOptions}
          />
          <Input
            label={t.workflows.blockName}
            value={editBlock.name}
            onChange={(e) => setEditBlock({ ...editBlock, name: e.target.value })}
            placeholder="backend_tests"
            hint={t.workflows.blockNameHint}
          />
          {editBlock.type === 'compress' && (
            <Select
              label={t.workflows.compression.preset}
              value=""
              onChange={(e) => {
                const preset = e.target.value as CompressPresetId;
                if (preset) setEditBlock({ ...editBlock, config: applyPreset(editBlock.config, preset) });
              }}
              options={[
                { value: '', label: t.workflows.compression.presetNone },
                ...(Object.keys(COMPRESS_PRESETS) as CompressPresetId[]).map((id) => ({ value: id, label: t.workflows.compression.presets[id] })),
              ]}
            />
          )}
          {editBlock.type === 'sub-workflow' && (
            <Select
              label={t.workflows.selectSubWorkflow}
              value={(() => { try { return JSON.parse(editBlock.config).workflow_id || ''; } catch { return ''; } })()}
              onChange={(e) => setEditBlock({ ...editBlock, config: JSON.stringify({ workflow_id: e.target.value }, null, 2) })}
              options={[
                { value: '', label: t.common.selectWorkflow },
                ...(allWorkflows?.filter((w) => w.id !== workflowId).map((w) => ({ value: w.id, label: `${w.icon || ''} ${w.name}`.trim() })) ?? []),
              ]}
            />
          )}
          <DashboardReportFields
            blockType={editBlock.type}
            configJson={editBlock.config}
            onConfigChange={(config) => setEditBlock({ ...editBlock, config })}
            idSuffix="-edit-block"
          />
          <BlockAiOverrideFields
            blockType={editBlock.type}
            configJson={editBlock.config}
            onConfigChange={(config) => setEditBlock({ ...editBlock, config })}
            workflowProvider={workflow.provider}
            idSuffix="-edit-block"
          />
          <div>
            <label className="block text-sm font-medium text-text mb-1.5">{t.workflows.blockConfig}</label>
            <MonacoEditor
              value={editBlock.config}
              onChange={(val) => setEditBlock({ ...editBlock, config: val })}
              language="json"
              height="250px"
              revealText={editBlockRevealText}
              revealNonce={editBlockRevealNonce}
            />
          </div>
          {allSkills.length > 0 && (
            <div>
              <label className="block text-sm font-medium text-text mb-1">{t.workflows.blockSkills}</label>
              <p className="text-xs text-text-tertiary mb-2">{t.workflows.blockSkillsHint}</p>
              <div className="flex flex-wrap gap-1.5">
                {allSkills.map((skill) => (
                  <button
                    key={skill.id}
                    type="button"
                    onClick={() => {
                      const ids = editBlock.skillIds;
                      const next = ids.includes(skill.id)
                        ? ids.filter((id) => id !== skill.id)
                        : [...ids, skill.id];
                      setEditBlock({ ...editBlock, skillIds: next });
                    }}
                    className={`px-2 py-1 rounded-md text-xs border transition-colors ${
                      editBlock.skillIds.includes(skill.id)
                        ? 'bg-accent/10 border-accent text-accent'
                        : 'border-border text-text-tertiary hover:border-accent/50'
                    }`}
                  >
                    {skill.name}
                  </button>
                ))}
              </div>
            </div>
          )}
        </div>
      </Modal>

      <Modal
        open={showAddSkill}
        onClose={() => setShowAddSkill(false)}
        title={t.workflows.activateSkill}
        size="sm"
        footer={
          <ModalFooter
            onCancel={() => setShowAddSkill(false)}
            onAction={onActivateSkill}
            cancelLabel={t.common.cancel}
            actionLabel={t.skills.activate}
            actionDisabled={!selectedSkillId}
            actionLoading={activateLoading}
          />
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

      <Modal
        open={showDelete}
        onClose={() => setShowDelete(false)}
        title={`${t.common.delete} "${workflow.name}"?`}
        size="sm"
        footer={
          <ModalFooter
            onCancel={() => setShowDelete(false)}
            onAction={onDelete}
            cancelLabel={t.common.cancel}
            actionLabel={t.common.delete}
            actionVariant="danger"
            actionLoading={deleteLoading}
          />
        }
      >
        <p className="text-sm text-text-secondary">
          {t.common.deleteConfirm} {t.workflows.deleteConfirm}
        </p>
      </Modal>

      <Modal
        open={showClone}
        onClose={() => setShowClone(false)}
        title={t.workflows.cloneWorkflow}
        size="sm"
        footer={
          <>
            <Button variant="secondary" onClick={() => setShowClone(false)}>{t.common.cancel}</Button>
            <Button
              onClick={onClone}
              loading={cloneLoading}
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

      <Modal
        open={showEditInstructions}
        onClose={() => setShowEditInstructions(false)}
        title={t.workflows.editInstructions}
        size="lg"
        footer={
          <ModalFooter
            onCancel={() => setShowEditInstructions(false)}
            onAction={onSaveInstructions}
            cancelLabel={t.common.cancel}
            actionLabel={t.common.save}
            actionLoading={updateLoading}
          />
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

      <Modal
        open={showOptimizeResult}
        onClose={() => setShowOptimizeResult(false)}
        title={t.workflows.optimizeResult}
        size="lg"
        footer={
          <ModalFooter
            onCancel={() => setShowOptimizeResult(false)}
            onAction={onApplyOptimize}
            cancelLabel={t.common.cancel}
            actionLabel={t.workflows.optimizeApply}
            actionLoading={updateLoading}
          />
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
                      {optimizeResult.changes!.map((change, i) => (
                        <li key={i} className="flex items-start gap-2 text-sm">
                          <span className="shrink-0 px-1.5 py-0.5 rounded-sm text-[10px] font-medium bg-accent/10 text-accent">
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
                    <h4 className="text-sm font-medium text-text mb-1">{t.workflows.blocks} ({optimizeResult.blocks!.length})</h4>
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
    </>
  );
}
