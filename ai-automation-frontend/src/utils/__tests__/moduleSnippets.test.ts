import { describe, it, expect } from 'vitest';
import { envSnippet, yamlSnippet } from '../moduleSnippets';

describe('moduleSnippets', () => {
  it('nests the property path as YAML', () => {
    expect(yamlSnippet('automation.modules.webhooks', true)).toBe(
      'automation:\n  modules:\n    webhooks: true',
    );
  });

  it('keeps dashed module names and writes false for disabling', () => {
    expect(yamlSnippet('automation.hardening.require-sealed-for-unattended', false)).toBe(
      'automation:\n  hardening:\n    require-sealed-for-unattended: false',
    );
  });

  it('renders the environment variable assignment', () => {
    expect(envSnippet('AUTOMATION_MODULES_REMOTE_ACCESS', true)).toBe('AUTOMATION_MODULES_REMOTE_ACCESS=true');
  });
});
