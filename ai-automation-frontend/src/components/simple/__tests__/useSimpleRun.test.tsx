import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { Routes, Route } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { en } from '../../../i18n/en';
import type { Workflow } from '../../../types';
import { useSimpleRun } from '../useSimpleRun';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  useI18nStore.setState({ locale: 'en' });
});

function workflow(overrides: Partial<Workflow>): Workflow {
  return { id: 'wf-1', name: 'Digest', blocks: [], defaultContext: {}, ...overrides } as unknown as Workflow;
}

function Harness({ wf }: { wf: Workflow }) {
  const simpleRun = useSimpleRun();
  return (
    <>
      <button onClick={() => simpleRun.run(wf)}>start</button>
      {simpleRun.dialog}
    </>
  );
}

function renderHarness(wf: Workflow) {
  return renderWithProviders(
    <Routes>
      <Route path="/" element={<Harness wf={wf} />} />
      <Route path="/workflows/:id/run/:runId" element={<p>run page</p>} />
    </Routes>,
  );
}

function captureExecute() {
  const calls: Record<string, unknown>[] = [];
  server.use(
    http.post('/api/v1/workflows/:id/execute', async ({ request }) => {
      calls.push((await request.json()) as Record<string, unknown>);
      return HttpResponse.json({ id: 'run-7', workflowId: 'wf-1', status: 'RUNNING' });
    }),
  );
  return calls;
}

describe('useSimpleRun', () => {
  it('starts a workflow without inputs right away and opens its run page', async () => {
    const calls = captureExecute();
    renderHarness(workflow({}));

    fireEvent.click(screen.getByRole('button', { name: 'start' }));

    expect(await screen.findByText('run page')).toBeInTheDocument();
    expect(calls).toHaveLength(1);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('asks for inputs first and sends them as the run context', async () => {
    const calls = captureExecute();
    renderHarness(workflow({ blocks: [{ type: 'simple', config: { prompt: 'Sum up {{ topic }}' } }] as Workflow['blocks'] }));

    fireEvent.click(screen.getByRole('button', { name: 'start' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByRole('textbox'), { target: { value: 'weather' } });
    fireEvent.click(within(dialog).getByRole('button', { name: en.workflows.execute }));

    expect(await screen.findByText('run page')).toBeInTheDocument();
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0]).toEqual({ context: { topic: 'weather' } });
  });
});
