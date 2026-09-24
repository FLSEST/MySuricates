import { describe, expect, it } from 'vitest';

import { isInternalAppPath } from './internalAppPath';

describe('isInternalAppPath', () => {
  it('accepts app-internal paths', () => {
    expect(isInternalAppPath('/workflows')).toBe(true);
    expect(isInternalAppPath('/workflows/wf-1/run/run-9')).toBe(true);
    expect(isInternalAppPath('/todos?projectId=abc')).toBe(true);
  });

  it('rejects protocol-relative and backslash paths that escape the app', () => {
    // react-router <= 7.17.0 resolves these as external targets (open redirect)
    expect(isInternalAppPath('//evil.example')).toBe(false);
    expect(isInternalAppPath('/\\evil.example')).toBe(false);
    expect(isInternalAppPath('/\\\\evil.example')).toBe(false);
  });

  it('rejects absolute URLs and empty values', () => {
    expect(isInternalAppPath('https://evil.example')).toBe(false);
    expect(isInternalAppPath('javascript:alert(1)')).toBe(false);
    expect(isInternalAppPath('')).toBe(false);
    expect(isInternalAppPath(undefined)).toBe(false);
  });
});
