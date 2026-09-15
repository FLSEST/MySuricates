import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import type { ReactNode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { useI18nStore } from '../../i18n';
import { useToastStore } from '../../stores/toastStore';
import { useDashboardLayoutStore } from '../../stores/dashboardLayoutStore';
import { useDashboardSyncStore } from '../../stores/dashboardSyncStore';
import { createDefaultLayout } from '../../components/dashboard/dashboardLayout';
import type { DashboardLayoutVersion, DashboardLayoutVersionSummary } from '../../types';
import { useDashboardLayoutSync } from '../useDashboardLayoutSync';

const summary = (id: string, extra: Partial<DashboardLayoutVersionSummary> = {}): DashboardLayoutVersionSummary => ({
  id, createdAt: '2026-09-15T10:00:00Z', deviceLabel: 'Laptop', name: null, formatVersion: 2, viewCount: 1,
  contentHash: `hash-${id}`, ...extra,
});

const remoteLayout = {
  activeViewId: 'default',
  views: [{ id: 'default', name: '', widgets: [{ instanceId: 'quick-actions', type: 'quick-actions' }] }],
};

const localWidgets = [{ instanceId: 'todos', type: 'todos' }];

interface Serve {
  /** Throwing (instead of returning) simulates the list endpoint itself failing (500). */
  latest: () => DashboardLayoutVersionSummary | null;
  versions?: Record<string, DashboardLayoutVersion>;
  onPost?: (body: unknown) => ReturnType<typeof HttpResponse.json>;
  /** Delays the detail GET response until this resolves — to land an edit while it is in flight. */
  beforeDetail?: Promise<unknown>;
  /** Delays the POST response until this resolves — to land an edit while a save is in flight. */
  beforePost?: Promise<unknown>;
}

function serve({ latest, versions = {}, onPost, beforeDetail, beforePost }: Serve) {
  const calls: string[] = [];
  server.use(
    http.get('/api/v1/dashboard/layouts', () => {
      calls.push('list');
      let item: DashboardLayoutVersionSummary | null;
      try {
        item = latest();
      } catch {
        return HttpResponse.json({ error: 'list failed' }, { status: 500 });
      }
      return HttpResponse.json({ content: item ? [item] : [], page: 0, size: 1, totalElements: item ? 1 : 0, totalPages: item ? 1 : 0 });
    }),
    http.get('/api/v1/dashboard/layouts/:id', async ({ params }) => {
      calls.push(`get:${String(params.id)}`);
      if (beforeDetail) await beforeDetail;
      const version = versions[String(params.id)];
      return version ? HttpResponse.json(version) : HttpResponse.json({ error: 'not found' }, { status: 404 });
    }),
    http.post('/api/v1/dashboard/layouts', async ({ request }) => {
      const body = await request.json();
      calls.push('post');
      if (beforePost) await beforePost;
      return onPost ? onPost(body) : HttpResponse.json(summary('saved'), { status: 201 });
    }),
  );
  return calls;
}

/** A promise this test releases manually, to pin down which side of an await an action lands on. */
function gate(): { promise: Promise<void>; release: () => void } {
  let release: () => void = () => {};
  const promise = new Promise<void>((resolve) => {
    release = resolve;
  });
  return { promise, release };
}

let queryClient: QueryClient;
function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  useI18nStore.setState({ locale: 'en' });
  useToastStore.setState({ toasts: [] });
  useDashboardLayoutStore.setState(createDefaultLayout());
  useDashboardSyncStore.setState({ deviceLabel: 'Laptop', baseVersionId: null, dirty: false });
});

function customizeLocally() {
  useDashboardLayoutStore.setState({ views: [{ id: 'default', name: '', widgets: localWidgets }] });
}

