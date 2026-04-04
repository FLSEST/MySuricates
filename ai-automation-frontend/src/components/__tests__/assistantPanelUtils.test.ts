import { describe, expect, it } from 'vitest';

import {
  formatAssistantResponseTime,
  formatAssistantTime,
  parsePageContext,
} from '../assistantPanelUtils';

describe('assistantPanelUtils', () => {
  it('parses workflow run paths into page context', () => {
    expect(parsePageContext('/workflows/wf-1/run/run-9')).toEqual({
      page: 'run',
      entityId: 'run-9',
      workflowId: 'wf-1',
    });
  });

  it('parses project paths into project-aware context', () => {
    expect(parsePageContext('/projects/project-7')).toEqual({
      page: 'project',
      entityId: 'project-7',
      projectId: 'project-7',
    });
  });

  it('falls back to dashboard for the root path', () => {
    expect(parsePageContext('/')).toEqual({ page: 'dashboard' });
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
