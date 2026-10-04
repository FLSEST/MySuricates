import { failOnUnhandledRequest } from '../../../test/msw';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { en } from '../../../i18n/en';
import type { SyncTarget } from '../../../types';
import { StagedTargetsCard } from '../StagedTargetsCard';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => useI18nStore.setState({ locale: 'en' }));

const target = (over: Partial<SyncTarget>): SyncTarget => ({
  name: 'test', url: 'http://test:9090', keyPresent: true, reachable: true, sealedOnly: false, version: '0.8.0', ...over,
});

const preview = {
  projects: [{ id: 'p1', name: 'Ops', projectName: null, action: 'SKIP', diff: null }],
  workflows: [{ id: 'w1', name: 'Deploy', projectName: 'Ops', action: 'NEW', diff: null }],
  skills: [],
  chains: [],
};

describe('StagedTargetsCard (84a78bb7)', () => {
  it('names each target with its state; a missing key comes with the command that fetches it', () => {
    renderWithProviders(<StagedTargetsCard targets={[
      target({}),
      target({ name: 'prod', url: 'http://prod:9090', keyPresent: false, sealedOnly: true }),
    ]} />);

    const test = within(screen.getByTestId('staged-target-test'));
    expect(test.getByText(en.settings.stagedReachable)).toBeInTheDocument();
    expect(test.getByRole('button', { name: 'Transfer to Test' })).toBeEnabled();

    const prod = within(screen.getByTestId('staged-target-prod'));
    expect(prod.getByText(en.settings.stagedKeyMissing)).toBeInTheDocument();
    expect(prod.getByText('scripts/instance.sh sync-keys --name dev')).toBeInTheDocument();
    expect(prod.getByText(en.settings.stagedSealedOnly)).toBeInTheDocument();
    expect(prod.getByRole('button', { name: 'Transfer to Prod' })).toBeDisabled();
  });

  it('previews and transfers to the chosen target only', async () => {
    let body: unknown = null;
    server.use(
      http.get('/api/v1/staging/targets/prod/sync-preview', () => HttpResponse.json(preview)),
      http.post('/api/v1/staging/targets/prod/sync-definitions', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ projectsSynced: 0, workflowsSynced: 1, skillsSynced: 0, chainsSynced: 0, skipped: 0, errors: [], preSyncBackup: 'pre.db' });
      }),
    );
    renderWithProviders(<StagedTargetsCard targets={[target({ name: 'prod', url: 'http://prod:9090', sealedOnly: true })]} />);

    fireEvent.click(screen.getByRole('button', { name: 'Transfer to Prod' }));
    const dialog = await screen.findByRole('dialog');
    await within(dialog).findByText('Deploy');
    fireEvent.click(within(dialog).getByRole('button', { name: en.settings.syncItemCount.replace('{count}', '1') }));

    await waitFor(() => expect(body).toEqual(expect.objectContaining({ workflowIds: ['w1'] })));
    expect(await screen.findByText('1 workflows, 0 projects, 0 skills synced (0 skipped)')).toBeInTheDocument();
  });

  it('shows a refused transfer as an error instead of losing it', async () => {
    server.use(
      http.get('/api/v1/staging/targets/test/sync-preview', () => HttpResponse.json(preview)),
      http.post('/api/v1/staging/targets/test/sync-definitions', () => HttpResponse.json(
        { code: 'STATE_CONFLICT', error: "Sync target 'test' does not answer at http://test:9090" }, { status: 409 })),
    );
    renderWithProviders(<StagedTargetsCard targets={[target({})]} />);

    fireEvent.click(screen.getByRole('button', { name: 'Transfer to Test' }));
    const dialog = await screen.findByRole('dialog');
    await within(dialog).findByText('Deploy');
    fireEvent.click(within(dialog).getByRole('button', { name: en.settings.syncItemCount.replace('{count}', '1') }));

    expect(await screen.findByText(en.settings.testingSyncErrors.replace('{count}', '1'))).toBeInTheDocument();
  });
});
