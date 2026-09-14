import { describe, it, expect } from 'vitest';
import {
  formatMetadataValue, persistedForEachItems, persistedTotalItems, stepMetadataSummary, wrapperFacts,
} from '../stepMetadata';
import type { StepResult } from '../../../types';

function step(metadata: Record<string, unknown> | undefined): StepResult {
  return {
    id: 's1', stepIndex: 0, blockType: 'foreach', input: '', output: '', status: 'COMPLETED',
    durationMs: 1, error: null, metadata,
  } as StepResult;
}

describe('stepMetadata', () => {
  it('summarises provider, model and requestedModel', () => {
    const summary = stepMetadataSummary(step({
      provider: 'CLAUDE', model: 'claude-opus-4-6', requestedModel: 'opus', aiRequest: { provider: 'CLAUDE' },
    }));
    expect(summary.provider).toBe('CLAUDE');
    expect(summary.model).toBe('claude-opus-4-6');
    expect(summary.showRequestedModel).toBe(true);
    expect(summary.aiRequest).toEqual({ provider: 'CLAUDE' });
  });

  it('returns nulls without metadata', () => {
    const summary = stepMetadataSummary(step(undefined));
    expect(summary.provider).toBeNull();
    expect(summary.aiRequest).toBeNull();
    expect(summary.showRequestedModel).toBe(false);
  });

  it('formats nested values as JSON', () => {
    expect(formatMetadataValue({ a: 1 })).toBe('{"a":1}');
    expect(formatMetadataValue(['x'])).toBe('["x"]');
    expect(formatMetadataValue(3)).toBe('3');
    expect(formatMetadataValue(null)).toBe('');
  });

  it('lists wrapper facts in a fixed order', () => {
    expect(wrapperFacts(step({ conditionMet: false, attempts: 2, maxAttempts: 4 }))).toEqual([
      ['attempts', '2'], ['maxAttempts', '4'], ['conditionMet', 'false'],
    ]);
    expect(wrapperFacts(step({ provider: 'CLAUDE' }))).toEqual([]);
  });

  it('maps persisted foreach items to the live item shape', () => {
    const items = persistedForEachItems(step({
      totalItems: 3,
      items: [
        { index: 0, item: 'a', status: 'COMPLETED', durationMs: 5 },
        { index: 1, item: 'b', status: 'FAILED', durationMs: 0 },
        'garbage',
      ],
    }));
    expect(items).toEqual([
      { itemIndex: 0, item: 'a', status: 'completed' },
      { itemIndex: 1, item: 'b', status: 'failed' },
    ]);
    expect(persistedTotalItems(step({ totalItems: 3 }))).toBe(3);
  });

  it('is empty for missing or malformed items', () => {
    expect(persistedForEachItems(step(undefined))).toEqual([]);
    expect(persistedForEachItems(step({ items: 'nope' }))).toEqual([]);
    expect(persistedTotalItems(step({ totalItems: 'x' }))).toBeNull();
  });
});
