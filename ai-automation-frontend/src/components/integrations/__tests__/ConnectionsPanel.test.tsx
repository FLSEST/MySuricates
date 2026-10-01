import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { screen, fireEvent, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { ConnectionsPanel } from '../ConnectionsPanel';
import type { IntegrationProviderInfo } from '../../../types';

const BASE = '/api/v1';

const github: IntegrationProviderInfo = {
  providerId: 'github',
  displayName: 'GitHub',
  icon: 'Github',
  description: 'Sync GitHub issues',
  status: 'CONNECTED',
  configured: true,
  requiresCredentials: true,
  requiresOAuthFlow: false,
  accountEmail: null,
  accountName: 'octocat',
  connectedSince: null,
  scopes: null,
  optionalScopes: [],
  actions: [],
  connections: [
    { name: 'default', status: 'CONNECTED', configured: true, accountEmail: null, accountName: 'octocat', connectedSince: null, scopes: null },
    { name: 'work', status: 'CONNECTED', configured: true, accountEmail: null, accountName: 'octo-work', connectedSince: null, scopes: null },
  ],
};

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function renderPanel(provider = github) {
  renderWithProviders(<ConnectionsPanel provider={provider} displayName="GitHub" onRefresh={() => {}} />);
}

describe('ConnectionsPanel', () => {
  it('lists every connection, the default one without actions', () => {
    renderPanel();
    expect(screen.getByTestId('connection-default')).toHaveTextContent('octocat');
    expect(screen.getByTestId('connection-work')).toHaveTextContent('octo-work');
    expect(screen.getByTestId('connection-default').querySelectorAll('button')).toHaveLength(0);
    expect(screen.getByTestId('connection-work').querySelectorAll('button').length).toBeGreaterThan(0);
  });

  it('validates a new name: pattern and uniqueness', () => {
    renderPanel();
    fireEvent.click(screen.getByText('Add connection'));
    const input = screen.getByLabelText('Connection name');

    fireEvent.change(input, { target: { value: 'Work Account' } });
    expect(screen.getByText(/1–40 lower-case letters/)).toBeInTheDocument();
    expect(screen.getByText('Continue').closest('button')).toBeDisabled();

    fireEvent.change(input, { target: { value: 'work' } });
    expect(screen.getByText('This connection already exists')).toBeInTheDocument();

    fireEvent.change(input, { target: { value: 'private-2' } });
    expect(screen.getByText('Continue').closest('button')).not.toBeDisabled();
  });

  it('saves credentials of a new connection with ?connection=<name>', async () => {
    let url = '';
    let body: unknown = null;
    server.use(http.post(`${BASE}/integrations/github/credentials`, async ({ request }) => {
      url = request.url;
      body = await request.json();
      return HttpResponse.json(github);
    }));
    renderPanel();
    fireEvent.click(screen.getByText('Add connection'));
    fireEvent.change(screen.getByLabelText('Connection name'), { target: { value: 'oss' } });
    fireEvent.click(screen.getByText('Continue'));

    const inputs = await screen.findAllByRole('textbox');
    fireEvent.change(inputs[inputs.length - 1], { target: { value: 'user' } });
    const secret = document.querySelector('input[type="password"]') as HTMLInputElement;
    fireEvent.change(secret, { target: { value: 'ghp_x' } });
    fireEvent.click(screen.getByText('Save credentials'));

    await waitFor(() => expect(url).toContain('connection=oss'));
    expect(body).toEqual({ clientId: 'user', clientSecret: 'ghp_x' });
  });

  it('deletes only the chosen named connection after confirmation', async () => {
    let url = '';
    server.use(http.delete(`${BASE}/integrations/github/credentials`, ({ request }) => {
      url = request.url;
      return new HttpResponse(null, { status: 204 });
    }));
    renderPanel();
    fireEvent.click(screen.getByTestId('connection-work').querySelector('button:last-child') as HTMLButtonElement);
    expect(await screen.findByText('Delete connection “work”?')).toBeInTheDocument();
    const buttons = screen.getAllByText('Delete');
    fireEvent.click(buttons[buttons.length - 1]);

    await waitFor(() => expect(url).toContain('connection=work'));
  });
});
