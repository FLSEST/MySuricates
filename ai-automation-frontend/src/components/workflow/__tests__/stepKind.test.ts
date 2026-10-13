import { describe, it, expect } from 'vitest';
import { shadowFacts, stepAnsweredBy, stepBlockType, stepKind } from '../stepMetadata';
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
    expect(stepKind(step({ metadata: { provider: 'CLAUDE', model: 'opus' } }))).toBe('ai');
  });

  it('does not read the integration provider of an integration step as an AI call', () => {
    const metadata = { provider: 'feed', connection: 'default', action: 'fetch', executionMode: 'integration' };
    expect(stepKind(step({ blockType: 'integration', metadata }))).toBe('deterministic');
    expect(stepKind(step({ blockType: 'integration', status: 'FAILED', metadata: { ...metadata, error: 'timeout' } })))
      .toBe('deterministic');
    // steps without a block type: the integration's execution mode tells them apart
    expect(stepKind(step({ metadata }))).toBe('deterministic');
    expect(stepKind(step({ metadata: { blockType: 'integration', provider: 'open-meteo' } }))).toBe('deterministic');
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

describe('stepKind of a cast decision step (answeredBy)', () => {
  it('is human for approval and human-input without casting, as before', () => {
    expect(stepKind(step({ blockType: 'approval' }))).toBe('human');
    expect(stepKind(step({ blockType: 'human-input', metadata: { answeredBy: 'human' } }))).toBe('human');
  });

  it('is ai when an AI actor answered the step', () => {
    expect(stepKind(step({ blockType: 'approval', metadata: { answeredBy: 'ai', aiRequest: { provider: 'CLAUDE' } } })))
      .toBe('ai');
    expect(stepKind(step({ metadata: { blockType: 'human-input', answeredBy: 'ai' } }))).toBe('ai');
  });

  it('stays human in shadow mode — the person decided, even though an AI call ran', () => {
    const metadata = { answeredBy: 'shadow', shadowAnswer: 'Ja', agreed: false, aiRequest: { provider: 'CLAUDE' } };
    expect(stepKind(step({ blockType: 'approval', metadata }))).toBe('human');
  });

  it('ignores answeredBy on other block types', () => {
    expect(stepKind(step({ blockType: 'shell', metadata: { answeredBy: 'ai' } }))).toBe('deterministic');
  });
});

describe('stepAnsweredBy / shadowFacts', () => {
  it('reads only the three known actors', () => {
    expect(stepAnsweredBy(step({ metadata: { answeredBy: 'shadow' } }))).toBe('shadow');
    expect(stepAnsweredBy(step({ metadata: { answeredBy: 'robot' } }))).toBeNull();
    expect(stepAnsweredBy(step({}))).toBeNull();
  });

  it('exposes the proposal and the agreement of a shadow step only', () => {
    expect(shadowFacts(step({ metadata: { answeredBy: 'shadow', shadowAnswer: 'Ja', agreed: true } })))
      .toEqual({ proposal: 'Ja', agreed: true });
    expect(shadowFacts(step({ metadata: { answeredBy: 'shadow', agreed: false } }))).toEqual({ proposal: '', agreed: false });
    // Nobody answered (timeout, cancel, default value): neither agreement nor disagreement
    expect(shadowFacts(step({ metadata: { answeredBy: 'shadow', shadowAnswer: 'Ja' } })))
      .toEqual({ proposal: 'Ja', agreed: null });
    expect(shadowFacts(step({ metadata: { answeredBy: 'ai', shadowAnswer: 'Ja' } }))).toBeNull();
  });
});
