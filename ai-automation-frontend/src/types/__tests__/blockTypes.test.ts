import { describe, it, expect } from 'vitest';
import { BLOCK_TYPES } from '../index';
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
];

describe('BLOCK_TYPES', () => {
  it('lists all 17 backend block types exactly once', () => {
    expect([...BLOCK_TYPES].sort()).toEqual([...EXPECTED_BLOCK_TYPES].sort());
    expect(new Set(BLOCK_TYPES).size).toBe(17);
  });

  it('has a label for every block type in both locales', () => {
    for (const type of BLOCK_TYPES) {
      expect(de.workflows.blockTypes[type], `de label for ${type}`).toBeTruthy();
      expect(en.workflows.blockTypes[type], `en label for ${type}`).toBeTruthy();
    }
  });
});
