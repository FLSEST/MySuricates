import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { useSettingsStore } from '../../../stores/settingsStore';
import { Sidebar } from '../Sidebar';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
  useSettingsStore.setState({ simpleMode: false, sidebarItemOrder: [], sidebarHiddenItems: [] });
});

const waiting = (runId: string) => ({
  runId, workflowId: 'wf-1', workflowName: 'WF', type: 'approval', blockId: 'approval-1',
  question: 'Go?', options: ['Yes', 'No'], rejectOptions: ['No'], stepIndex: 1,
  requestedAt: new Date().toISOString(), timeoutAt: null,
});

describe('Sidebar approvals entry', () => {
  it('appears with the count while runs wait, and leads to the approvals page', async () => {
    server.use(http.get('/api/v1/executions/pending-input', () => HttpResponse.json([waiting('r1'), waiting('r2')])));

    renderWithProviders(<Sidebar />);

    const link = await screen.findByRole('link', { name: 'Approvals: 2 waiting for you' });
    expect(link).toHaveAttribute('href', '/approvals');
    expect(link.querySelector('.animate-ping')).not.toBeNull();
  });

  it('also shows in simple mode, where the regular list is reduced', async () => {
    useSettingsStore.setState({ simpleMode: true });
    server.use(http.get('/api/v1/executions/pending-input', () => HttpResponse.json([waiting('r1')])));

    renderWithProviders(<Sidebar />);

    expect(await screen.findByRole('link', { name: 'Approvals: 1 waiting for you' })).toBeInTheDocument();
  });

  it('is absent while nothing waits', async () => {
    renderWithProviders(<Sidebar />);

    await waitFor(() => expect(screen.getByRole('link', { name: 'Workflows' })).toBeInTheDocument());
    expect(screen.queryByRole('link', { name: /Approvals/ })).not.toBeInTheDocument();
  });
});
