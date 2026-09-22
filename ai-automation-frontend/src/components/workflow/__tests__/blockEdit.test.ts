import { describe, it, expect } from 'vitest';
import { blockFromDraft, blockUsesSkills, draftFromBlock, emptyDraft } from '../blockEdit';
import { BLOCK_TYPES, type BlockDefinition } from '../../../types';

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

describe('blockUsesSkills', () => {
  it('offers skills for blocks that always call the AI in the project directory', () => {
    for (const type of ['simple', 'agentic', 'skill-capability']) expect(blockUsesSkills(type, {}), type).toBe(true);
  });

  it('offers skills for wrapper blocks unless they run in shell mode', () => {
    for (const type of ['retry', 'foreach', 'while', 'conditional', 'switch']) {
      expect(blockUsesSkills(type, {}), type).toBe(true);
      expect(blockUsesSkills(type, { execution_mode: 'ai' }), type).toBe(true);
      expect(blockUsesSkills(type, '{"execution_mode": "shell"}'), type).toBe(false);
    }
  });

  it('hides skills for blocks without an AI call in the project directory', () => {
    const noSkills = BLOCK_TYPES.filter((t) => !['simple', 'agentic', 'skill-capability', 'retry', 'foreach', 'while', 'conditional', 'switch'].includes(t));
    expect(noSkills).toEqual(expect.arrayContaining(['shell', 'approval', 'compress-ai', 'todo-create']));
    for (const type of noSkills) expect(blockUsesSkills(type, {}), type).toBe(false);
  });

  it('treats half-typed JSON as the default mode', () => {
    expect(blockUsesSkills('foreach', '{"execution_mode": "sh')).toBe(true);
  });
});
