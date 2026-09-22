import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { RefusedStartsNotice } from '../RefusedStartsNotice';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => { localStorage.clear(); useI18nStore.setState({ locale: 'en' }); });

const base = { problem: 'NONE', detail: null, changedSkills: [], skillsPinned: true, signed: true, pinnedSkillCount: 0, sealedAt: null, lastRefusal: null };

describe('RefusedStartsNotice', () => {
  it('lists workflows whose starts were refused, since those leave no run row', async () => {
    server.use(http.get('/api/v1/workflows/seal-status', () => HttpResponse.json([
      { ...base, workflowId: 'wf-1', workflowName: 'Deploy', state: 'VIOLATED', refusedStarts: 2, lastRefusedAt: new Date().toISOString() },
      { ...base, workflowId: 'wf-2', workflowName: 'Nightly', state: 'NOT_SEALED', refusedStarts: 1, lastRefusedAt: new Date().toISOString() },
      { ...base, workflowId: 'wf-3', workflowName: 'Calm', state: 'INTACT', refusedStarts: 0, lastRefusedAt: null },
    ])));

    renderWithProviders(<RefusedStartsNotice />);

    const notice = await screen.findByTestId('refused-starts-notice');
    expect(notice).toHaveTextContent('3 start(s) refused in the last 7 days');
    expect(screen.getByRole('link', { name: 'Deploy' })).toHaveAttribute('href', '/workflows/wf-1');
    expect(screen.getByRole('link', { name: 'Nightly' })).toBeInTheDocument();
    expect(screen.queryByText('Calm')).not.toBeInTheDocument();
  });

  it('stays hidden when nothing was refused', async () => {
    renderWithProviders(<RefusedStartsNotice />);
    await waitFor(() => expect(screen.queryByTestId('refused-starts-notice')).not.toBeInTheDocument());
  });
});
