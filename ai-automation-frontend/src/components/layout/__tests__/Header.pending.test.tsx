import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { Header } from '../Header';

// The sidebar is closed on mobile, so the menu button has to carry the signal.
vi.mock('../../../hooks/useIsMobile', () => ({ useIsMobile: () => true }));

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => useI18nStore.setState({ locale: 'en' }));

describe('Header on mobile', () => {
  it('marks the menu button while a run waits for an answer', async () => {
    server.use(http.get('/api/v1/executions/pending-input', () => HttpResponse.json([{
      runId: 'r1', workflowId: 'wf-1', workflowName: 'WF', type: 'approval', blockId: 'approval-1', question: 'Go?',
      options: ['Yes', 'No'], rejectOptions: ['No'], stepIndex: 1, requestedAt: new Date().toISOString(), timeoutAt: null,
    }])));

    renderWithProviders(<Header />);

    const button = await screen.findByRole('button', { name: /Approvals: 1 waiting for you/ }, { timeout: 5000 });
    expect(button.querySelector('.animate-ping')).not.toBeNull();
  }, 10_000);

  it('leaves the menu button plain while nothing waits', async () => {
    renderWithProviders(<Header />);

    const button = await screen.findByRole('button', { name: 'Menu' }, { timeout: 5000 });
    expect(button.querySelector('.animate-ping')).toBeNull();
  }, 10_000);
});
