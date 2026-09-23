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

/** Block types that always make an AI call in the project directory. */
const SKILL_BLOCK_TYPES: readonly string[] = ['simple', 'agentic', 'skill-capability'];
/** Wrapper blocks: AI calls unless `execution_mode` is `shell`. */
const MODE_BLOCK_TYPES: readonly string[] = ['retry', 'foreach', 'while', 'conditional', 'switch'];

/**
 * Whether per-block skills change anything for this block. Skills reach a block only through
 * `.claude/rules/skills-context.md`, which only AI calls in the project directory read
 * (BlockSkillContext) — a shell, approval or todo block ignores them, and `compress-ai` runs in its own
 * temp directory. `config` may be the editor's JSON text; unparseable text counts as the default mode.
 */
export function blockUsesSkills(type: string, config: Record<string, unknown> | string): boolean {
  if (SKILL_BLOCK_TYPES.includes(type)) return true;
  if (!MODE_BLOCK_TYPES.includes(type)) return false;
  let parsed: unknown = config;
  if (typeof config === 'string') {
    try { parsed = JSON.parse(config); } catch { return true; }
  }
  const mode = parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>).execution_mode : undefined;
  return mode !== 'shell';
}

/** Block types whose `config.description` is content (the TODO text), not a note on the step. */
const DESCRIPTION_IS_CONTENT = new Set(['todo-create', 'todo-update']);

/**
 * The note on a step (`config.description`, a common key without effect on execution) — what the
 * editor shows under the block, e.g. the "replace this with …" hints of the blueprints (TODO 31b7aca8).
 */
export function blockNote(block: { type: string; config?: Record<string, unknown> }): string | null {
  if (DESCRIPTION_IS_CONTENT.has(block.type)) return null;
  const note = block.config?.description;
  return typeof note === 'string' && note.trim() ? note : null;
}
