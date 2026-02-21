import type { BlockDefinition } from '../types';

const VARIABLE_PATTERN = /\{\{\s*(\w+)\s*\}\}/g;
const SYSTEM_VARIABLES = new Set(['previous_output']);

/**
 * Scans all block configs for {{ variable }} patterns.
 * Returns a deduplicated list of variable names, excluding system variables.
 */
export function extractWorkflowVariables(blocks: BlockDefinition[]): string[] {
  const variables = new Set<string>();

  for (const block of blocks) {
    scanObject(block.config, variables);
  }

  // Remove system variables
  for (const sysVar of SYSTEM_VARIABLES) {
    variables.delete(sysVar);
  }

  // Remove forEach loop variables — these are bound by the block, not user input
  for (const block of blocks) {
    if (block.type === 'foreach' && typeof block.config?.variable_name === 'string') {
      variables.delete(block.config.variable_name);
    }
  }

  return Array.from(variables);
}

function scanObject(obj: unknown, variables: Set<string>): void {
  if (typeof obj === 'string') {
    let match: RegExpExecArray | null;
    VARIABLE_PATTERN.lastIndex = 0;
    while ((match = VARIABLE_PATTERN.exec(obj)) !== null) {
      if (match[1]) variables.add(match[1]);
    }
  } else if (Array.isArray(obj)) {
    for (const item of obj) {
      scanObject(item, variables);
    }
  } else if (obj !== null && typeof obj === 'object') {
    for (const value of Object.values(obj)) {
      scanObject(value, variables);
    }
  }
}
