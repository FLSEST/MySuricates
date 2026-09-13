import type { BlockDefinition } from '../../types';

export type ExecutionMode = 'shell' | 'ai' | 'approval';

/** Which execution modes (shell / AI / approval) a block list uses, in first-seen order. */
export function getExecutionModes(blocks: BlockDefinition[]): ExecutionMode[] {
  const modes = new Set<ExecutionMode>();
  for (const b of blocks) {
    const t = b.type;
    if (t === 'shell') modes.add('shell');
    else if (t === 'simple' || t === 'agentic' || t === 'skill-capability') modes.add('ai');
    else if (t === 'approval') modes.add('approval');
    else if (t === 'foreach' || t === 'while') {
      const mode = (b.config as Record<string, unknown>).execution_mode;
      if (mode === 'shell') modes.add('shell');
      else modes.add('ai');
    }
  }
  return [...modes];
}
