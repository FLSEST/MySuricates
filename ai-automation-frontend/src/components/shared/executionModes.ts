import type { BlockDefinition } from '../../types';
import { BLUEPRINT_PREFIX } from '../../utils/templateUtils';

export type ExecutionMode = 'shell' | 'ai' | 'approval';

/** Block types that always call a model. */
const MODEL_TYPES = new Set(['simple', 'agentic', 'skill-capability', 'compress-ai']);
/** Run their command through the AI unless `execution_mode: shell` says otherwise. */
const COMMAND_WRAPPERS = new Set(['foreach', 'while', 'conditional', 'retry']);

type RawBlock = { type?: unknown; config?: unknown };

function configOf(block: RawBlock): Record<string, unknown> {
  return block.config && typeof block.config === 'object' ? (block.config as Record<string, unknown>) : {};
}

function runsAsShell(config: Record<string, unknown>): boolean {
  return config.execution_mode === 'shell';
}

/** The blocks a switch can run: its cases and its default (`SwitchBlockExecutor`). */
function caseBlocks(config: Record<string, unknown>): RawBlock[] {
  const cases = Array.isArray(config.cases) ? config.cases : [];
  const fromCases = cases.flatMap((c) => (c && typeof c === 'object' && Array.isArray((c as { blocks?: unknown }).blocks)
    ? (c as { blocks: RawBlock[] }).blocks
    : []));
  return [...fromCases, ...(Array.isArray(config.default) ? (config.default as RawBlock[]) : [])];
}

/**
 * Which execution modes (shell / AI / approval) a block list uses, in first-seen order.
 *
 * AI follows the backend's `ModelUse` rule, which also decides a template's `needsAi`: the model
 * block types always; foreach, while, conditional and retry unless they run as shell; a switch
 * through a `simple` case block that does not run as shell; a sub-workflow, because the workflow
 * it starts is not part of this definition — unless it still names a shipped blueprint (a blueprint
 * package before installation, TODO 093d2f11), whose cost `BlueprintGateTest` pins.
 */
export function getExecutionModes(blocks: BlockDefinition[]): ExecutionMode[] {
  const modes = new Set<ExecutionMode>();
  for (const b of blocks) {
    const t = b.type;
    const config = configOf(b);
    if (t === 'shell') modes.add('shell');
    else if (MODEL_TYPES.has(t)) modes.add('ai');
    else if (t === 'sub-workflow') {
      const id = config.workflow_id;
      if (!(typeof id === 'string' && id.startsWith(BLUEPRINT_PREFIX))) modes.add('ai');
    }
    else if (t === 'approval') modes.add('approval');
    else if (COMMAND_WRAPPERS.has(t)) modes.add(runsAsShell(config) ? 'shell' : 'ai');
    else if (t === 'switch') {
      for (const nested of caseBlocks(config)) {
        if (nested?.type === 'shell') modes.add('shell');
        else if (nested?.type === 'simple') modes.add(runsAsShell(configOf(nested)) ? 'shell' : 'ai');
      }
    }
  }
  return [...modes];
}
