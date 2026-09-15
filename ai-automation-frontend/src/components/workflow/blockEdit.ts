import type { BlockDefinition, BlockType } from '../../types';

/** Editable form of a block in the add/edit modals; config stays JSON text for the Monaco editor. */
export interface BlockDraft {
  type: BlockType;
  name: string;
  config: string;
  skillIds: string[];
}

export function emptyDraft(type: BlockType = 'simple', config = JSON.stringify({ command: '' }, null, 2)): BlockDraft {
  return { type, name: '', config, skillIds: [] };
}

export function draftFromBlock(block: BlockDefinition): BlockDraft {
  return {
    type: block.type,
    name: block.name ?? '',
    config: JSON.stringify(block.config, null, 2),
    skillIds: block.skillIds ?? [],
  };
}

/**
 * Block to save. Keeps every field of the previous block the modal does not edit (requiredCapabilities,
 * future keys) — dropping the name broke {{ block.<name>.output }} references (TODO 71233e70).
 * Throws on invalid JSON.
 */
export function blockFromDraft(draft: BlockDraft, previous?: BlockDefinition): BlockDefinition {
  const config = JSON.parse(draft.config) as Record<string, unknown>;
  const kept: Partial<BlockDefinition> = { ...(previous ?? {}) };
  delete kept.name;
  delete kept.skillIds;
  const name = draft.name.trim();
  return {
    ...kept,
    type: draft.type,
    ...(name ? { name } : {}),
    config,
    ...(draft.skillIds.length > 0 ? { skillIds: draft.skillIds } : {}),
  };
}
