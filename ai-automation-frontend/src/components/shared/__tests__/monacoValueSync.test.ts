import { describe, it, expect } from 'vitest';
import { MonacoValueSync } from '../monacoValueSync';

function typed(...texts: string[]): MonacoValueSync {
  const sync = new MonacoValueSync();
  for (const text of texts) sync.reportedChange(text);
  return sync;
}

describe('MonacoValueSync', () => {
  it('leaves the model alone when the parent echoes the latest keystroke', () => {
    const sync = typed('a', 'ab');
    expect(sync.shouldApply('ab', 'ab')).toBe(false);
  });

  it('never writes an older echo over newer text', () => {
    // 20 ms per key: the parent renders "a" while the editor already holds "abc"
    const sync = typed('a', 'ab', 'abc');
    expect(sync.shouldApply('a', 'abc')).toBe(false);
    expect(sync.shouldApply('ab', 'abc')).toBe(false);
    expect(sync.shouldApply('abc', 'abc')).toBe(false);
  });

  it('copes with a parent that skips renders and with repeated texts', () => {
    // typed a, b, backspace, b, c — "ab" occurs twice
    const sync = typed('a', 'ab', 'a', 'ab', 'abc');
    expect(sync.shouldApply('ab', 'abc')).toBe(false);
    expect(sync.shouldApply('a', 'abc')).toBe(false);
    expect(sync.shouldApply('ab', 'abc')).toBe(false);
    expect(sync.shouldApply('abc', 'abc')).toBe(false);
  });

  it('writes a value the editor never reported (loaded, reset, set by another field)', () => {
    const sync = typed('draft');
    expect(sync.shouldApply('{"provider": "CODEX"}', 'draft')).toBe(true);
  });

  it('writes an outside value once the parent has caught up, even if it was typed before', () => {
    const sync = typed('x', 'xy');
    expect(sync.shouldApply('xy', 'xy')).toBe(false);
    // later a reset to an earlier text: no longer an echo
    expect(sync.shouldApply('x', 'xy')).toBe(true);
  });

  it('keeps only a bounded number of reports for a parent that never echoes them', () => {
    const sync = new MonacoValueSync(3);
    for (const text of ['1', '2', '3', '4', '5']) sync.reportedChange(text);
    expect(sync.shouldApply('1', '5')).toBe(true);
  });
});
