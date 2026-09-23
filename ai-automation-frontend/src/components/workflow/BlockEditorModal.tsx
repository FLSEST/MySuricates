import { useMemo } from 'react';
import { Input, Select, Modal, ModalFooter } from '../ui';
import { MonacoEditor } from '../shared/MonacoEditor';
import { BlockAiOverrideFields } from './BlockAiOverrideFields';
import { DashboardReportFields } from './DashboardReportFields';
import { BlockSkillPicker } from './BlockSkillPicker';
import { BLOCK_DEFAULT_CONFIG, buildBlockConfigSchema } from './blockConfigSchema';
import { buildPlaceholderContext } from './placeholderContext';
import type { BlockDraft } from './blockEdit';
import { applyPreset, COMPRESS_PRESETS, type CompressPresetId } from './compression';
import { useI18nStore, useT } from '../../i18n';
import { useBlockTypes } from '../../hooks/useBlockTypes';
import type { BlockType, Workflow } from '../../types';

export interface SkillOption {
  id: string;
  name: string;
}

export interface WorkflowOption {
  id: string;
  name: string;
  icon: string;
}

interface BlockEditorModalProps {
  /** `add` appends a new block, `edit` changes the block at {@link position}. */
  mode: 'add' | 'edit';
  open: boolean;
  onClose: () => void;
  workflow: Workflow;
  workflowId: string;
  /** Where the block sits — decides which {{ }} are bound (a new block goes to the end). */
  position: number;
  block: BlockDraft;
  setBlock: (v: BlockDraft) => void;
  onSave: () => void;
  saving: boolean;
  allWorkflows: WorkflowOption[] | undefined;
  allSkills: SkillOption[];
  revealText?: string;
  revealNonce?: number;
}

/** Add or edit one workflow block: type, name, type-specific fields, JSON config, skills. */
export function BlockEditorModal({
  mode,
  open,
  onClose,
  workflow,
  workflowId,
  position,
  block,
  setBlock,
  onSave,
  saving,
  allWorkflows,
  allSkills,
  revealText,
  revealNonce,
}: BlockEditorModalProps) {
  const t = useT();
  const locale = useI18nStore((state) => state.locale);
  const isNew = mode === 'add';
  const idSuffix = isNew ? '-new-block' : '-edit-block';
  // Stable per type/locale: a new object would re-register the schema on every keystroke
  const schema = useMemo(() => buildBlockConfigSchema(block.type, locale), [block.type, locale]);
  const placeholders = useMemo(
    () => buildPlaceholderContext(workflow.blocks, position, { type: block.type, configJson: block.config }, { defaultContext: workflow.defaultContext, locale }),
    [workflow.blocks, workflow.defaultContext, position, block.type, block.config, locale],
  );
  // What THIS backend can execute (BlockTypeRegistry); the static list is only the fallback
  const blockTypes = useBlockTypes();
  // A type only the server knows has no icon and no label in this build — offer its raw name
  const blockTypeOptions = blockTypes.map((type) => ({
    value: type,
    label: t.workflows.blockTypes[type as BlockType] ?? type,
  }));

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={isNew ? t.workflows.addBlock : `${t.workflows.editBlock} #${position + 1}`}
      size="lg"
      resizable
      footer={
        <ModalFooter
          onCancel={onClose}
          onAction={onSave}
          cancelLabel={t.common.cancel}
          actionLabel={isNew ? t.workflows.addBlock : t.common.save}
          actionLoading={saving}
        />
      }
    >
      <div className="flex flex-col gap-4 flex-1">
        <Select
          label={t.workflows.blockType}
          value={block.type}
          onChange={(e) => {
            const type = e.target.value as BlockType;
            // A new block starts from the type's defaults (a type only the server knows: empty);
            // an edited block keeps its config
            setBlock(isNew
              ? { ...block, type, config: JSON.stringify(BLOCK_DEFAULT_CONFIG[type] ?? {}, null, 2) }
              : { ...block, type });
          }}
          options={blockTypeOptions}
        />
        <Input
          label={t.workflows.blockName}
          value={block.name}
          onChange={(e) => setBlock({ ...block, name: e.target.value })}
          placeholder="backend_tests"
          hint={t.workflows.blockNameHint}
        />
        {block.type === 'compress' && (
          <Select
            label={t.workflows.compression.preset}
            value=""
            onChange={(e) => {
              const preset = e.target.value as CompressPresetId;
              if (preset) setBlock({ ...block, config: applyPreset(block.config, preset) });
            }}
            options={[
              { value: '', label: t.workflows.compression.presetNone },
              ...(Object.keys(COMPRESS_PRESETS) as CompressPresetId[]).map((id) => ({ value: id, label: t.workflows.compression.presets[id] })),
            ]}
          />
        )}
        {block.type === 'sub-workflow' && (
          <Select
            label={t.workflows.selectSubWorkflow}
            value={(() => { try { return JSON.parse(block.config).workflow_id || ''; } catch { return ''; } })()}
            onChange={(e) => setBlock({ ...block, config: JSON.stringify({ workflow_id: e.target.value }, null, 2) })}
            options={[
              { value: '', label: t.common.selectWorkflow },
              ...(allWorkflows?.filter((w) => w.id !== workflowId).map((w) => ({ value: w.id, label: `${w.icon || ''} ${w.name}`.trim() })) ?? []),
            ]}
          />
        )}
        <DashboardReportFields
          blockType={block.type}
          configJson={block.config}
          onConfigChange={(config) => setBlock({ ...block, config })}
          idSuffix={idSuffix}
          workflowId={workflowId}
        />
        <BlockAiOverrideFields
          blockType={block.type}
          configJson={block.config}
          onConfigChange={(config) => setBlock({ ...block, config })}
          workflowProvider={workflow.provider}
          idSuffix={idSuffix}
        />
        <div className={`flex flex-col flex-1 ${isNew ? 'min-h-[280px]' : 'min-h-[360px]'}`}>
          <label className="block text-sm font-medium text-text mb-1.5">{t.workflows.blockConfig}</label>
          <MonacoEditor
            value={block.config}
            onChange={(val) => setBlock({ ...block, config: val })}
            language="json"
            jsonSchema={schema}
            placeholders={placeholders}
            fill
            revealText={revealText}
            revealNonce={revealNonce}
          />
        </div>
        <BlockSkillPicker
          blockType={block.type}
          configJson={block.config}
          skills={allSkills}
          selected={block.skillIds}
          onChange={(skillIds) => setBlock({ ...block, skillIds })}
        />
      </div>
    </Modal>
  );
}
