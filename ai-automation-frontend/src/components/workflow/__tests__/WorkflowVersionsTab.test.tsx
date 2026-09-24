import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import WorkflowVersionsTab from '../WorkflowVersionsTab';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
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

  it('marks nothing when the current hash is unknown', async () => {
    server.use(http.get('/api/v1/workflows/wf-1/versions', () => HttpResponse.json({
      content: [version('v1', 1, HASH_OLD)], page: 0, size: 20, totalElements: 1, totalPages: 1,
    })));

    renderWithProviders(<WorkflowVersionsTab workflowId="wf-1" />);

    expect(await screen.findByText('bbbbbbbb')).toBeInTheDocument();
    expect(screen.queryByText('current')).not.toBeInTheDocument();
  });
});
