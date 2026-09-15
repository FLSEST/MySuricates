import { describe, it, expect } from 'vitest';
import { stepBlockType, stepKind } from '../stepMetadata';
import type { StepResult } from '../../../types';

function step(overrides: Partial<StepResult>): StepResult {
  return {
    id: 's1', stepIndex: 0, input: '', output: '', status: 'COMPLETED',
    durationMs: 1, error: null, ...overrides,
  };
}

describe('stepBlockType', () => {
  it('prefers the top-level field', () => {
    expect(stepBlockType(step({ blockType: 'shell', metadata: { blockType: 'simple' } }))).toBe('shell');
  });

  it('falls back to metadata.blockType (older backends only persist it there)', () => {
    expect(stepBlockType(step({ metadata: { blockType: 'agentic' } }))).toBe('agentic');
    expect(stepBlockType(step({ blockType: null, metadata: { blockType: 'foreach' } }))).toBe('foreach');
  });

  it('is empty when neither is set', () => {
    expect(stepBlockType(step({}))).toBe('');
    expect(stepBlockType(step({ metadata: { blockType: 7 } }))).toBe('');
  });
});

describe('stepKind', () => {
  it('classifies a step with an actual AI call as ai', () => {
    expect(stepKind(step({ blockType: 'simple', metadata: { aiRequest: { provider: 'CLAUDE' } } }))).toBe('ai');
  });

  it('classifies wrappers by whether an inner AI call ran', () => {
    expect(stepKind(step({ blockType: 'foreach', metadata: { aiRequest: { provider: 'CLAUDE' }, totalItems: 2 } })))
      .toBe('ai');
    expect(stepKind(step({ blockType: 'foreach', metadata: { totalItems: 2, executionMode: 'shell' } })))
      .toBe('deterministic');
    expect(stepKind(step({ blockType: 'conditional', metadata: { conditionMet: true } }))).toBe('deterministic');
  });

  it('treats a provider without aiRequest as ai (runs from before aiRequest was recorded)', () => {
    expect(stepKind(step({ blockType: 'retry', metadata: { provider: 'CLAUDE', model: 'opus' } }))).toBe('ai');
  });

  it('falls back to the block type while no AI metadata exists yet (running or failed early)', () => {
    expect(stepKind(step({ blockType: 'simple', status: 'RUNNING' }))).toBe('ai');
    expect(stepKind(step({ metadata: { blockType: 'agentic' }, status: 'FAILED' }))).toBe('ai');
    expect(stepKind(step({ blockType: 'skill-capability' }))).toBe('ai');
  });

  it('classifies approval and human-input as human', () => {
    expect(stepKind(step({ blockType: 'approval' }))).toBe('human');
    expect(stepKind(step({ metadata: { blockType: 'human-input' } }))).toBe('human');
  });

  it('classifies finished wrappers without an AI call as deterministic', () => {
    expect(stepKind(step({ blockType: 'while', metadata: { iterationCount: 3, iterations: [] } }))).toBe('deterministic');
    expect(stepKind(step({ blockType: 'retry', metadata: { attempts: 1, maxAttempts: 3 } }))).toBe('deterministic');
    expect(stepKind(step({ blockType: 'switch', metadata: { matchedCase: 'none' } }))).toBe('deterministic');
  });

  it('does not guess for wrappers without result metadata (running, or runs before it was recorded)', () => {
    expect(stepKind(step({ blockType: 'foreach', status: 'RUNNING', metadata: { blockType: 'foreach' } }))).toBe('unknown');
    expect(stepKind(step({ metadata: { blockType: 'conditional' } }))).toBe('unknown');
  });

  it('classifies everything else as deterministic', () => {
    for (const type of ['shell', 'integration', 'document', 'todo-create', 'sub-workflow', '']) {
      expect(stepKind(step({ blockType: type, metadata: { exitCode: 0 } }))).toBe('deterministic');
    }
  });

  it('classifies compress as deterministic and compress-ai as AI even before AI metadata exists', () => {
    expect(stepKind({ id: 'a', stepIndex: 0, input: '', output: '', status: 'COMPLETED', durationMs: 1, error: null, blockType: 'compress', metadata: {} })).toBe('deterministic');
    expect(stepKind({ id: 'b', stepIndex: 1, input: '', output: '', status: 'RUNNING', durationMs: 1, error: null, blockType: 'compress-ai', metadata: {} })).toBe('ai');
  });
});
