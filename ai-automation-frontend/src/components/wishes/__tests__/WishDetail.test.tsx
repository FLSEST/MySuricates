import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { useLocation } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { en } from '../../../i18n/en';
import type { Wish } from '../../../types';
import { WishDetail } from '../WishDetail';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => useI18nStore.setState({ locale: 'en' }));

function wish(overrides: Partial<Wish> = {}): Wish {
  return {
    id: 'wish-1', originalText: 'Build a weekly report', status: 'FAILED', projectId: 'p1', projectName: 'Alpha',
    decomposition: null, generatedWorkflowIds: [], generatedWorkflowNames: [], referenceWorkflowIds: [],
    referenceWorkflowNames: [], generatedChainId: null, iterationCount: 2, maxIterations: 3, currentRunId: null,
    resultSummary: null, error: null, metadata: {}, createdAt: '2026-09-20T10:00:00Z', updatedAt: '2026-09-20T10:00:00Z',
    autoRefine: false,
    iterationHistory: [
      { iteration: 1, runId: 'run-aaaaaaaa1', workflowIds: ['wf-bbbbbbbb1'], evaluation: { satisfied: false, summary: 'Too short', issues: ['No chart'] } },
      { iteration: 2, runId: 'run-cccccccc2', workflowIds: ['wf-bbbbbbbb1'], evaluation: { satisfied: true } },
    ],
    ...overrides,
  };
}

function serve(...wishes: Wish[]) {
  const responded: unknown[] = [];
  server.use(
    http.get('/api/v1/wishes/:id', ({ params }) => HttpResponse.json(wishes.find((w) => w.id === params.id))),
    http.post('/api/v1/wishes/:id/respond', async ({ request }) => {
      responded.push(await request.json());
      return HttpResponse.json(wishes[0]);
    }),
  );
  return responded;
}

/** The bar row of the status timeline sits right above its row of step labels. */
const timelineBars = () => screen.getByText(en.wishes.status.received).parentElement!.previousElementSibling!;

function Location() {
  return <div data-testid="location">{useLocation().pathname}</div>;
}

describe('WishDetail', { timeout: 15_000 }, () => {
  it('shows a failed wish with retry and delete and marks the failed step of the timeline', async () => {
    const responded = serve(wish({ status: 'FAILED', autoRefine: true }));
    const onDelete = vi.fn();
    renderWithProviders(<WishDetail wishId="wish-1" onCancel={vi.fn()} onDelete={onDelete} />);

    expect(await screen.findByText('Build a weekly report')).toBeInTheDocument();
    expect(screen.getByText(`${en.wishes.project}: Alpha`)).toBeInTheDocument();
    expect(screen.getByText(en.wishes.autoRefine.enabled)).toBeInTheDocument();
    expect(screen.getByText(`${en.wishes.iteration} 2 / 3`)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: en.wishes.cancel })).toBeNull();
    // FAILED is not on the timeline: no step is highlighted, none marked as failed
    expect(timelineBars().querySelectorAll('.bg-border')).toHaveLength(5);

    fireEvent.click(screen.getByTitle(en.common.delete));
    expect(onDelete).toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: en.wishes.retry }));
    await waitFor(() => expect(responded).toEqual([{ action: 'RETRY' }]));
  });

  it('shows a paused wish at its timeline position with the waiting label and a cancel-free header', async () => {
    serve(wish({ status: 'AWAITING_APPROVAL', iterationHistory: [] }));
    renderWithProviders(<WishDetail wishId="wish-1" onCancel={vi.fn()} onDelete={vi.fn()} />);

    await screen.findByText('Build a weekly report');
    const labels = within(timelineBars().nextElementSibling as HTMLElement);
    expect(labels.getByText(en.wishes.status.awaiting_approval)).toHaveClass('text-warning');
    expect(labels.queryByText(en.wishes.status.evaluating)).toBeNull();
    expect(timelineBars().querySelectorAll('.bg-success')).toHaveLength(3);
    expect(timelineBars().querySelectorAll('.bg-warning')).toHaveLength(1);
    // The approval panel below has its own cancel; the header offers none while the wish waits
    const header = within(screen.getByText('Build a weekly report').closest('.p-4') as HTMLElement);
    expect(header.queryByRole('button', { name: en.wishes.cancel })).toBeNull();
    expect(screen.queryByText(en.wishes.iterationHistory, { exact: false })).toBeNull();
  });

  it('offers cancel while the wish runs', async () => {
    serve(wish({ status: 'DECOMPOSING', iterationHistory: [] }));
    const onCancel = vi.fn();
    renderWithProviders(<WishDetail wishId="wish-1" onCancel={onCancel} onDelete={vi.fn()} />);
    fireEvent.click(await screen.findByRole('button', { name: en.wishes.cancel }));
    expect(onCancel).toHaveBeenCalled();
    expect(screen.queryByTitle(en.common.delete)).toBeNull();
  });

  it('unfolds the iteration history, links runs and keeps it open for the next wish', async () => {
    serve(wish(), wish({ id: 'wish-2', originalText: 'Second wish', generatedChainId: 'chain-9' }));
    const { rerender } = renderWithProviders(
      <><WishDetail wishId="wish-1" onCancel={vi.fn()} onDelete={vi.fn()} /><Location /></>,
    );
    fireEvent.click(await screen.findByText(`${en.wishes.iterationHistory} (2)`));
    expect(screen.getByText('Too short')).toBeInTheDocument();
    expect(screen.getByText('No chart')).toBeInTheDocument();
    expect(screen.getByText(en.wishes.historySatisfied)).toBeInTheDocument();

    fireEvent.click(screen.getByText(/run-aaaa/));
    expect(screen.getByTestId('location')).toHaveTextContent('/workflows/wf-bbbbbbbb1/run/run-aaaaaaaa1');

    rerender(<><WishDetail wishId="wish-2" onCancel={vi.fn()} onDelete={vi.fn()} /><Location /></>);
    expect(await screen.findByText('Second wish')).toBeInTheDocument();
    fireEvent.click(screen.getByText(/run-cccc/));
    expect(screen.getByTestId('location')).toHaveTextContent('/chains/chain-9/run/run-cccccccc2');
  });
});
