import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { useWorkflowDetailActions } from '../useWorkflowDetailActions';
import type { Workflow } from '../../types';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const workflow = {
  id: 'wf-1', projectId: 'p1', name: 'Deploy', description: 'Ships it', icon: '🚀',
  blocks: [{ type: 'shell', config: { command: 'deploy {{ env }}' } }],
  activatedSkillIds: ['s1'], instructions: 'Be careful', model: 'sonnet',
} as unknown as Workflow;
const noVariables = { ...workflow, blocks: [{ type: 'shell', config: { command: 'deploy' } }] } as unknown as Workflow;

/** Router around the hook; `location.current` follows navigate(). */
function setup(wf: Workflow | undefined) {
  const location = { current: '' };
  function Spy() {
    location.current = useLocation().pathname;
    return null;
  }
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/workflows/wf-1']}>
        {children}
        <Routes><Route path="*" element={<Spy />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
  const hook = renderHook(() => useWorkflowDetailActions(wf), { wrapper });
  return { ...hook, location };
}

describe('useWorkflowDetailActions', () => {
  it('asks for the variables first when the workflow has some, and starts the run with them', async () => {
    const bodies: unknown[] = [];
    server.use(http.post('/api/v1/workflows/:id/execute', async ({ request }) => {
      bodies.push(await request.json());
      return HttpResponse.json({ id: 'run-7', workflowId: 'wf-1' });
    }));
    const { result, location } = setup(workflow);
    expect(result.current.workflowVariables).toEqual(['env']);

    act(() => result.current.execute());
    expect(result.current.showExecuteDialog).toBe(true);
    expect(bodies).toEqual([]);

    act(() => result.current.executeWithContext({ env: 'prod' }));
    await waitFor(() => expect(location.current).toBe('/workflows/wf-1/run/run-7'));
    expect(result.current.showExecuteDialog).toBe(false);
    expect(bodies).toEqual([{ context: { env: 'prod' } }]);
  });

  it('starts a workflow without variables at once', async () => {
    server.use(http.post('/api/v1/workflows/:id/execute', () => HttpResponse.json({ id: 'run-8', workflowId: 'wf-1' })));
    const { result, location } = setup(noVariables);
    act(() => result.current.execute());
    expect(result.current.showExecuteDialog).toBe(false);
    await waitFor(() => expect(location.current).toBe('/workflows/wf-1/run/run-8'));
  });

  it('clones into the chosen project and opens the copy', async () => {
    const created: unknown[] = [];
    server.use(http.post('/api/v1/workflows', async ({ request }) => {
      created.push(await request.json());
      return HttpResponse.json({ id: 'wf-2' });
    }));
    const { result, location } = setup(workflow);

    act(() => result.current.openClone());
    expect(result.current.cloneForm).toEqual({ projectId: 'p1', name: 'Deploy (Copy)' });
    act(() => result.current.setCloneForm({ projectId: 'p2', name: 'Deploy staging' }));
    act(() => result.current.clone());

    await waitFor(() => expect(location.current).toBe('/workflows/wf-2'));
    expect(created).toEqual([{
      projectId: 'p2', name: 'Deploy staging', description: 'Ships it', icon: '🚀', blocks: workflow.blocks,
      activatedSkillIds: ['s1'], instructions: 'Be careful', model: 'sonnet',
    }]);
    expect(result.current.showClone).toBe(false);
  });

  it('deletes and goes back to the list', async () => {
    server.use(http.delete('/api/v1/workflows/:id', () => new HttpResponse(null, { status: 204 })));
    const { result, location } = setup(workflow);
    act(() => result.current.remove());
    await waitFor(() => expect(location.current).toBe('/workflows'));
  });

  it('shows an optimization for review and applies only the suggested parts', async () => {
    const updates: unknown[] = [];
    server.use(
      http.post('/api/v1/workflows/:id/optimize', () => HttpResponse.json({
        suggestedName: 'Deploy safely', suggestedDescription: '', blocks: [],
      })),
      http.put('/api/v1/workflows/:id', async ({ request }) => {
        updates.push(await request.json());
        return HttpResponse.json(workflow);
      }),
    );
    const { result } = setup(workflow);

    act(() => result.current.optimize());
    await waitFor(() => expect(result.current.showOptimizeResult).toBe(true));
    act(() => result.current.applyOptimize());
    await waitFor(() => expect(result.current.showOptimizeResult).toBe(false));
    expect(updates).toEqual([{ name: 'Deploy safely' }]);
  });

  it('edits name and instructions and activates a skill', async () => {
    const updates: unknown[] = [];
    const skills: unknown[] = [];
    server.use(
      http.put('/api/v1/workflows/:id', async ({ request }) => {
        updates.push(await request.json());
        return HttpResponse.json(workflow);
      }),
      http.post('/api/v1/workflows/:id/skills', async ({ request }) => {
        skills.push(await request.json());
        return HttpResponse.json({});
      }),
    );
    const { result } = setup(workflow);

    act(() => result.current.openEdit());
    expect(result.current.editForm).toEqual({ name: 'Deploy', description: 'Ships it', icon: '🚀' });
    act(() => result.current.setEditForm({ name: 'Deploy v2', description: 'Ships it', icon: '🚀' }));
    act(() => result.current.update());
    await waitFor(() => expect(result.current.showEdit).toBe(false));

    act(() => result.current.openInstructions());
    expect(result.current.editInstructions).toBe('Be careful');
    act(() => result.current.setEditInstructions('Be quick'));
    act(() => result.current.saveInstructions());
    await waitFor(() => expect(result.current.showEditInstructions).toBe(false));
    expect(updates).toEqual([{ name: 'Deploy v2', description: 'Ships it', icon: '🚀' }, { instructions: 'Be quick' }]);

    act(() => result.current.activateSkill());   // nothing chosen yet
    act(() => result.current.setShowAddSkill(true));
    act(() => result.current.setSelectedSkillId('s2'));
    act(() => result.current.activateSkill());
    await waitFor(() => expect(result.current.showAddSkill).toBe(false));
    expect(skills).toEqual([{ skillIds: ['s1', 's2'] }]);
    expect(result.current.selectedSkillId).toBe('');
  });
});
