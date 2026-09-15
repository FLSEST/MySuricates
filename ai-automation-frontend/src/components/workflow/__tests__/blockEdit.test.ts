import { describe, it, expect } from 'vitest';
import { blockFromDraft, draftFromBlock, emptyDraft } from '../blockEdit';
import type { BlockDefinition } from '../../../types';

describe('blockEdit', () => {
  const stored: BlockDefinition = {
    type: 'shell',
    name: 'backend_tests',
    config: { command: 'mvn test' },
    requiredCapabilities: ['maven'],
    skillIds: ['s1'],
  };

  it('round-trips name, skills and fields the editor does not show', () => {
    const draft = draftFromBlock(stored);
    expect(draft.name).toBe('backend_tests');

    const saved = blockFromDraft({ ...draft, config: '{"command":"mvn -q test"}' }, stored);

    expect(saved).toEqual({
      type: 'shell',
      name: 'backend_tests',
      config: { command: 'mvn -q test' },
      requiredCapabilities: ['maven'],
      skillIds: ['s1'],
    });
  });

  it('trims the name and drops empty name and empty skills', () => {
    const saved = blockFromDraft({ ...draftFromBlock(stored), name: '   ', skillIds: [] }, stored);

    expect(saved).not.toHaveProperty('name');
    expect(saved).not.toHaveProperty('skillIds');
    expect(saved.requiredCapabilities).toEqual(['maven']);
  });

  it('builds a new block with a name', () => {
    const saved = blockFromDraft({ ...emptyDraft('simple'), name: ' report ', config: '{"command":"x"}' });

    expect(saved).toEqual({ type: 'simple', name: 'report', config: { command: 'x' } });
  });

  it('throws on invalid JSON so the caller keeps the modal open', () => {
    expect(() => blockFromDraft({ ...emptyDraft(), config: '{kaputt' })).toThrow();
  });
});
