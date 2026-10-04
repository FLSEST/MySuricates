import { describe, it, expect } from 'vitest';
import { getExecutionModes } from '../executionModes';
import type { BlockDefinition } from '../../../types';

const block = (type: string, config: Record<string, unknown> = {}) => ({ type, config }) as unknown as BlockDefinition;
const switchWith = (caseBlock: Record<string, unknown>) =>
  block('switch', { expression: '{{ previous_output }}', cases: [{ value: 'a', blocks: [caseBlock] }] });

/** Same cases as the backend's ModelUseTest: the badge and the picker's "runs without AI" filter must agree. */
describe('getExecutionModes', () => {
  it('lists shell, AI and approval in first-seen order', () => {
    expect(getExecutionModes([block('approval'), block('shell'), block('simple')])).toEqual(['approval', 'shell', 'ai']);
  });

  it('counts every model block type as AI', () => {
    for (const type of ['simple', 'agentic', 'skill-capability', 'compress-ai']) {
      expect(getExecutionModes([block(type)]), type).toEqual(['ai']);
    }
  });

  it('asks the AI in every command wrapper unless it runs as shell', () => {
    for (const type of ['foreach', 'while', 'conditional', 'retry']) {
      expect(getExecutionModes([block(type, { command: 'x' })]), type).toEqual(['ai']);
      expect(getExecutionModes([block(type, { execution_mode: 'ai' })]), type).toEqual(['ai']);
      expect(getExecutionModes([block(type, { execution_mode: 'shell' })]), type).toEqual(['shell']);
    }
  });

  it('reads a switch through the blocks of its cases and default', () => {
    expect(getExecutionModes([switchWith({ type: 'shell', config: { command: 'echo a' } })])).toEqual(['shell']);
    expect(getExecutionModes([switchWith({ type: 'simple', config: { command: 'Summarize' } })])).toEqual(['ai']);
    expect(getExecutionModes([switchWith({ type: 'simple', config: { command: 'date', execution_mode: 'shell' } })]))
      .toEqual(['shell']);
    expect(getExecutionModes([block('switch', { expression: 'x', default: [{ type: 'simple', config: {} }] })]))
      .toEqual(['ai']);
  });

  it('counts a sub-workflow as AI, since the workflow it starts is not part of the definition', () => {
    expect(getExecutionModes([block('sub-workflow', { workflow_id: 'w-1' })])).toEqual(['ai']);
  });

  // 093d2f11: a blueprint package before installation names its part, whose cost the backend gate pins
  it('judges a sub-workflow that still names a shipped blueprint by that blueprint, like ModelUse', () => {
    expect(getExecutionModes([block('sub-workflow', { workflow_id: 'blueprint-sub-workflow-part' })])).toEqual([]);
    expect(getExecutionModes([block('sub-workflow', {})])).toEqual(['ai']);
  });

  it('shows nothing for purely deterministic non-shell blocks', () => {
    expect(getExecutionModes([
      block('integration', { provider: 'github' }), block('todo-create', { title: 'x' }), block('document'),
      block('compress'), block('human-input'), block('dashboard-report'),
    ])).toEqual([]);
  });
});
