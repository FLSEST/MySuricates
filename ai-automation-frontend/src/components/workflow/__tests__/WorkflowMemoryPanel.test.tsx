import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { failOnUnhandledRequest } from '../../../test/msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { de } from '../../../i18n/de';
import { en } from '../../../i18n/en';
import type { WorkflowMemory, WorkflowRun } from '../../../types';
import { WorkflowMemoryPanel } from '../WorkflowMemoryPanel';

const memory: WorkflowMemory = {
  id: 'm1', workflowId: 'w1', title: 'Lessons', content: 'remember this', tags: [],
  sourceRunIds: ['r1'], provider: 'CLAUDE', active: true, createdAt: '2026-09-01T10:00:00Z',
};
const run = { id: 'r1', workflowId: 'w1', status: 'COMPLETED' } as Omit<WorkflowRun, 'steps'>;

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => {
  server.resetHandlers();
  useI18nStore.setState({ locale: 'en' });
});
afterAll(() => server.close());

function serveMemories() {
  server.use(http.get('/api/v1/workflows/:id/memory', () => HttpResponse.json([memory])));
}

// 442702e2: with memory versions but no runs left the button was greyed out without a word why.
describe('WorkflowMemoryPanel', () => {
  it.each([['en', en], ['de', de]] as const)('says why extraction is locked when the workflow has no runs (%s)', async (locale, texts) => {
    useI18nStore.setState({ locale });
    serveMemories();
    renderWithProviders(<WorkflowMemoryPanel workflowId="w1" runs={[]} runTotal={0} />);

    expect(await screen.findByText('Lessons')).toBeInTheDocument();
    const button = screen.getByRole('button', { name: texts.memory.extractKnowledge });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('title', texts.memory.extractNeedsRuns);
    expect(screen.getByTestId('memory-extract-needs-runs')).toHaveTextContent(texts.memory.extractNeedsRuns);
  });

  it('offers extraction without the hint when there are runs', async () => {
    serveMemories();
    renderWithProviders(<WorkflowMemoryPanel workflowId="w1" runs={[run]} runTotal={1} />);

    expect(await screen.findByText('Lessons')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: en.memory.extractKnowledge })).toBeEnabled();
    expect(screen.queryByTestId('memory-extract-needs-runs')).not.toBeInTheDocument();
  });

  it('counts all runs, not only the shown page, and stays quiet while the runs load', async () => {
    serveMemories();
    const { unmount } = renderWithProviders(<WorkflowMemoryPanel workflowId="w1" runs={[]} runTotal={12} />);
    expect(await screen.findByText('Lessons')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: en.memory.extractKnowledge })).toBeEnabled();
    unmount();

    renderWithProviders(<WorkflowMemoryPanel workflowId="w1" />);
    expect(await screen.findByText('Lessons')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: en.memory.extractKnowledge })).toBeDisabled();
    expect(screen.queryByTestId('memory-extract-needs-runs')).not.toBeInTheDocument();
  });
});
