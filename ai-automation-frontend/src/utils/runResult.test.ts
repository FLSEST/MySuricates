import { describe, it, expect } from 'vitest';
import type { StepResult, WorkflowRun } from '../types';
import {
  createdTodoCount, extractPaths, failedStep, pickResultStep, readRunReport, reportInvalid,
  runErrorText, stepDisplayName, stripMarkerLines, MAX_RESULT_PATHS,
} from './runResult';

function step(stepIndex: number, overrides: Partial<StepResult> = {}): StepResult {
  return {
    id: `s${stepIndex}`,
    stepIndex,
    blockType: 'shell',
    input: '',
    output: `output ${stepIndex}`,
    status: 'COMPLETED',
    durationMs: 10,
    error: null,
    metadata: { blockIndex: stepIndex },
    ...overrides,
  };
}

const T = {
  simple: { result: { errorFallback: 'No reason given.' } },
  workflows: { blockTypes: { shell: 'Shell Command' } as Record<string, string>, execution: { stepNumber: 'Step {n}' } },
};

const REPORT = { title: 'Nightly', status: 'YELLOW', kpis: [{ label: 'Errors', value: '3' }], items: ['one'] };

describe('readRunReport / reportInvalid', () => {
  it('returns the validated contract', () => {
    const run = { metadata: { dashboard_widget: REPORT } };
    expect(readRunReport(run)?.title).toBe('Nightly');
    expect(reportInvalid(run)).toBe(false);
  });

  it('returns null and flags an invalid contract', () => {
    const run = { metadata: { dashboard_widget: { status: 'PURPLE' } } };
    expect(readRunReport(run)).toBeNull();
    expect(reportInvalid(run)).toBe(true);
  });

  it('flags the backend INVALID token', () => {
    expect(reportInvalid({ metadata: { dashboard_widget_error: 'INVALID' } })).toBe(true);
  });

  it('is neither report nor invalid without a marker', () => {
    expect(readRunReport({ metadata: {} })).toBeNull();
    expect(reportInvalid({ metadata: {} })).toBe(false);
  });
});

describe('pickResultStep', () => {
  it('picks the last completed step with output in run order', () => {
    expect(pickResultStep([step(0), step(1), step(2)])?.id).toBe('s2');
  });

  it('skips failed, empty, derived and bookkeeping steps', () => {
    const steps = [
      step(0, { output: 'the real result' }),
      step(1, { status: 'FAILED' }),
      step(2, { output: '   ' }),
      step(3, { metadata: { blockIndex: 3, derivedOutput: true } }),
      step(4, { blockType: 'approval' }),
      step(5, { blockType: 'human-input' }),
      step(6, { blockType: 'todo-create' }),
      step(7, { blockType: 'todo-update' }),
      step(8, { blockType: 'todo-close' }),
      step(9, { blockType: 'dashboard-report' }),
      step(10, { blockType: 'compress' }),
      step(11, { blockType: undefined, metadata: { blockIndex: 11, blockType: 'compress-ai' } }),
    ];
    expect(pickResultStep(steps)?.id).toBe('s0');
  });

  it('skips a step whose output is only markers', () => {
    const steps = [step(0, { output: 'report text' }), step(1, { output: 'gate_status: GREEN\ntodo_item: x | HIGH | a' })];
    expect(pickResultStep(steps)?.id).toBe('s0');
  });

  it('uses the newest attempt of a resumed block', () => {
    const steps = [
      step(0, { output: 'old attempt', metadata: { blockIndex: 0 } }),
      step(1, { output: '', status: 'FAILED', metadata: { blockIndex: 1 } }),
      step(2, { id: 'retry', output: 'new attempt', metadata: { blockIndex: 1 } }),
    ];
    expect(pickResultStep(steps)?.id).toBe('retry');
  });

  it('does not fall back to an older attempt when the newest one failed', () => {
    const steps = [
      step(0, { output: 'first block' }),
      step(1, { output: 'old attempt', metadata: { blockIndex: 1 } }),
      step(2, { status: 'FAILED', output: '', metadata: { blockIndex: 1 } }),
    ];
    expect(pickResultStep(steps)?.id).toBe('s0');
  });

  it('returns null when nothing qualifies', () => {
    expect(pickResultStep([])).toBeNull();
    expect(pickResultStep([step(0, { blockType: 'approval' })])).toBeNull();
  });
});

