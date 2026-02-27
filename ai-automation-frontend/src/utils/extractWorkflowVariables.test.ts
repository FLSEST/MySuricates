import { describe, it, expect } from 'vitest';
import { extractWorkflowVariables } from './extractWorkflowVariables';
import type { BlockDefinition } from '../types';

function block(config: Record<string, unknown>): BlockDefinition {
  return { type: 'simple', config } as BlockDefinition;
}

describe('extractWorkflowVariables', () => {
  it('extracts variables from block config strings', () => {
    const blocks = [block({ prompt: 'Review {{ repo_path }} for {{ language }}' })];
    expect(extractWorkflowVariables(blocks).sort()).toEqual(['language', 'repo_path']);
  });

  it('excludes system variable previous_output', () => {
    const blocks = [block({ prompt: '{{ previous_output }} and {{ user_input }}' })];
    expect(extractWorkflowVariables(blocks)).toEqual(['user_input']);
  });

  it('deduplicates across blocks', () => {
    const blocks = [
      block({ prompt: '{{ name }}' }),
      block({ prompt: '{{ name }} again' }),
    ];
    expect(extractWorkflowVariables(blocks)).toEqual(['name']);
  });

  it('returns empty for no variables', () => {
    const blocks = [block({ prompt: 'Just a plain prompt' })];
    expect(extractWorkflowVariables(blocks)).toEqual([]);
  });

  it('excludes forEach loop variables', () => {
    const blocks = [{
      type: 'foreach',
      config: { variable_name: 'item', collection: '{{ items }}', prompt: '{{ item }}' },
    } as unknown as BlockDefinition];
    expect(extractWorkflowVariables(blocks)).toEqual(['items']);
  });
});
