import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { fireEvent, screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import type { PendingInput } from '../../hooks/usePendingInputs';
import { Approvals } from '../Approvals';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => useI18nStore.setState({ locale: 'en' }));

const now = Date.now();
const approval: PendingInput = {
  runId: 'run-1',
  workflowId: 'wf-1',
  workflowName: 'Walkthrough illustrations',
  type: 'approval',
  blockId: 'approval-30',
  question: '## Measurement\n\n12 images, all 1024px\n\nAdopt the set?',
  options: ['Ja', 'Nein'],
  rejectOptions: ['Nein'],
  stepIndex: 30,
  requestedAt: new Date(now - 5 * 60_000).toISOString(),
  timeoutAt: new Date(now + 3 * 3_600_000).toISOString(),
};
const humanInput: PendingInput = {
  runId: 'run-2',
  workflowId: null,
  workflowName: null,
  type: 'human-input',
  blockId: 'human-input-2',
  question: 'Which file?',
  options: [],
  rejectOptions: [],
  stepIndex: 2,
  requestedAt: new Date(now - 60_000).toISOString(),
  timeoutAt: null,
};

describe('Approvals page', () => {
  it('shows each waiting run with its rendered question, timing and a link to the run', async () => {
    server.use(http.get('/api/v1/executions/pending-input', () => HttpResponse.json([approval, humanInput])));

    renderWithProviders(<Approvals />);

    const card = await screen.findByRole('article', { name: 'Walkthrough illustrations' });
    expect(within(card).getByRole('heading', { name: 'Measurement' })).toBeInTheDocument();
    expect(within(card).getByText(/Asked 5 minutes ago/)).toBeInTheDocument();
    expect(within(card).getByText(/Times out in 3 hours/)).toBeInTheDocument();
    expect(within(card).getByRole('link', { name: /Open run/ })).toHaveAttribute('href', '/workflows/wf-1/run/run-1');
    // the backend's reject list decides the danger button — "Nein" here, not a hard-coded "No"
    expect(within(card).getByRole('button', { name: 'Nein' }).className).toContain('bg-danger');
    expect(within(card).getByRole('button', { name: 'Ja' }).className).not.toContain('bg-danger');
  });

  it('human input only links to the run — no answer buttons', async () => {
    server.use(http.get('/api/v1/executions/pending-input', () => HttpResponse.json([humanInput])));

    renderWithProviders(<Approvals />);

    const card = await screen.findByRole('article', { name: 'Deleted workflow' });
    expect(within(card).getByText(/Enter it on the run page/)).toBeInTheDocument();
    expect(within(card).queryByRole('button')).not.toBeInTheDocument();
    expect(within(card).getByRole('link', { name: /Open run/ })).toHaveAttribute('href', '/runs/run-2');
  });

  it('answers an approval in place', async () => {
    let posted: { runId: string; body: unknown } | null = null;
    server.use(
      http.get('/api/v1/executions/pending-input', () => HttpResponse.json([approval])),
      http.post('/api/v1/executions/:runId/approve', async ({ params, request }) => {
        posted = { runId: params.runId as string, body: await request.json() };
        return new HttpResponse(null, { status: 200 });
      }),
    );

    renderWithProviders(<Approvals />);
    fireEvent.click(await screen.findByRole('button', { name: 'Nein' }));

    await expect.poll(() => posted).toEqual({ runId: 'run-1', body: { answer: 'Nein' } });
  });

  it('says so when nothing is waiting', async () => {
    renderWithProviders(<Approvals />);

    expect(await screen.findByText('Nothing is waiting')).toBeInTheDocument();
  });
});
