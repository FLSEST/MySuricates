import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import { en } from '../../i18n/en';
import type { WorkflowFactoryResult } from '../../types';
import { WorkflowFactory } from '../WorkflowFactory';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const result = (overrides: Partial<WorkflowFactoryResult>): WorkflowFactoryResult => ({
  workflowId: 'wf-new', workflowName: 'Review Pipeline', status: 'VERIFIED', testRunId: 'r1', testStatus: 'PASSED',
  testError: null, promotedAsTemplate: false, durationMs: 0, retryErrors: [], ...overrides,
});

let calls: string[];
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
  calls = [];
  server.use(
    http.get('/api/v1/testing/status', () => HttpResponse.json({ healthy: true })),
    http.get('/api/v1/factory/topics', () => HttpResponse.json([])),
    http.get('/api/v1/workflows', () => HttpResponse.json([
      { id: 'wf-old', projectId: 'p1', projectName: 'Alpha', name: 'Earlier Pipeline', description: 'From last week', blocks: [],
        metadata: { factoryGenerated: true }, testStatus: 'FAILED', runCount: 0, createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-01T00:00:00Z' },
      { id: 'w-hand', projectId: 'p1', projectName: 'Alpha', name: 'Hand-made', blocks: [], metadata: {}, runCount: 0,
        createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-01T00:00:00Z' },
    ])),
    http.post('/api/v1/factory/generate', () => {
      calls.push('generate');
      return HttpResponse.json({ id: 'wf-new', name: 'Review Pipeline' });
    }),
    http.post('/api/v1/factory/verify/:id', ({ params }) => {
      calls.push(`verify ${params.id}`);
      return HttpResponse.json(result({ workflowId: String(params.id), workflowName: params.id === 'wf-old' ? 'Earlier Pipeline' : 'Review Pipeline', durationMs: 65_000 }));
    }),
    http.post('/api/v1/factory/promote/:id', ({ params }) => {
      calls.push(`promote ${params.id}`);
      return HttpResponse.json(result({ workflowId: String(params.id), status: 'PROMOTED', promotedAsTemplate: true }));
    }),
  );
});

/** The card of one row, found by its workflow name. */
const card = (name: string) => screen.getByText(name).closest('.p-4') as HTMLElement;
// Select labels are not tied to their field; the project select is the one with this placeholder
const projectSelect = () => screen.getByRole('option', { name: en.common.selectProject }).closest('select')!;

async function createWorkflow() {
  fireEvent.change(screen.getByLabelText(en.factory.topicLabel), { target: { value: 'Code review' } });
  await waitFor(() => expect(within(projectSelect()).getByRole('option', { name: 'Test Project' })).toBeInTheDocument());
  fireEvent.change(projectSelect(), { target: { value: 'p1' } });
  fireEvent.click(screen.getByRole('button', { name: en.factory.createWorkflow }));
  await screen.findByText('CREATED');
}

describe('WorkflowFactory', () => {
  it('lists earlier factory workflows and replaces one by its verification result', async () => {
    renderWithProviders(<WorkflowFactory />);
    expect(await screen.findByText('Earlier Pipeline')).toBeInTheDocument();
    expect(screen.queryByText('Hand-made')).toBeNull();
    expect(within(card('Earlier Pipeline')).getByText('FAILED')).toBeInTheDocument();

    await waitFor(() => expect(within(card('Earlier Pipeline')).getByTitle(en.factory.verifyExisting)).toBeInTheDocument());
    fireEvent.click(within(card('Earlier Pipeline')).getByTitle(en.factory.verifyExisting));

    await waitFor(() => expect(within(card('Earlier Pipeline')).getByText('VERIFIED')).toBeInTheDocument());
    expect(calls).toEqual(['verify wf-old']);
    expect(screen.getAllByText('Earlier Pipeline')).toHaveLength(1);
    expect(within(card('Earlier Pipeline')).getByTitle(en.factory.promoteTemplate)).toBeInTheDocument();
  });

  it('creates a workflow, then verifies and promotes the same entry', async () => {
    renderWithProviders(<WorkflowFactory />);
    await createWorkflow();
    const created = card('Review Pipeline');
    expect(within(created).queryByTitle(en.factory.promoteTemplate)).toBeNull();
    fireEvent.click(within(created).getByTitle(en.factory.verifyExisting));

    await waitFor(() => expect(within(card('Review Pipeline')).getByText('VERIFIED')).toBeInTheDocument());
    expect(within(card('Review Pipeline')).getByText('1.1m')).toBeInTheDocument();
    fireEvent.click(within(card('Review Pipeline')).getByTitle(en.factory.promoteTemplate));

    await waitFor(() => expect(within(card('Review Pipeline')).getByText('PROMOTED')).toBeInTheDocument());
    expect(calls).toEqual(['generate', 'verify wf-new', 'promote wf-new']);
    expect(screen.getAllByText('Review Pipeline')).toHaveLength(1);
  });

  it('colours the status icons with theme tokens', async () => {
    renderWithProviders(<WorkflowFactory />);
    await createWorkflow();
    fireEvent.click(within(card('Review Pipeline')).getByTitle(en.factory.verifyExisting));
    await waitFor(() => expect(within(card('Review Pipeline')).getByTitle(en.factory.promoteTemplate)).toBeInTheDocument());
    fireEvent.click(within(card('Review Pipeline')).getByTitle(en.factory.promoteTemplate));
    await screen.findByText('PROMOTED');

    const icon = card('Review Pipeline').querySelector('svg');
    expect(icon).toHaveClass('text-warning');
    expect(document.querySelector('[class*="amber-"], [class*="violet-"], [class*="red-4"], [class*="yellow-4"]')).toBeNull();
  });
});
