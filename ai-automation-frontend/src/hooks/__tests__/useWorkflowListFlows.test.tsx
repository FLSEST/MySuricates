import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { useI18nStore } from '../../i18n';
import { useSettingsStore } from '../../stores/settingsStore';
import { useWorkflowCreateFlow } from '../useWorkflowCreateFlow';
import { useWorkflowExecuteFlow } from '../useWorkflowExecuteFlow';
import { useWorkflowImportFlow } from '../useWorkflowImportFlow';
import type { PoolWorkflowEntry, Workflow, WorkflowExportData, WorkflowTemplate } from '../../types';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  useI18nStore.setState({ locale: 'de' });
  useSettingsStore.setState({ autoTodoDefault: false });
});

/** Router around the hook; `location.current` follows navigate(). */
function setup<T>(hook: () => T) {
  const location = { current: '' };
  function Spy() {
    location.current = useLocation().pathname;
    return null;
  }
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/workflows']}>
        {children}
        <Routes><Route path="*" element={<Spy />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
  return { ...renderHook(hook, { wrapper }), location };
}

const template = {
  id: 't1', name: { de: 'Prüfen', en: 'Check' }, description: { de: 'Prüft', en: 'Checks' }, icon: 'x',
  blocks: [{ type: 'shell', config: { command: 'true' } }], instructions: null, model: 'sonnet',
} as unknown as WorkflowTemplate;

const poolEntry = {
  id: 'pool-1', name: { de: 'Aufräumen', en: 'Cleanup' }, description: { de: 'Räumt auf', en: 'Cleans' }, icon: 'y',
  blocks: [], instructions: 'Be tidy', model: null, defaultContext: { days: 7 },
} as unknown as PoolWorkflowEntry;

describe('useWorkflowCreateFlow', () => {
  it('fills the form from a template in the app language and creates the workflow', async () => {
    const bodies: unknown[] = [];
    server.use(http.post('/api/v1/workflows', async ({ request }) => {
      bodies.push(await request.json());
      return HttpResponse.json({ id: 'w-new' });
    }));
    useSettingsStore.setState({ autoTodoDefault: true });
    const { result, location } = setup(useWorkflowCreateFlow);

    act(() => result.current.openCreate());
    expect(result.current.createStep).toBe('template');
    act(() => result.current.selectTemplate(template));
    expect(result.current.createStep).toBe('details');
    expect(result.current.form).toMatchObject({ projectId: '', name: 'Prüfen', description: 'Prüft', model: 'sonnet', templateId: 't1' });

    act(() => result.current.setForm({ ...result.current.form, projectId: 'p1' }));
    act(() => result.current.create());
    await waitFor(() => expect(location.current).toBe('/workflows/w-new'));
    expect(bodies[0]).toMatchObject({ projectId: 'p1', templateId: 't1', metadata: { autoTodo: { enabled: true } } });
    expect(result.current.createStep).toBe('closed');
    expect(result.current.form).toEqual({ projectId: '', name: '' });
  });

  it('installs a pool entry through the pool once a project is chosen', async () => {
    const installs: unknown[] = [];
    server.use(http.post('/api/v1/pool/:id/install', async ({ params, request }) => {
      installs.push([params.id, await request.json()]);
      return HttpResponse.json({ workflowId: 'w-pool', workflowName: 'Aufräumen', created: true });
    }));
    const { result, location } = setup(useWorkflowCreateFlow);

    act(() => result.current.selectPoolEntry(poolEntry));
    expect(result.current.form).toMatchObject({
      name: 'Aufräumen', instructions: 'Be tidy', metadata: { poolSource: 'pool-1' }, defaultContext: { days: '7' },
    });
    act(() => result.current.setForm({ ...result.current.form, projectId: 'p1' }));
    act(() => result.current.create());
    await waitFor(() => expect(location.current).toBe('/workflows/w-pool'));
    expect(installs).toEqual([['pool-1', { projectId: 'p1', name: 'Aufräumen', locale: 'de' }]]);
  });

  it('starts blank without a template and closes again', () => {
    const { result } = setup(useWorkflowCreateFlow);
    act(() => result.current.selectTemplate(null));
    expect(result.current.form).toEqual({ projectId: '', name: '' });
    act(() => result.current.backToTemplates());
    expect(result.current.createStep).toBe('template');
    act(() => result.current.close());
    expect(result.current.createStep).toBe('closed');
  });
});

describe('useWorkflowExecuteFlow', () => {
  const withVariables = { id: 'w1', blocks: [{ type: 'shell', config: { command: 'echo {{ env }}' } }] } as unknown as Workflow;
  const plain = { id: 'w2', blocks: [{ type: 'shell', config: { command: 'echo hi' } }] } as unknown as Workflow;

  it('starts a workflow without variables at once and opens the run', async () => {
    server.use(http.post('/api/v1/workflows/:id/execute', () => HttpResponse.json({ id: 'run-1' })));
    const { result, location } = setup(useWorkflowExecuteFlow);
    act(() => result.current.execute(plain));
    expect(result.current.executeDialog.open).toBe(false);
    await waitFor(() => expect(location.current).toBe('/workflows/w2/run/run-1'));
  });

  it('asks for the variables first and starts with them', async () => {
    const bodies: unknown[] = [];
    server.use(http.post('/api/v1/workflows/:id/execute', async ({ request }) => {
      bodies.push(await request.json());
      return HttpResponse.json({ id: 'run-2' });
    }));
    const { result, location } = setup(useWorkflowExecuteFlow);
    act(() => result.current.execute(withVariables));
    expect(result.current.executeDialog).toEqual({ open: true, workflow: withVariables, variables: ['env'] });
    expect(bodies).toEqual([]);

    act(() => result.current.executeWithContext({ env: 'prod' }));
    await waitFor(() => expect(location.current).toBe('/workflows/w1/run/run-2'));
    expect(bodies).toEqual([{ context: { env: 'prod' } }]);
    expect(result.current.executeDialog.open).toBe(false);
  });
});

describe('useWorkflowImportFlow', () => {
  it('opens with the file name, imports under the chosen name and closes on success', async () => {
    const bodies: unknown[] = [];
    server.use(http.post('/api/v1/workflows/import', async ({ request }) => {
      bodies.push(await request.json());
      return HttpResponse.json({ id: 'w-imp' });
    }));
    const { result } = setup(useWorkflowImportFlow);
    act(() => result.current.importReady({ name: 'Exported', blocks: [] } as unknown as WorkflowExportData));
    expect(result.current).toMatchObject({ showImport: true, importName: 'Exported', importProjectId: '' });

    act(() => result.current.setImportName('   '));
    act(() => result.current.runImport());
    act(() => result.current.setImportName('Renamed'));
    act(() => result.current.setImportProjectId('p1'));
    act(() => result.current.runImport());
    await waitFor(() => expect(result.current.showImport).toBe(false));
    expect(bodies).toHaveLength(1);
    expect(bodies[0]).toMatchObject({ name: 'Renamed', projectId: 'p1' });
    expect(result.current.importData).toBeNull();
  });
  it('sends the version label of the export along (62422aef)', async () => {
    const bodies: unknown[] = [];
    server.use(http.post('/api/v1/workflows/import', async ({ request }) => {
      bodies.push(await request.json());
      return HttpResponse.json({ id: 'w-imp' });
    }));
    const { result } = setup(useWorkflowImportFlow);
    act(() => result.current.importReady({ name: 'Pool entry', blocks: [], version: '4.4.0' } as unknown as WorkflowExportData));
    act(() => result.current.setImportProjectId('p1'));
    act(() => result.current.runImport());
    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toMatchObject({ name: 'Pool entry', projectId: 'p1', version: '4.4.0' });
  });
});
