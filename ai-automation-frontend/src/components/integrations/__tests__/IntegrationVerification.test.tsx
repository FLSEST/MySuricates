import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, beforeEach, afterAll, afterEach } from 'vitest';
import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { IntegrationBlockVerificationNotice } from '../IntegrationVerification';
import type { IntegrationProviderInfo } from '../../../types';

const provider = (id: string, verification: 'VERIFIED' | 'SIMULATED'): IntegrationProviderInfo => ({
  providerId: id,
  displayName: id,
  icon: 'Plug',
  description: '',
  status: 'DISCONNECTED',
  configured: true,
  accountEmail: null,
  accountName: null,
  connectedSince: null,
  scopes: null,
  optionalScopes: [],
  actions: [],
  verification,
  verifiedAgainst: verification === 'VERIFIED' ? 'real service' : null,
});

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('IntegrationBlockVerificationNotice', () => {
  beforeEach(() => {
    server.use(http.get('/api/v1/integrations', () =>
      HttpResponse.json([provider('gitea', 'SIMULATED'), provider('github', 'VERIFIED')])));
  });

  it('warns when the block uses a simulated provider', async () => {
    renderWithProviders(<IntegrationBlockVerificationNotice configJson='{"provider":"gitea","action":"list_repos"}' />);
    expect(await screen.findByText(/not against the real service/)).toBeInTheDocument();
  });

  it('stays silent for a verified provider, an unknown provider and invalid JSON', async () => {
    const { rerender } = renderWithProviders(<IntegrationBlockVerificationNotice configJson='{"provider":"gitea"}' />);
    // Data loaded: the simulated provider shows the notice ...
    expect(await screen.findByText(/not against the real service/)).toBeInTheDocument();

    // ... and the same loaded list shows nothing for the others.
    for (const configJson of ['{"provider":"github"}', '{"provider":"nope"}', '{not json']) {
      rerender(<IntegrationBlockVerificationNotice configJson={configJson} />);
      expect(screen.queryByText(/not against the real service/)).not.toBeInTheDocument();
    }
  });
});
