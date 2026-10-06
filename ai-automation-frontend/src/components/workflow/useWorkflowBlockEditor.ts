import { useState } from 'react';
import { useUpdateWorkflow } from '../../hooks/useWorkflows';
import { blockFromDraft, draftFromBlock, emptyDraft, type BlockDraft } from './blockEdit';
import type { DiagramSelection } from './bpmnLayout';
import type { BlockDefinition, BlockType, Workflow } from '../../types';

/** Primary config field per block type — the line a top-level click jumps to. */
const PRIMARY_FIELD: Partial<Record<BlockType, string>> = {
  shell: 'command', simple: 'command', retry: 'command',
  agentic: 'goal', approval: 'question', 'human-input': 'prompt',
  'skill-capability': 'capability', 'sub-workflow': 'workflow_id',
  conditional: 'condition', switch: 'expression', foreach: 'variable_name',
  while: 'condition_command', document: 'file_path',
  compress: 'source', 'compress-ai': 'source',
};

/** Determines from a diagram selection the JSON search text the editor should jump to. */
export function revealTextFor(blocks: BlockDefinition[], sel: DiagramSelection): string | undefined {
  const block = blocks[sel.blockIndex];
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
  // Top level: primary field of the block type, if present
  const key = block ? PRIMARY_FIELD[block.type] : undefined;
  return key && key in config ? `"${key}"` : undefined;
}

/**
 * Edit the blocks of the workflow detail page: add (draft in the dialog), edit a
 * block including jump target in the JSON (`reveal`, e.g. after a click in the diagram), save and
 * remove. Invalid JSON saves nothing — the editor shows the error. `workflow` may still be
 * missing (loading); then the actions do nothing.
 */
export function useWorkflowBlockEditor(workflow: Workflow | undefined) {
  const updateWorkflow = useUpdateWorkflow();
  const [showAddBlock, setShowAddBlock] = useState(false);
  const [newBlock, setNewBlock] = useState<BlockDraft>(emptyDraft());
  const [showEditBlock, setShowEditBlock] = useState(false);
  const [editBlockIndex, setEditBlockIndex] = useState<number>(-1);
  const [editBlock, setEditBlock] = useState<BlockDraft>(emptyDraft('simple', '{}'));
  const [reveal, setReveal] = useState<{ text: string; nonce: number } | null>(null);

  const addBlock = () => {
    if (!workflow) return;
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

  const removeBlock = (index: number) => {
    if (!workflow) return;
    const blocks = workflow.blocks.filter((_, i) => i !== index);
    updateWorkflow.mutate({ id: workflow.id, data: { blocks } });
  };

  const openEditBlock = (index: number, revealText?: string) => {
    const block = workflow?.blocks[index];
    if (!block) return;
    setEditBlockIndex(index);
    setEditBlock(draftFromBlock(block));
    setReveal(revealText ? { text: revealText, nonce: Date.now() } : null);
    setShowEditBlock(true);
  };

  const selectFromDiagram = (sel: DiagramSelection) => {
    if (!workflow) return;
    // Every box opens the block editor and jumps to the matching definition line
    openEditBlock(sel.blockIndex, revealTextFor(workflow.blocks, sel));
  };

  const saveBlock = () => {
    if (!workflow) return;
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

  return {
    showAddBlock, setShowAddBlock, newBlock, setNewBlock, addBlock,
    showEditBlock, setShowEditBlock, editBlockIndex, editBlock, setEditBlock, saveBlock, reveal,
    openEditBlock, removeBlock, selectFromDiagram,
    saving: updateWorkflow.isPending,
  };
}