describe('useDashboardLayoutSync', () => {
  it('loads a newer server layout when nothing changed locally', async () => {
    serve({ latest: () => summary('v2'), versions: { v2: { ...summary('v2'), layout: remoteLayout } } });

    renderHook(() => useDashboardLayoutSync(), { wrapper });

    await waitFor(() => expect(useDashboardSyncStore.getState().baseVersionId).toBe('v2'));
    expect(useDashboardLayoutStore.getState().views[0]?.widgets).toEqual(remoteLayout.views[0]?.widgets);
    expect(useDashboardSyncStore.getState().dirty).toBe(false);
  });

  it('reports a conflict instead of overwriting local changes', async () => {
    customizeLocally();
    useDashboardSyncStore.setState({ baseVersionId: 'v1', dirty: true });
    // The mount check now always reads the version's content before deciding, to be able to
    // re-check for a race with a local edit — so the detail GET must resolve too.
    serve({
      latest: () => summary('v2', { deviceLabel: 'Desktop' }),
      versions: { v2: { ...summary('v2', { deviceLabel: 'Desktop' }), layout: remoteLayout } },
    });

    const { result } = renderHook(() => useDashboardLayoutSync(), { wrapper });

    await waitFor(() => expect(result.current.conflict?.id).toBe('v2'));
    expect(useDashboardLayoutStore.getState().views[0]?.widgets).toEqual(localWidgets);
  });

  it('treats a customized layout that was never synced as a local change', async () => {
    customizeLocally();
    serve({
      latest: () => summary('v2'),
      versions: { v2: { ...summary('v2'), layout: remoteLayout } },
    });

    const { result } = renderHook(() => useDashboardLayoutSync(), { wrapper });

    await waitFor(() => expect(result.current.conflict?.id).toBe('v2'));
  });

  it('does nothing when the newest server version is the base', async () => {
    useDashboardSyncStore.setState({ baseVersionId: 'v2' });
    const calls = serve({ latest: () => summary('v2') });

    const { result } = renderHook(() => useDashboardLayoutSync(), { wrapper });

    await waitFor(() => expect(calls).toContain('list'));
    // Let the mount check actually finish before asserting on what it did *not* do.
    await act(async () => {
      await new Promise((r) => setTimeout(r, 20));
    });
    expect(calls).not.toContain('get:v2');
    expect(result.current.conflict).toBeNull();
  });

  it('refuses a version from a newer app version', async () => {
    serve({
      latest: () => summary('v9', { formatVersion: 3 }),
      versions: { v9: { ...summary('v9', { formatVersion: 3 }), layout: remoteLayout } },
    });

    renderHook(() => useDashboardLayoutSync(), { wrapper });

    await waitFor(() => expect(useToastStore.getState().toasts.some((t) => t.type === 'warning')).toBe(true));
    expect(useDashboardSyncStore.getState().baseVersionId).toBeNull();
    expect(useDashboardLayoutStore.getState().views[0]?.widgets).toEqual([]);
  });

  it('saves a sanitized snapshot based on the synced version', async () => {
    customizeLocally();
    useDashboardSyncStore.setState({ baseVersionId: 'v1', dirty: true });
    let posted: unknown;
    serve({
      latest: () => summary('v1'),
      onPost: (body) => {
        posted = body;
        return HttpResponse.json(summary('v3'), { status: 201 });
      },
    });
    const { result } = renderHook(() => useDashboardLayoutSync(), { wrapper });

    await act(() => result.current.saveSnapshot());

    expect(posted).toEqual({
      deviceLabel: 'Laptop',
      formatVersion: 2,
      baseVersionId: 'v1',
      force: false,
      layout: { activeViewId: 'default', views: [{ id: 'default', name: '', widgets: localWidgets }] },
    });
    expect(useDashboardSyncStore.getState()).toMatchObject({ baseVersionId: 'v3', dirty: false });
  });

  it('turns a 409 into a conflict with the newest server version and keeps the local state dirty', async () => {
    customizeLocally();
    useDashboardSyncStore.setState({ baseVersionId: 'v1', dirty: true });
    let current = summary('v1');
    serve({
      latest: () => current,
      onPost: () => {
        current = summary('v2', { deviceLabel: 'Desktop' });
        return HttpResponse.json({ code: 'STATE_CONFLICT', error: 'changed' }, { status: 409 });
      },
    });
    const { result } = renderHook(() => useDashboardLayoutSync(), { wrapper });

    await act(() => result.current.saveSnapshot());

    expect(result.current.conflict?.id).toBe('v2');
    expect(useDashboardSyncStore.getState()).toMatchObject({ baseVersionId: 'v1', dirty: true });
  });

  it('keepLocal saves with force and clears the conflict', async () => {
    customizeLocally();
    useDashboardSyncStore.setState({ baseVersionId: 'v1', dirty: true });
    let posted: { force?: boolean } | undefined;
    serve({
      latest: () => summary('v2'),
      versions: { v2: { ...summary('v2'), layout: remoteLayout } },
      onPost: (body) => {
        posted = body as { force?: boolean };
        return HttpResponse.json(summary('v3'), { status: 201 });
      },
    });
    const { result } = renderHook(() => useDashboardLayoutSync(), { wrapper });
    await waitFor(() => expect(result.current.conflict?.id).toBe('v2'));

    await act(() => result.current.keepLocal());

    expect(posted?.force).toBe(true);
    expect(result.current.conflict).toBeNull();
    expect(useDashboardSyncStore.getState().baseVersionId).toBe('v3');
  });

  it('keepRemote loads the server version and clears the conflict', async () => {
    customizeLocally();
    useDashboardSyncStore.setState({ baseVersionId: 'v1', dirty: true });
    serve({ latest: () => summary('v2'), versions: { v2: { ...summary('v2'), layout: remoteLayout } } });
    const { result } = renderHook(() => useDashboardLayoutSync(), { wrapper });
    await waitFor(() => expect(result.current.conflict?.id).toBe('v2'));

    await act(() => result.current.keepRemote());

    expect(result.current.conflict).toBeNull();
    expect(useDashboardLayoutStore.getState().views[0]?.widgets).toEqual(remoteLayout.views[0]?.widgets);
    expect(useDashboardSyncStore.getState()).toMatchObject({ baseVersionId: 'v2', dirty: false });
  });

  it('restoreVersion applies an older version and saves it as the newest', async () => {
    useDashboardSyncStore.setState({ baseVersionId: 'v2' });
    let posted: { force?: boolean; layout?: unknown } | undefined;
    serve({
      latest: () => summary('v2'),
      versions: { v1: { ...summary('v1'), layout: remoteLayout } },
      onPost: (body) => {
        posted = body as { force?: boolean; layout?: unknown };
        return HttpResponse.json(summary('v3'), { status: 201 });
      },
    });
    const { result } = renderHook(() => useDashboardLayoutSync(), { wrapper });

    await act(() => result.current.restoreVersion('v1'));

    expect(posted).toMatchObject({ force: true, layout: remoteLayout });
    expect(useDashboardSyncStore.getState()).toMatchObject({ baseVersionId: 'v3', dirty: false });
  });

  it('adoptView puts one view of an older version into the local layout and saves', async () => {
    useDashboardSyncStore.setState({ baseVersionId: 'v2' });
    const oldLayout = {
      activeViewId: 'default',
      views: [
        { id: 'default', name: '', widgets: [] },
        { id: 'ops', name: 'Ops', widgets: [{ instanceId: 'todos', type: 'todos' }] },
      ],
    };
    const calls = serve({
      latest: () => summary('v2'),
      versions: { v1: { ...summary('v1', { viewCount: 2 }), layout: oldLayout } },
    });
    const { result } = renderHook(() => useDashboardLayoutSync(), { wrapper });

    await act(() => result.current.adoptView('v1', 'ops'));

    expect(useDashboardLayoutStore.getState().views.map((v) => v.id)).toEqual(['default', 'ops']);
    expect(calls).toContain('post');
  });

  it('keeps an edit made while a save is in flight as a local change', async () => {
    customizeLocally();
    useDashboardSyncStore.setState({ baseVersionId: 'v1', dirty: true });
    const { promise: postGate, release } = gate();
    const calls = serve({
      latest: () => summary('v1'),
      beforePost: postGate,
      onPost: () => HttpResponse.json(summary('v3'), { status: 201 }),
    });
    const { result } = renderHook(() => useDashboardLayoutSync(), { wrapper });

    const savePromise = act(() => result.current.saveSnapshot());
    await waitFor(() => expect(calls).toContain('post'));
    // Lands strictly between the request being sent and its response arriving.
    useDashboardLayoutStore.getState().setActiveViewWidgets([{ instanceId: 'recent-runs', type: 'recent-runs' }]);
    release();
    await savePromise;

    expect(useDashboardSyncStore.getState()).toMatchObject({ baseVersionId: 'v3', dirty: true });
  });

  it('re-checks for a local edit made while the version detail fetch is in flight', async () => {
    const { promise: detailGate, release } = gate();
    const calls = serve({
      latest: () => summary('v2'),
      versions: { v2: { ...summary('v2'), layout: remoteLayout } },
      beforeDetail: detailGate,
    });

    const { result } = renderHook(() => useDashboardLayoutSync(), { wrapper });

    await waitFor(() => expect(calls).toContain('get:v2'));
    // The version summary already matched with no local changes — the edit lands only now.
    useDashboardLayoutStore.getState().setActiveViewWidgets(localWidgets);
    release();

    await waitFor(() => expect(result.current.conflict?.id).toBe('v2'));
    expect(useDashboardLayoutStore.getState().views[0]?.widgets).toEqual(localWidgets);
  });

  it('keeps the layout dirty when a restore fails to save', async () => {
    useDashboardSyncStore.setState({ baseVersionId: 'v2' });
    serve({
      latest: () => summary('v2'),
      versions: { v1: { ...summary('v1'), layout: remoteLayout } },
      onPost: () => HttpResponse.json({ error: 'save failed' }, { status: 500 }),
    });
    const { result } = renderHook(() => useDashboardLayoutSync(), { wrapper });

    await act(() => result.current.restoreVersion('v1'));

    expect(useDashboardSyncStore.getState()).toMatchObject({ baseVersionId: 'v2', dirty: true });
    expect(useToastStore.getState().toasts.some((t) => t.type === 'error')).toBe(true);
  });

  it('shows an error toast when the version check after a 409 also fails', async () => {
    customizeLocally();
    useDashboardSyncStore.setState({ baseVersionId: 'v1', dirty: true });
    let listCalls = 0;
    serve({
      latest: () => {
        listCalls += 1;
        if (listCalls > 1) throw new Error('list failed');
        return summary('v1');
      },
      onPost: () => HttpResponse.json({ code: 'STATE_CONFLICT', error: 'changed' }, { status: 409 }),
    });
    const { result } = renderHook(() => useDashboardLayoutSync(), { wrapper });

    await act(() => result.current.saveSnapshot());

    expect(result.current.conflict).toBeNull();
    expect(useToastStore.getState().toasts.some((t) => t.type === 'error')).toBe(true);
  });
});
