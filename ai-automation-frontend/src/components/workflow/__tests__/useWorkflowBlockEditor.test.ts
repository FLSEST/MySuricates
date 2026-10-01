import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { revealTextFor, useWorkflowBlockEditor } from '../useWorkflowBlockEditor';
import type { BlockDefinition, Workflow } from '../../../types';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  return ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client: qc }, children);
}

const BLOCKS: BlockDefinition[] = [
  { type: 'shell', name: 'build', config: { command: 'make' }, requiredCapabilities: ['docker'] },
  { type: 'switch', config: { expression: '{{ x }}', cases: [{ value: 'a' }, { value: '' }], default: 'b' } },
  { type: 'approval', config: {} },
];

const workflow = { id: 'wf-1', blocks: BLOCKS } as unknown as Workflow;

/** Records every PUT /workflows/:id body. */
function recordUpdates(bodies: unknown[]) {
  server.use(http.put('/api/v1/workflows/:id', async ({ request }) => {
    bodies.push(await request.json());
    return HttpResponse.json(workflow);
  }));
}

describe('revealTextFor', () => {
  it('points at the primary field of a block, or at the part chosen in the diagram', () => {
    expect(revealTextFor(BLOCKS, { blockIndex: 0 })).toBe('"command"');
    expect(revealTextFor(BLOCKS, { blockIndex: 1 })).toBe('"expression"');
    expect(revealTextFor(BLOCKS, { blockIndex: 1, sub: { kind: 'case', caseIndex: 0 } })).toBe('"a"');
    expect(revealTextFor(BLOCKS, { blockIndex: 1, sub: { kind: 'case', caseIndex: 1 } })).toBe('"cases"');
    expect(revealTextFor(BLOCKS, { blockIndex: 1, sub: { kind: 'default' } })).toBe('"default"');
    expect(revealTextFor(BLOCKS, { blockIndex: 0, sub: { kind: 'else' } })).toBe('"else_command"');
    // Primary field missing in the config, or no such block: nothing to jump to
    expect(revealTextFor(BLOCKS, { blockIndex: 2 })).toBeUndefined();
    expect(revealTextFor(BLOCKS, { blockIndex: 9 })).toBeUndefined();
  });
});

describe('useWorkflowBlockEditor', () => {
  it('adds the drafted block at the end and starts the next draft empty', async () => {
    const bodies: unknown[] = [];
    recordUpdates(bodies);
    const { result } = renderHook(() => useWorkflowBlockEditor(workflow), { wrapper: createWrapper() });

    act(() => result.current.setShowAddBlock(true));
    act(() => result.current.setNewBlock({ type: 'shell', name: ' lint ', config: '{"command":"npm run lint"}', skillIds: [] }));
    act(() => result.current.addBlock());

    await waitFor(() => expect(result.current.showAddBlock).toBe(false));
    expect(bodies).toEqual([{ blocks: [...BLOCKS, { type: 'shell', name: 'lint', config: { command: 'npm run lint' } }] }]);
    expect(result.current.newBlock.name).toBe('');
  });

  it('saves nothing while the JSON is invalid', async () => {
    const bodies: unknown[] = [];
    recordUpdates(bodies);
    const { result } = renderHook(() => useWorkflowBlockEditor(workflow), { wrapper: createWrapper() });

    act(() => result.current.setShowAddBlock(true));
    act(() => result.current.setNewBlock({ type: 'shell', name: '', config: '{ broken', skillIds: [] }));
    act(() => result.current.addBlock());
    await new Promise((r) => setTimeout(r, 30));
    expect(bodies).toEqual([]);
    expect(result.current.showAddBlock).toBe(true);
  });

  it('opens a block from the diagram at the chosen line and saves it with its untouched fields', async () => {
    const bodies: unknown[] = [];
    recordUpdates(bodies);
    const { result } = renderHook(() => useWorkflowBlockEditor(workflow), { wrapper: createWrapper() });

    act(() => result.current.selectFromDiagram({ blockIndex: 0 }));
    expect(result.current.showEditBlock).toBe(true);
    expect(result.current.editBlockIndex).toBe(0);
    expect(result.current.reveal?.text).toBe('"command"');
    expect(JSON.parse(result.current.editBlock.config)).toEqual({ command: 'make' });

    act(() => result.current.setEditBlock({ ...result.current.editBlock, config: '{"command":"make all"}' }));
    act(() => result.current.saveBlock());
    await waitFor(() => expect(result.current.showEditBlock).toBe(false));
    expect(bodies).toEqual([{ blocks: [
      { type: 'shell', name: 'build', config: { command: 'make all' }, requiredCapabilities: ['docker'] },
      BLOCKS[1], BLOCKS[2],
    ] }]);
  });

  it('opens a block from the list without a jump target and removes blocks by index', async () => {
    const bodies: unknown[] = [];
    recordUpdates(bodies);
    const { result } = renderHook(() => useWorkflowBlockEditor(workflow), { wrapper: createWrapper() });

    act(() => result.current.openEditBlock(2));
    expect(result.current.reveal).toBeNull();
    act(() => result.current.openEditBlock(7));   // no such block: stays on block 2
    expect(result.current.editBlockIndex).toBe(2);

    act(() => result.current.removeBlock(1));
    await waitFor(() => expect(bodies).toEqual([{ blocks: [BLOCKS[0], BLOCKS[2]] }]));
  });

  it('does nothing while the workflow is still loading', async () => {
    const bodies: unknown[] = [];
    recordUpdates(bodies);
    const { result } = renderHook(() => useWorkflowBlockEditor(undefined), { wrapper: createWrapper() });
    act(() => {
      result.current.addBlock();
      result.current.removeBlock(0);
      result.current.saveBlock();
      result.current.selectFromDiagram({ blockIndex: 0 });
    });
    await new Promise((r) => setTimeout(r, 30));
    expect(bodies).toEqual([]);
    expect(result.current.showEditBlock).toBe(false);
  });
});
