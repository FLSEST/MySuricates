import { describe, it, expect } from 'vitest';
import { computeIterationDiff } from '../iterationDiff';

describe('computeIterationDiff', () => {
  it('returns empty array when both iterations are identical', () => {
    const entry = {
      decomposition: { analysis: 'A', strategy: 'B' },
      evaluation: { summary: 'OK', issues: ['x'] },
    };
    expect(computeIterationDiff(entry, entry)).toEqual([]);
  });

  it('marks every populated field as added when prev is null', () => {
    const diff = computeIterationDiff(null, {
      decomposition: { analysis: 'first analysis', strategy: 'plan' },
      evaluation: { summary: 'looks good', issues: ['nit'] },
    });
    expect(diff).toHaveLength(4);
    expect(diff.every(d => d.kind === 'added')).toBe(true);
    expect(diff.map(d => d.key).sort()).toEqual(['analysis', 'issues', 'strategy', 'summary']);
  });

  it('skips fields that are empty in both versions', () => {
    const diff = computeIterationDiff(
      { decomposition: { analysis: 'same' } },
      { decomposition: { analysis: 'same' } }
    );
    expect(diff).toEqual([]);
  });

  it('flags only the evaluation summary when it changes', () => {
    const prev = {
      decomposition: { analysis: 'same', strategy: 'same' },
      evaluation: { summary: 'initial', issues: ['nit'] },
    };
    const curr = {
      decomposition: { analysis: 'same', strategy: 'same' },
      evaluation: { summary: 'refined', issues: ['nit'] },
    };
    const diff = computeIterationDiff(prev, curr);
    expect(diff).toEqual([
      { key: 'summary', group: 'evaluation', kind: 'changed', before: 'initial', after: 'refined' },
    ]);
  });

  it('treats issues array ordering changes and content changes', () => {
    const diff = computeIterationDiff(
      { evaluation: { issues: ['a', 'b'] } },
      { evaluation: { issues: ['a', 'b', 'c'] } }
    );
    expect(diff).toHaveLength(1);
    expect(diff[0].key).toBe('issues');
    expect(diff[0].kind).toBe('changed');
    expect(diff[0].before).toBe('a\nb');
    expect(diff[0].after).toBe('a\nb\nc');
  });

  it('handles missing groups without throwing', () => {
    const diff = computeIterationDiff(
      {},
      { decomposition: { analysis: 'new' } }
    );
    expect(diff).toEqual([
      { key: 'analysis', group: 'decomposition', kind: 'added', before: '', after: 'new' },
    ]);
  });

  it('is null-safe when iteration entries lack expected keys', () => {
    expect(() => computeIterationDiff(null, {})).not.toThrow();
    expect(computeIterationDiff(null, {})).toEqual([]);
  });

  it('serializes non-string issues entries deterministically', () => {
    const diff = computeIterationDiff(
      null,
      { evaluation: { issues: [{ text: 'complex' }, 'simple'] } }
    );
    expect(diff).toHaveLength(1);
    expect(diff[0].key).toBe('issues');
    expect(diff[0].after).toBe('{"text":"complex"}\nsimple');
  });
});
