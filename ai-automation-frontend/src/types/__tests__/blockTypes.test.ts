import { describe, it, expect } from 'vitest';
import { AI_BLOCK_TYPES, BLOCK_TYPES } from '../index';
import { de } from '../../i18n/de';
import { en } from '../../i18n/en';

/**
 * Mirror of the backend guard `BlockTypeListsGuardTest` (ai-automation-backend,
 * com.automation.service.assistant): there the registered BlockExecutors are the source of
 * truth. When a block type is added on the backend, extend BLOCK_TYPES here and this list.
 */
const EXPECTED_BLOCK_TYPES = [
  'simple', 'shell', 'retry', 'foreach', 'while', 'conditional', 'switch',
  'skill-capability', 'approval', 'agentic', 'sub-workflow', 'human-input',
  'integration', 'document', 'todo-create', 'todo-update', 'todo-close',
  'compress', 'compress-ai', 'dashboard-report',
];

describe('BLOCK_TYPES', () => {
  it('lists all 20 backend block types exactly once', () => {
    expect([...BLOCK_TYPES].sort()).toEqual([...EXPECTED_BLOCK_TYPES].sort());
    expect(new Set(BLOCK_TYPES).size).toBe(20);
  });

  it('has a label for every block type in both locales', () => {
    for (const type of BLOCK_TYPES) {
      expect(de.workflows.blockTypes[type], `de label for ${type}`).toBeTruthy();
      expect(en.workflows.blockTypes[type], `en label for ${type}`).toBeTruthy();
    }
  });

  it('AI-capable block types are a subset of BLOCK_TYPES and exclude non-AI blocks', () => {
    for (const type of AI_BLOCK_TYPES) {
      expect(BLOCK_TYPES).toContain(type);
    }
    for (const nonAi of ['shell', 'integration', 'document', 'sub-workflow', 'todo-create', 'todo-update', 'todo-close', 'compress', 'dashboard-report']) {
      expect(AI_BLOCK_TYPES).not.toContain(nonAi);
    }
  });

  it('approval and human-input take provider/model for their AI actor (dca0c03e)', () => {
    expect(AI_BLOCK_TYPES).toContain('approval');
    expect(AI_BLOCK_TYPES).toContain('human-input');
  });

  it('compress-ai honours block provider/model, compress does not', () => {
    expect(AI_BLOCK_TYPES).toContain('compress-ai');
    expect(AI_BLOCK_TYPES).not.toContain('compress');
  });
});
