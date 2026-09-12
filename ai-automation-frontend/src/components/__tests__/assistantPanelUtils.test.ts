import { describe, expect, it } from 'vitest';

import {
  formatAssistantResponseTime,
  formatAssistantTime,
  isInternalAppPath,
  parsePageContext,
} from '../assistantPanelUtils';

describe('assistantPanelUtils', () => {
  it('parses workflow run paths into page context', () => {
    expect(parsePageContext('/workflows/wf-1/run/run-9')).toEqual({
      page: 'run',
      entityId: 'run-9',
      workflowId: 'wf-1',
      runId: 'run-9',
      pathname: '/workflows/wf-1/run/run-9',
    });
  });

  it('parses workflow detail paths into workflow-aware context', () => {
    expect(parsePageContext('/workflows/wf-1')).toEqual({
      page: 'workflow',
      entityId: 'wf-1',
      workflowId: 'wf-1',
      pathname: '/workflows/wf-1',
    });
  });

  it('parses project paths into project-aware context', () => {
    expect(parsePageContext('/projects/project-7')).toEqual({
      page: 'project',
      entityId: 'project-7',
      projectId: 'project-7',
      pathname: '/projects/project-7',
    });
  });

  it('falls back to dashboard for the root path', () => {
    expect(parsePageContext('/')).toEqual({ page: 'dashboard', pathname: '/' });
  });

  it('formats assistant timestamps as hh:mm', () => {
    const ts = new Date(2026, 2, 15, 9, 7).getTime();

    expect(formatAssistantTime(ts)).toBe('09:07');
  });

  it('formats short and long response times', () => {
    expect(formatAssistantResponseTime(240)).toBe('240ms');
    expect(formatAssistantResponseTime(1500)).toBe('1.5s');
    expect(formatAssistantResponseTime(undefined)).toBeNull();
  });
});

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
