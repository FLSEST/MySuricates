import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import WorkflowVersionsTab from '../WorkflowVersionsTab';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => useI18nStore.setState({ locale: 'en' }));

const HASH_CURRENT = 'a'.repeat(64);
const HASH_OLD = 'b'.repeat(64);

function version(id: string, versionNumber: number, definitionHash: string | null) {
  return {
    id, workflowId: 'wf-1', versionNumber, snapshot: '{}', changeSource: 'user',
    changeMessage: null, createdAt: '2026-09-14T10:00:00Z', definitionHash,
  };
}

describe('WorkflowVersionsTab provenance', () => {
  it('shows short hashes and marks the version matching the current definition', async () => {
    server.use(http.get('/api/v1/workflows/wf-1/versions', () => HttpResponse.json({
      content: [version('v2', 2, HASH_CURRENT), version('v1', 1, HASH_OLD), version('v0', 0, null)],
      page: 0, size: 20, totalElements: 3, totalPages: 1,
    })));

    renderWithProviders(<WorkflowVersionsTab workflowId="wf-1" currentHash={HASH_CURRENT} />);

    expect(await screen.findByText('aaaaaaaa')).toHaveAttribute('title', HASH_CURRENT);
    expect(screen.getByText('bbbbbbbb')).toBeInTheDocument();
    expect(screen.getAllByText('current')).toHaveLength(1);
  });

  it('names the goal guard as the source of the versions it created', async () => {
    server.use(http.get('/api/v1/workflows/wf-1/versions', () => HttpResponse.json({
      content: [{ ...version('v1', 1, HASH_OLD), changeSource: 'goal-guard' }],
      page: 0, size: 20, totalElements: 1, totalPages: 1,
    })));

    renderWithProviders(<WorkflowVersionsTab workflowId="wf-1" />);

    expect(await screen.findByText('Goal guard')).toBeInTheDocument();
    expect(screen.queryByText('goal-guard')).not.toBeInTheDocument();
  });

  // 23635ef6: the app's own dialog instead of window.confirm
  it('restores a version only after confirming it', async () => {
    const restored: string[] = [];
    server.use(
      http.get('/api/v1/workflows/wf-1/versions', () => HttpResponse.json({
        content: [version('v1', 1, HASH_OLD)], page: 0, size: 20, totalElements: 1, totalPages: 1,
      })),
      http.post('/api/v1/workflows/wf-1/versions/:versionId/restore', ({ params }) => {
        restored.push(String(params.versionId));
        return HttpResponse.json({});
      }),
    );
    renderWithProviders(<WorkflowVersionsTab workflowId="wf-1" />);

    fireEvent.click(await screen.findByText('bbbbbbbb'));
    fireEvent.click(screen.getByRole('button', { name: 'Restore' }));
    const dialog = screen.getByRole('dialog', { name: 'Restore' });
    expect(within(dialog).getByText('Restore version 1? The current state will be saved as a new version.')).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(restored).toEqual([]);

    fireEvent.click(screen.getByRole('button', { name: 'Restore' }));
    fireEvent.click(within(screen.getByRole('dialog', { name: 'Restore' })).getByRole('button', { name: 'Restore' }));
    await waitFor(() => expect(restored).toEqual(['v1']));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  // db79bc54: theme tokens by role instead of fixed palette colors
  it('colors the source badge by role with theme tokens', async () => {
    server.use(http.get('/api/v1/workflows/wf-1/versions', () => HttpResponse.json({
      content: [
        { ...version('v4', 4, null), changeSource: 'user' },
        { ...version('v3', 3, null), changeSource: 'dialog' },
        { ...version('v2', 2, null), changeSource: 'pool-install' },
        { ...version('v1', 1, null), changeSource: 'import' },
      ],
      page: 0, size: 20, totalElements: 4, totalPages: 1,
    })));

    renderWithProviders(<WorkflowVersionsTab workflowId="wf-1" />);

    expect(await screen.findByText('User')).toHaveClass('bg-human-subtle', 'text-human');
    expect(screen.getByText('Dialog import')).toHaveClass('bg-ai-subtle', 'text-ai');
    expect(screen.getByText('Pool Install')).toHaveClass('bg-info-subtle', 'text-info');
    expect(screen.getByText('Import')).toHaveClass('bg-surface-hover', 'text-text-secondary');
  });

  it('marks nothing when the current hash is unknown', async () => {
    server.use(http.get('/api/v1/workflows/wf-1/versions', () => HttpResponse.json({
      content: [version('v1', 1, HASH_OLD)], page: 0, size: 20, totalElements: 1, totalPages: 1,
    })));

    renderWithProviders(<WorkflowVersionsTab workflowId="wf-1" />);

    expect(await screen.findByText('bbbbbbbb')).toBeInTheDocument();
    expect(screen.queryByText('current')).not.toBeInTheDocument();
  });
});