describe('stripMarkerLines', () => {
  it('removes every marker kind and keeps the prose', () => {
    const text = [
      '# Report',
      'All good.',
      'gate_status: GREEN',
      'gate_category: quality',
      'gate_description: nothing found',
      'todo_item: Fix it | HIGH | backend | details',
      'dashboard_widget: {"status":"GREEN"}',
      'Done.',
    ].join('\n');
    expect(stripMarkerLines(text)).toBe('# Report\nAll good.\nDone.');
  });

  it.each([
    '**gate_status:** RED',
    '### Gate-Status: YELLOW',
    '`gate_status`: GREEN',
    'GATE_STATUS: green',
    '- todo-item: Title | LOW | tag',
    '1. todo_item: Title | LOW | tag',
    '> dashboard-widget: {"status":"RED"}',
    '[item-a]: gate_status: RED',
    '  **Todo_Item**: Title | MEDIUM | tag',
  ])('removes the variant %s', (line) => {
    expect(stripMarkerLines(`before\n${line}\nafter`)).toBe('before\nafter');
  });

  it('removes a multi-line dashboard_widget JSON value with its marker', () => {
    const text = 'Summary\ndashboard_widget: {\n  "status": "GREEN",\n  "items": ["a } b", "c"]\n}\nTail';
    expect(stripMarkerLines(text)).toBe('Summary\nTail');
  });

  it('keeps lines that merely mention a marker word', () => {
    const text = 'The gate_status field is set later.\nSee todo_item docs';
    expect(stripMarkerLines(text)).toBe(text);
  });

  it('collapses the blank lines left behind and trims', () => {
    expect(stripMarkerLines('\nA\n\ngate_status: GREEN\n\nB\n')).toBe('A\n\nB');
  });
});

function run(overrides: Partial<WorkflowRun>): Pick<WorkflowRun, 'error' | 'steps'> {
  return { error: null, steps: [], ...overrides };
}

describe('runErrorText', () => {
  it('uses the run error message', () => {
    expect(runErrorText(run({ error: { message: 'Timeout after 300 s' } }), T)).toBe('Timeout after 300 s');
  });

  it('falls back to the failed step error, never to JSON', () => {
    const r = run({
      error: { type: 'EXECUTION', details: { nested: true } },
      steps: [step(0), step(1, { status: 'FAILED', error: 'exit code 2' })],
    });
    expect(runErrorText(r, T)).toBe('exit code 2');
  });

  it('prefers the step named by error.stepIndex', () => {
    const r = run({
      error: { stepIndex: 0 },
      steps: [step(0, { status: 'FAILED', error: 'first' }), step(1, { status: 'FAILED', error: 'second' })],
    });
    expect(runErrorText(r, T)).toBe('first');
    expect(failedStep(r)?.id).toBe('s0');
  });

  it('ends with the generic text', () => {
    expect(runErrorText(run({ error: { message: { code: 1 } as unknown as string } }), T)).toBe('No reason given.');
    expect(runErrorText(run({}), T)).toBe('No reason given.');
  });
});

describe('stepDisplayName', () => {
  it('prefers block name, then block type, then the step number', () => {
    expect(stepDisplayName(step(0, { blockName: 'Fetch data' }), T)).toBe('Fetch data');
    expect(stepDisplayName(step(1), T)).toBe('Shell Command');
    expect(stepDisplayName(step(2, { blockType: undefined, metadata: {} }), T)).toBe('Step 3');
  });
});

describe('extractPaths', () => {
  it('finds absolute paths, unique and in order, without sentence dots', () => {
    const text = 'Wrote /tmp/out/report.md. Also /home/me/data.csv and /tmp/out/report.md again.';
    expect(extractPaths(text)).toEqual(['/tmp/out/report.md', '/home/me/data.csv']);
  });

  it('ignores URLs, home-relative and relative paths', () => {
    expect(extractPaths('https://example.com/a/b ~/notes/x.md src/app/main.ts')).toEqual([]);
  });

  it('caps the list', () => {
    const text = Array.from({ length: 15 }, (_, i) => `/tmp/f/${i}.txt`).join(' ');
    expect(extractPaths(text)).toHaveLength(MAX_RESULT_PATHS);
  });
});

describe('createdTodoCount', () => {
  it('counts todo_items', () => {
    expect(createdTodoCount({ metadata: { todo_items: [{}, {}] } })).toBe(2);
    expect(createdTodoCount({ metadata: {} })).toBe(0);
  });
});
