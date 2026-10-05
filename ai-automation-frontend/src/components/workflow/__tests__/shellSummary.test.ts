import { describe, it, expect } from 'vitest';
import { shellSummary } from '../shellSummary';

describe('shellSummary', () => {
  it('shows the command of a normal shell block', () => {
    expect(shellSummary({ command: 'mvn test' })).toBe('mvn test');
  });

  it('shows image and arguments in tool mode', () => {
    expect(shellSummary({ container_image: 'aquasec/trivy:0.70.0', container_args: ['fs', '/workspace'] }))
      .toBe('aquasec/trivy:0.70.0 fs /workspace');
  });

  it('is empty without command and arguments', () => {
    expect(shellSummary({})).toBe('');
  });
});
