import { describe, it, expect } from 'vitest';
import type { BlockDefinition } from '../types';
import { buildPlaceholderContext, checkPlaceholders } from './placeholderContext';

const blocks: BlockDefinition[] = [
  { type: 'shell', name: 'backend_tests', config: { command: 'mvn test' } },
  { type: 'approval', config: { question: 'Weiter?', variable_name: 'go' } },
  { type: 'simple', config: { command: 'Fasse zusammen: {{ previous_output }} für {{ ticket }}' } },
  { type: 'shell', name: 'report', config: { command: 'echo done' } },
];

const ctxAt = (index: number, current: { type: string; configJson?: string } = { type: 'simple' }, locale: 'de' | 'en' = 'en') =>
  buildPlaceholderContext(blocks, index, { type: current.type, configJson: current.configJson ?? '{}' }, { defaultContext: { lang: 'de' }, locale });

const keys = (index: number) => ctxAt(index).items.map((i) => i.key);

describe('buildPlaceholderContext', () => {
  it('ties previous_output to the block right before', () => {
    const prev = ctxAt(2).items.find((i) => i.key === 'previous_output')!;
    expect(prev.source).toBe('#2 approval');
    expect(ctxAt(1).items.find((i) => i.key === 'previous_output')!.source).toBe('#1 backend_tests');
    expect(ctxAt(0).items.find((i) => i.key === 'previous_output')!.source).toBeUndefined();
  });

  it('offers only outputs and variables of earlier blocks', () => {
    expect(keys(2)).toEqual(expect.arrayContaining(['block.backend_tests.output', 'step_0_output', 'step_1_output', 'go']));
    expect(keys(2)).not.toContain('block.report.output');
    expect(keys(2)).not.toContain('step_2_output');
    expect(keys(1)).not.toContain('go');
  });

  it('adds system variables, start values and inputs other blocks ask for', () => {
    const items = ctxAt(3).items;
    expect(items.find((i) => i.key === 'workflow_run_id')?.kind).toBe('system');
    expect(items.find((i) => i.key === 'lang')?.kind).toBe('input');
    expect(items.find((i) => i.key === 'ticket')?.kind).toBe('input');
  });

  it('stops mapping step numbers once a loop runs several steps', () => {
    const withLoop: BlockDefinition[] = [
      { type: 'shell', config: { command: 'ls' } },
      { type: 'foreach', config: { items: ['a'], command: 'x' } },
      { type: 'shell', config: { command: 'y' } },
    ];
    const ctx = buildPlaceholderContext(withLoop, 3, { type: 'simple', configJson: '{}' }, { locale: 'en' });
    const k = ctx.items.map((i) => i.key);
    expect(k).toContain('step_0_output');
    expect(k).not.toContain('step_2_output');
    expect(k).toContain('item');
    expect(ctx.ambiguousSteps).toBe(true);
  });

  it('binds the loop variable of the edited foreach and knows its shell mode', () => {
    const ctx = ctxAt(4, { type: 'foreach', configJson: '{"variable_name": "repo", "execution_mode": "shell"}' });
    expect(ctx.items.find((i) => i.key === 'repo')).toBeDefined();
    expect(ctx.shell).toBe(true);
    expect(ctxAt(4, { type: 'foreach' }).shell).toBe(false);
    expect(ctxAt(4, { type: 'shell' }).shell).toBe(true);
  });
});

describe('checkPlaceholders', () => {
  const marks = (text: string, index = 2, type = 'simple') => checkPlaceholders(text, ctxAt(index, { type, configJson: text }));

  it('shows where a placeholder comes from', () => {
    const [m] = marks('{"command": "{{ previous_output }}"}');
    expect(m!.hint).toBe('← #2 approval');
    expect(m!.problem).toBeUndefined();
    expect(marks('{"command": "{{ block.backend_tests.output }}"}')[0]!.hint).toBe('← #1 backend_tests');
  });

  it('warns about references to later or missing blocks', () => {
    expect(marks('{"command": "{{ block.report.output }}"}')[0]!.problem?.message).toMatch(/runs after/);
    expect(marks('{"command": "{{ block.nope.output }}"}')[0]!.problem?.message).toMatch(/No block `nope`/);
    expect(marks('{"command": "{{ step_3_output }}"}')[0]!.problem?.severity).toBe('warning');
  });

  it('flags spacing the backend does not resolve', () => {
    expect(marks('{"command": "{{ previous_output}}"}')[0]!.problem?.message).toMatch(/Will not resolve/);
    expect(marks('{"command": "{{previous_output}}"}')[0]!.problem).toBeUndefined();
  });

  it('notes that previous_output is empty in the first block', () => {
    expect(marks('{"command": "{{ previous_output }}"}', 0)[0]!.problem?.severity).toBe('info');
  });

  it('warns about quoted output placeholders in shell commands and asks for the escape option', () => {
    expect(marks(`{"command": "X='{{ previous_output }}'", "shell_escape_variables": true}`, 2, 'shell')[0]!.problem?.message).toMatch(/Do not quote/);
    expect(marks('{"command": "X=\\"{{ previous_output }}\\""}', 2, 'shell')[0]!.problem?.message).toMatch(/Do not quote/);
    expect(marks('{"command": "set -- {{ previous_output }}"}', 2, 'shell')[0]!.problem?.message).toMatch(/shell_escape_variables/);
    expect(marks('{"command": "set -- {{ previous_output }}", "shell_escape_variables": true}', 2, 'shell')[0]!.problem).toBeUndefined();
    // A JSON string that starts with the placeholder is not a shell quote
    expect(marks('{"command": "{{ previous_output }}", "shell_escape_variables": true}', 2, 'shell')[0]!.problem).toBeUndefined();
    // Platform data is always escaped by the backend
    expect(marks('{"command": "R={{ open_todos }}"}', 2, 'shell')[0]!.problem).toBeUndefined();
  });

  it('speaks German when asked', () => {
    const ctx = ctxAt(2, { type: 'simple' }, 'de');
    expect(checkPlaceholders('{{ block.nope.output }}', ctx)[0]!.problem?.message).toMatch(/Keinen Block/);
  });
});
