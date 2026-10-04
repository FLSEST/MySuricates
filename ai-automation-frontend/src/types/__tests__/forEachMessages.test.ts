import { describe, it, expect } from 'vitest';
import { handleForEachMessage, type ForEachState } from '../websocket';

/** Runs the reducer the way the hook does and returns the state it produced. */
function apply(messages: Parameters<typeof handleForEachMessage>[0][]): Map<number, ForEachState> {
  let state = new Map<number, ForEachState>();
  for (const msg of messages) {
    handleForEachMessage(msg, (update) => {
      state = typeof update === 'function' ? update(state) : update;
    });
  }
  return state;
}

describe('handleForEachMessage', () => {
  const started = { type: 'foreach_item_started' as const, stepIndex: 1, itemIndex: 0, item: 'auth', totalItems: 2 };

  // a5c9fbbf: foreach with workflow_id names the child run of each item
  it('keeps the child run of a finished item', () => {
    const state = apply([started, { ...started, type: 'foreach_item_completed', status: 'COMPLETED', output: 'ok', subRunId: 'sub-1' }]);
    expect(state.get(1)?.items[0]).toMatchObject({ status: 'completed', output: 'ok', subRunId: 'sub-1' });
  });

  it('works with older backends that send no subRunId, and with an empty one', () => {
    const old = apply([started, { ...started, type: 'foreach_item_completed', status: 'FAILED', output: 'x' }]);
    expect(old.get(1)?.items[0]).toEqual({ itemIndex: 0, item: 'auth', status: 'failed', output: 'x' });
    const empty = apply([started, { ...started, type: 'foreach_item_completed', status: 'COMPLETED', output: '', subRunId: '' }]);
    expect(empty.get(1)?.items[0].subRunId).toBeUndefined();
  });
});
