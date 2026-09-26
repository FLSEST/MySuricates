import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { createTestQueryClient } from '../../test/utils';
import { useTodoExportTarget } from '../useTodoExportTarget';
import type { IntegrationProviderInfo, Project, Todo } from '../../types';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function wrapper({ children }: { children: ReactNode }) {
  return createElement(QueryClientProvider, { client: createTestQueryClient() }, children);
}

const todo = (partial: Partial<Todo> = {}) => ({ id: 't1', projectId: 'p1', ...partial }) as Todo;
const project = (id: string, taskExport?: { provider?: string; target?: string }) =>
  ({ id, name: id, config: taskExport ? { taskExport } : {} }) as unknown as Project;
const sink = (providerId: string, partial: Partial<IntegrationProviderInfo> = {}) =>
  ({ providerId, displayName: providerId.toUpperCase(), icon: `${providerId}-icon`,
    status: 'CONNECTED', supportsTaskExport: true, ...partial }) as IntegrationProviderInfo;

function serveTargets(targets: { id: string; label: string }[]) {
  const asked: string[] = [];
  server.use(http.get('/api/v1/integrations/:providerId/task-targets', ({ params }) => {
    asked.push(String(params.providerId));
    return HttpResponse.json(targets);
  }));
  return asked;
}

describe('useTodoExportTarget', () => {
  it('offers only connected trackers that take tasks and picks the first one', async () => {
    const asked = serveTargets([{ id: 'board-1', label: 'Board 1' }]);
    const integrations = [
      sink('github', { status: 'DISCONNECTED' }),
      sink('slack', { supportsTaskExport: false }),
      sink('jira'),
      sink('linear'),
    ];
    const { result } = renderHook(
      () => useTodoExportTarget({ todo: todo(), projects: [], integrations }), { wrapper });

    expect(result.current.taskSinks.map((s) => s.providerId)).toEqual(['jira', 'linear']);
    expect(result.current.activeProvider).toBe('jira');
    expect(result.current.activeProviderName).toBe('JIRA');
    expect(result.current.activeProviderIcon).toBe('jira-icon');
    expect(result.current.activeTarget).toBe('');
    await waitFor(() => expect(result.current.taskTargets).toEqual([{ id: 'board-1', label: 'Board 1' }]));
    expect(asked).toEqual(['jira']);
  });

  it('takes tracker and target from the project chosen in the form before the todo project', () => {
    serveTargets([]);
    const projects = [project('p1', { provider: 'linear', target: 'OLD' }), project('p2', { provider: 'linear', target: 'TEAM-A' })];
    const { result } = renderHook(() => useTodoExportTarget({
      todo: todo(), projectId: 'p2', projects, integrations: [sink('jira'), sink('linear')],
    }), { wrapper });

    expect(result.current.activeProvider).toBe('linear');
    expect(result.current.activeTarget).toBe('TEAM-A');
    expect(result.current.configuredTarget).toBe('TEAM-A');
  });

  it('prefers the own choice, trims the typed target and forgets both for the next todo', () => {
    serveTargets([]);
    const integrations = [sink('jira'), sink('linear')];
    const { result, rerender } = renderHook(
      ({ current }: { current: Todo }) => useTodoExportTarget({ todo: current, projects: [], integrations }),
      { wrapper, initialProps: { current: todo() } });

    act(() => {
      result.current.setExportProvider('linear');
      result.current.setExportTarget('  TEAM-B ');
    });
    expect(result.current.activeProvider).toBe('linear');
    expect(result.current.activeTarget).toBe('TEAM-B');
    expect(result.current.exportTarget).toBe('  TEAM-B ');

    rerender({ current: todo({ id: 't2' }) });
    expect(result.current.activeProvider).toBe('jira');
    expect(result.current.activeTarget).toBe('');
  });

  it('names the tracker of an exported todo and asks for no targets', async () => {
    const asked = serveTargets([]);
    const { result } = renderHook(() => useTodoExportTarget({
      todo: todo({ exportedProvider: 'jira', exportedRef: 'JIRA-1' }), projects: [],
      integrations: [sink('jira', { status: 'DISCONNECTED' })],
    }), { wrapper });

    expect(result.current.exportedProviderName).toBe('JIRA');
    expect(result.current.exportedProviderIcon).toBe('jira-icon');
    await new Promise((r) => setTimeout(r, 20));
    expect(asked).toEqual([]);
  });

  it('falls back to the provider id of an exported todo whose integration is gone', () => {
    const { result } = renderHook(() => useTodoExportTarget({
      todo: todo({ exportedProvider: 'trello' }), projects: [], integrations: [],
    }), { wrapper });
    expect(result.current.exportedProviderName).toBe('trello');
    expect(result.current.exportedProviderIcon).toBeUndefined();
    expect(result.current.activeProvider).toBe('');
  });
});
