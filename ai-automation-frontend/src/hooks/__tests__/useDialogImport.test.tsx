import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { useI18nStore } from '../../i18n';
import { en } from '../../i18n/en';
import { useToastStore } from '../../stores/toastStore';
import { OTHER_DIALOG_GROUP, useDialogImport } from '../useDialogImport';
import type { DialogSessionInfo } from '../../types';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  useI18nStore.setState({ locale: 'en' });
  useToastStore.setState({ toasts: [] });
});

const project = (id: string, name: string, path: string, archivedAt: string | null = null) =>
  ({ id, name, path, archivedAt, type: 'CODE', status: 'STOPPED', container: null, workflowCount: 0 });

function session(id: string, projectPath: string, firstUserMessage: string, substantial = true): DialogSessionInfo {
  return {
    sessionId: id, filePath: `/s/${id}.jsonl`, projectPath, startedAt: null, endedAt: null,
    turnCount: 1, toolCallCount: 1, dominantTools: [], firstUserMessage, substantial,
  };
}

const sessions = [
  session('s1', '/code/shop', 'Fix the cart'),
  session('s2', '/code/shop', 'Quick look', false),
  session('s3', '/tmp/scratch', 'Try something'),
  session('s4', '/code/blog', 'Blog post'),
];

function serve() {
  server.use(
    http.get('/api/v1/projects', () => HttpResponse.json([
      project('p-shop', 'Shop', '/code/shop'),
      project('p-blog', 'Blog', '/code/blog'),
      project('p-old', 'Old', '/code/old', '2025-01-01T00:00:00Z'),
    ])),
    http.get('/api/v1/dialog/sessions/count', () => HttpResponse.json({
      '/code/shop': 2, '/code/blog': 5, '/code/old': 4, '/tmp/scratch': 1, '/tmp/other': 2,
    })),
    http.get('/api/v1/dialog/sessions', ({ request }) => {
      const path = new URL(request.url).searchParams.get('projectPath');
      return HttpResponse.json(path ? sessions.filter((s) => s.projectPath === path) : sessions);
    }),
    http.get('/api/v1/workflows', () => HttpResponse.json([
      { id: 'w1', projectId: 'p-shop', name: 'Deploy' },
      { id: 'w2', projectId: 'p-blog', name: 'Publish' },
    ])),
  );
}

function setup() {
  const location = { current: '' };
  function Spy() {
    location.current = useLocation().pathname;
    return null;
  }
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/dialogs']}>
        {children}
        <Routes><Route path="*" element={<Spy />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
  return { ...renderHook(() => useDialogImport(), { wrapper }), location };
}

describe('useDialogImport', () => {
  it('groups the active projects with sessions by count and collects unknown paths under "Other"', async () => {
    serve();
    const { result } = setup();
    await waitFor(() => expect(result.current.groups).toHaveLength(3));
    // Archived "Old" counts as unknown, like the paths without a project
    expect(result.current.groups.map((g) => [g.id, g.count])).toEqual([
      ['p-blog', 5], ['p-shop', 2], [OTHER_DIALOG_GROUP, 7],
    ]);
    expect(result.current.groups[2]).toMatchObject({ label: en.dialog.otherSessions, path: null, isOther: true });
  });

  it('loads the sessions of the chosen project, substantial only by default, and filters by search', async () => {
    serve();
    const { result } = setup();
    await waitFor(() => expect(result.current.groups).toHaveLength(3));

    act(() => result.current.selectGroup('p-shop'));
    await waitFor(() => expect(result.current.filteredSessions.map((s) => s.sessionId)).toEqual(['s1']));
    expect(result.current.projectWorkflows.map((w) => w.id)).toEqual(['w1']);

    act(() => result.current.setSubstantialOnly(false));
    expect(result.current.filteredSessions.map((s) => s.sessionId)).toEqual(['s1', 's2']);
    act(() => result.current.setSearch('QUICK'));
    expect(result.current.filteredSessions.map((s) => s.sessionId)).toEqual(['s2']);

    // Another group starts without search
    act(() => result.current.selectGroup('p-blog'));
    expect(result.current.search).toBe('');
  });

  it('shows under "Other" only the sessions outside the active projects, without project workflows', async () => {
    serve();
    const { result } = setup();
    await waitFor(() => expect(result.current.groups).toHaveLength(3));

    act(() => result.current.selectGroup(OTHER_DIALOG_GROUP));
    await waitFor(() => expect(result.current.filteredSessions.map((s) => s.sessionId)).toEqual(['s3']));
    expect(result.current.projectWorkflows).toEqual([]);
  });

  it('analyzes a session, creates the workflow with the edited blocks and opens it', async () => {
    serve();
    const created: unknown[] = [];
    server.use(
      http.post('/api/v1/dialog/sessions/analyze', () => HttpResponse.json({ suggestedName: 'Cart', blocks: [], available: true })),
      http.post('/api/v1/dialog/sessions/create-workflow', async ({ request }) => {
        created.push(await request.json());
        return HttpResponse.json({ id: 'w-new' });
      }),
    );
    const { result, location } = setup();

    await act(() => result.current.analyze(sessions[0]));
    expect(result.current.selectedSession?.sessionId).toBe('s1');
    expect(result.current.suggestion?.suggestedName).toBe('Cart');

    const blocks = [{ type: 'shell', config: { command: 'make' } }];
    await act(() => result.current.create('p-shop', 'Cart flow', blocks));
    expect(created).toEqual([{ suggestion: { suggestedName: 'Cart', blocks, available: true }, projectId: 'p-shop', name: 'Cart flow' }]);
    await waitFor(() => expect(location.current).toBe('/workflows/w-new'));
    expect(useToastStore.getState().toasts.map((t) => t.message)).toEqual([en.dialog.created]);
  });

  it('reports a failed analysis and keeps no suggestion', async () => {
    serve();
    server.use(http.post('/api/v1/dialog/sessions/analyze', () => HttpResponse.json({ message: 'boom' }, { status: 500 })));
    const { result } = setup();

    await act(() => result.current.analyze(sessions[0]));
    expect(result.current.suggestion).toBeNull();
    expect(useToastStore.getState().toasts.map((t) => t.message)).toEqual([en.dialog.analyzeFailed]);
  });
});
