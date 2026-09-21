import { describe, it, expect } from 'vitest';
import {
  compressionFacts, formatMetadataValue, latestStepByBlock, persistedForEachItems, persistedTotalItems, stepBlockIndex,
  stepMetadataSummary, wrapperFacts,
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

describe('compressionFacts', () => {
  it('reads the compression contract and ignores other steps', () => {
    const facts = compressionFacts({
      id: 's', stepIndex: 3, input: '', output: '', status: 'COMPLETED', durationMs: 1, error: null,
      metadata: {
        derivedOutput: true,
        compression: {
          source: { block: 'backend_tests', stepIndex: 0, status: 'FAILED', exitCode: 1 },
          inChars: 740808, outChars: 17950, maxChars: 20000, capped: false,
          stages: [{ op: 'lines.filter', inChars: 736706, outChars: 150220, note: '−8.914 Zeilen' }],
        },
      },
    });

    expect(facts).toEqual({
      source: { block: 'backend_tests', stepIndex: 0, status: 'FAILED', exitCode: 1 },
      inChars: 740808, outChars: 17950, maxChars: 20000, capped: false,
      stages: [{ op: 'lines.filter', inChars: 736706, outChars: 150220, note: '−8.914 Zeilen' }],
    });
    expect(compressionFacts({ id: 's', stepIndex: 0, input: '', output: '', status: 'COMPLETED', durationMs: 1, error: null, metadata: {} })).toBeNull();
  });

  describe('latestStepByBlock', () => {
    const at = (stepIndex: number, status: StepResult['status'], metadata?: Record<string, unknown>): StepResult =>
      ({ id: `s${stepIndex}`, stepIndex, input: '', output: '', status, durationMs: 1, error: null, metadata }) as StepResult;

    it('maps steps to block positions although wrapper items pushed the step index ahead', () => {
      // Run 192d8be8: two foreach blocks with ten items each → step indices 0, 1, 12, 13, 24, 25
      const steps = [0, 1, 12, 13, 24, 25].map((i) => at(i, 'COMPLETED'));
      const byBlock = latestStepByBlock(steps);
      expect([...byBlock.keys()]).toEqual([0, 1, 2, 3, 4, 5]);
      expect(byBlock.get(2)?.stepIndex).toBe(12);
    });

    it('prefers metadata.blockIndex and lets the newest attempt of a resumed block win', () => {
      const steps = [
        at(4, 'COMPLETED', { blockIndex: 3 }),
        at(0, 'COMPLETED', { blockIndex: 0 }),
        at(1, 'COMPLETED', { blockIndex: 1 }),
        at(2, 'FAILED', { blockIndex: 2 }),
        at(3, 'COMPLETED', { blockIndex: 2 }),
      ];
      const byBlock = latestStepByBlock(steps);
      expect([...byBlock.keys()].sort()).toEqual([0, 1, 2, 3]);
      expect(byBlock.get(2)?.status).toBe('COMPLETED');
      expect(byBlock.get(3)?.stepIndex).toBe(4);
    });

    it('reads only numeric block positions', () => {
      expect(stepBlockIndex(at(0, 'COMPLETED', { blockIndex: '2' }))).toBeNull();
      expect(stepBlockIndex(at(0, 'COMPLETED'))).toBeNull();
      expect(stepBlockIndex(at(7, 'COMPLETED', { blockIndex: 0 }))).toBe(0);
    });
  });
});
