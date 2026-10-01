import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, fireEvent, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { modulesFixture } from '../../test/modules';
import { en } from '../../i18n/en';
import { useI18nStore } from '../../i18n';
import { Webhooks } from '../Webhooks';
import type { WebhookTrigger } from '../../types';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => { localStorage.clear(); useI18nStore.setState({ locale: 'en' }); });

function webhook(overrides: Partial<WebhookTrigger> = {}): WebhookTrigger {
  return {
    id: 'wh1', workflowId: 'w1', workflowName: 'Deploy', secretToken: 'abc12345…', tokenVisible: false,
    description: '', defaultContext: {}, enabled: true, webhookUrl: 'http://localhost/api/v1/webhook/trigger/abc12345…',
    triggerCount: 0, lastTriggeredAt: null, createdAt: '2026-09-14T10:00:00Z', ...overrides,
  };
}

function listing(...webhooks: WebhookTrigger[]) {
  server.use(http.get('/api/v1/webhooks', () => HttpResponse.json(webhooks)));
}

describe('Webhooks signatures', () => {
  it('shows which webhooks require a signature and hides secret actions when the module is off', async () => {
    const modules = modulesFixture('core', ['webhook-signatures']);
    server.use(http.get('/api/v1/system/capabilities', () => HttpResponse.json(modules)));
    localStorage.setItem('mysuricates-modules', JSON.stringify(modules));
    listing(webhook({ signatureRequired: true }));

    renderWithProviders(<Webhooks />);

    expect(await screen.findByTestId('webhook-signed')).toHaveTextContent(en.webhooks.signed);
    expect(screen.queryByRole('button', { name: en.webhooks.rotateSecret })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: en.webhooks.removeSecret })).not.toBeInTheDocument();
  });

  it('sets a signing secret and reveals it once', async () => {
    listing(webhook());
    server.use(http.post('/api/v1/webhooks/wh1/secret', () =>
      HttpResponse.json({ secret: 'f'.repeat(64), header: 'X-Signature' })));

    renderWithProviders(<Webhooks />);
    fireEvent.click(await screen.findByRole('button', { name: en.webhooks.setSecret }));

    expect(await screen.findByTestId('webhook-secret')).toHaveTextContent('f'.repeat(64));
    expect(screen.getByText(/X-Signature: sha256=/)).toBeInTheDocument();
  });

  it('removes a signing secret after confirmation', async () => {
    let removed = 0;
    listing(webhook({ signatureRequired: true }));
    server.use(http.delete('/api/v1/webhooks/wh1/secret', () => {
      removed += 1;
      return new HttpResponse(null, { status: 204 });
    }));

    renderWithProviders(<Webhooks />);
    fireEvent.click(await screen.findByRole('button', { name: en.webhooks.removeSecret }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(en.webhooks.removeSecretConfirm)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: en.webhooks.removeSecret }));

    await waitFor(() => expect(removed).toBe(1));
  });
});
