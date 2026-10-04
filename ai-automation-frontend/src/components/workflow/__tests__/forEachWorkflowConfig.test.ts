import { describe, it, expect } from 'vitest';
import { forEachWorkflowId, withForEachWorkflow } from '../forEachWorkflowConfig';

// a5c9fbbf: foreach runs a workflow per item instead of its command
describe('ForEachWorkflowField helpers', () => {
  const base = JSON.stringify({ items_source: 'command', items_command: 'ls', command: 'echo {{ item }}', variable_name: 'item' });

  it('choosing a workflow sets workflow_id, drops the command and keeps the rest', () => {
    const next = JSON.parse(withForEachWorkflow(base, 'wf-1'));
    expect(next).toEqual({ items_source: 'command', items_command: 'ls', variable_name: 'item', workflow_id: 'wf-1' });
    expect(forEachWorkflowId(JSON.stringify(next))).toBe('wf-1');
  });

  it('clearing it removes workflow_id and brings back an empty command', () => {
    const next = JSON.parse(withForEachWorkflow(withForEachWorkflow(base, 'wf-1'), ''));
    expect(next.workflow_id).toBeUndefined();
    expect(next.command).toBe('');
  });

  it('leaves config text that is no JSON object untouched', () => {
    expect(withForEachWorkflow('{ broken', 'wf-1')).toBe('{ broken');
    expect(withForEachWorkflow('[]', 'wf-1')).toBe('[]');
    expect(forEachWorkflowId('{ broken')).toBe('');
  });
});
