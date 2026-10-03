import { describe, it, expect } from 'vitest';
import {
  appendCapped, createStepLogContext, eventToEntry, liveLinesToEntries, skippedMarker,
} from '../executionLogEntries';
import type { WsMessage } from '../../types/websocket';

const NOW = '2026-09-14T10:00:00.000Z';

function map(data: Record<string, unknown>, ctx = createStepLogContext()) {
  return eventToEntry(data as unknown as WsMessage, 'evt-1', ctx, NOW);
}

describe('eventToEntry', () => {
  it('keeps the existing forEach, agentic, approval and status texts', () => {
    expect(map({ type: 'foreach_item_completed', stepIndex: 1, itemIndex: 1, item: 'b', totalItems: 4, status: 'FAILED', output: '' }))
      .toMatchObject({ level: 'ERROR', message: '[forEach] 2/4 (50%) [FAILED]: b' });
    expect(map({ type: 'agentic_acted', stepIndex: 0, iteration: 2, action: 'x', output: '', exitCode: 1 }).message)
      .toBe('[Agentic #2] Action executed (exit: 1)');
    expect(map({ type: 'approval_required', blockId: 'b', question: 'Go?', options: [], stepIndex: 3 }))
      .toMatchObject({ level: 'WARN', message: '[Step 4] Approval required: Go?' });
    expect(map({ type: 'status', runId: 'r', status: 'FAILED' }).message).toBe('Run status: FAILED');
  });

  it('maps step messages with labels, keeping the output when nothing streamed', () => {
    const ctx = createStepLogContext();
    expect(map({ stepIndex: 0, status: 'RUNNING', input: '', output: '', durationMs: 0, blockName: 'Scan' }, ctx))
      .toMatchObject({ message: '[Step 1 · Scan] started', stepIndex: 0, stepLabel: 'Step 1 · Scan' });
    expect(map({ stepIndex: 0, status: 'FAILED', input: 'in', output: 'boom', durationMs: 5 }, ctx))
      .toMatchObject({ level: 'ERROR', message: '[Step 1 · Scan] boom' });
  });

  // TODO 4eea7a87: the step reports WAITING_APPROVAL while a person decides, then RUNNING again
  it('marks a step that waits for a person and continues it afterwards', () => {
    const ctx = createStepLogContext();
    const step = (status: string) => ({ stepIndex: 2, status, input: '', output: '', durationMs: 0, blockName: 'Gate' });
    expect(map(step('RUNNING'), ctx).message).toBe('[Step 3 · Gate] started');
    expect(map(step('WAITING_APPROVAL'), ctx))
      .toMatchObject({ level: 'WARN', message: '[Step 3 · Gate] waiting for approval or input', stepIndex: 2 });
    expect(map(step('RUNNING'), ctx).message).toBe('[Step 3 · Gate] continues');
    expect(map(step('RUNNING'), ctx).message).toBe('[Step 3 · Gate] started');
  });

  it('accepts a context without waitingSteps (older callers)', () => {
    const ctx = { labels: new Map<number, string>(), liveSteps: new Set<number>() };
    expect(map({ stepIndex: 0, status: 'WAITING_APPROVAL', input: '', output: '', durationMs: 0 }, ctx).message)
      .toBe('[Step 1] waiting for approval or input');
    expect(map({ stepIndex: 0, status: 'RUNNING', input: '', output: '', durationMs: 0 }, ctx).message)
      .toBe('[Step 1] continues');
  });

  it('closes a step that streamed live lines with status and duration', () => {
    const ctx = createStepLogContext();
    liveLinesToEntries('b', [{ seq: 0, stepIndex: 2, kind: 'line', text: 'x', ts: 0 }], ctx);

    expect(map({ stepIndex: 2, status: 'COMPLETED', input: '', output: 'long', durationMs: 850 }, ctx).message)
      .toBe('[Step 3] COMPLETED (850ms)');
  });

  it('falls back to the raw JSON for unknown typed messages', () => {
    expect(map({ type: 'something_new', value: 1 }).message).toBe('{"type":"something_new","value":1}');
  });

  it('uses the server timestamp for command messages', () => {
    expect(map({ type: 'command', runId: 'r', input: 'pwd', output: '', timestamp: '2026-01-01T00:00:00Z' }))
      .toMatchObject({ message: '$ pwd', timestamp: '2026-01-01T00:00:00Z' });
  });
});

describe('liveLinesToEntries', () => {
  it('maps kinds, flags tool errors and uses server timestamps', () => {
    const entries = liveLinesToEntries('buf', [
      { seq: 7, stepIndex: 1, kind: 'tool_error', text: '↳ error · 3 B', ts: Date.parse(NOW) },
    ], createStepLogContext());

    expect(entries).toEqual([{
      id: 'live-buf-7', timestamp: NOW, level: 'ERROR', message: '↳ error · 3 B',
      kind: 'tool_error', stepIndex: 1, stepLabel: 'Step 2',
    }]);
  });
});

describe('helpers', () => {
  it('appendCapped keeps the newest entries', () => {
    expect(appendCapped([1, 2, 3], [4, 5], 4)).toEqual([2, 3, 4, 5]);
    const prev = [1];
    expect(appendCapped(prev, [])).toBe(prev);
  });

  it('skippedMarker carries the count', () => {
    expect(skippedMarker('buf', 10, 3)).toMatchObject({ id: 'skip-buf-10', skipped: 3, level: 'WARN' });
  });
});
