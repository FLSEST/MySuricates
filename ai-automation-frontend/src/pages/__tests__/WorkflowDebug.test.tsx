import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import { WorkflowDebug } from '../WorkflowDebug';
import type { BrokenWorkflow } from '../../types';

const moduleState = { assistant: true };
vi.mock('../../hooks/useModules', () => ({
  useModuleEnabled: (name: string) => (name === 'assistant' ? moduleState.assistant : true),
}));

const broken: BrokenWorkflow = {
  workflowId: 'wf-1', workflowName: 'Nightly', projectName: 'P', runId: 'run-1', status: 'FAILED',
  errorMessage: 'boom', failedStepIndex: 0, failedBlockType: 'shell', startedAt: '2026-09-22T10:00:00Z',
  completedAt: '2026-09-22T10:01:00Z', durationMs: 60_000, triggeredByScheduleId: null,
};

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  useI18nStore.setState({ locale: 'en' });
  moduleState.assistant = true;
  server.use(
    http.get('/api/v1/debug/failed-workflows', () => HttpResponse.json([broken])),
    http.get('/api/v1/dlq', () => HttpResponse.json({ content: [], totalElements: 3, totalPages: 3, number: 0, size: 1 })),
  );
});

// d75fbfef: the DLQ had no way in, and the diagnosis button opened a panel that may not exist
describe('WorkflowDebug', () => {
  it('links to the dead-letter queue with the number of open entries', async () => {
    renderWithProviders(<WorkflowDebug />);

    const link = await screen.findByTestId('dlq-link');
    expect(link).toHaveAttribute('href', '/dlq');
    expect(await screen.findByText('(3 open)')).toBeInTheDocument();
  });

  it('offers the diagnosis only with the assistant module', async () => {
    renderWithProviders(<WorkflowDebug />);
    expect(await screen.findByText('Nightly')).toBeInTheDocument();
    expect(screen.queryByTitle('Ask the assistant why this run failed')).not.toBeNull();
  });

  it('hides the diagnosis without the assistant module', async () => {
    moduleState.assistant = false;
    renderWithProviders(<WorkflowDebug />);

    expect(await screen.findByText('Nightly')).toBeInTheDocument();
    expect(screen.queryByTitle('Ask the assistant why this run failed')).toBeNull();
  });
});
