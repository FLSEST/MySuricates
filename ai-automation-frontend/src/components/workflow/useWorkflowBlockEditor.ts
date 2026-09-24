import { useState } from 'react';
import { useUpdateWorkflow } from '../../hooks/useWorkflows';
import { blockFromDraft, draftFromBlock, emptyDraft, type BlockDraft } from './blockEdit';
import type { DiagramSelection } from './bpmnLayout';
import type { BlockDefinition, BlockType, Workflow } from '../../types';

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
  // Top-Level: primäres Feld des Block-Typs, falls vorhanden
  const key = block ? PRIMARY_FIELD[block.type] : undefined;
  return key && key in config ? `"${key}"` : undefined;
}

/**
 * Blöcke der Workflow-Detailseite bearbeiten: Hinzufügen (Entwurf im Dialog), Bearbeiten eines
 * Blocks samt Sprungziel im JSON (`reveal`, etwa nach einem Klick im Diagramm), Speichern und
 * Entfernen. Ungültiges JSON speichert nichts — der Editor zeigt den Fehler. `workflow` darf noch
 * fehlen (Laden); dann tun die Aktionen nichts.
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
    // Jeder Kasten öffnet den Block-Editor und springt zur passenden Definitionszeile
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
