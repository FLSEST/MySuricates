import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, fireEvent, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { en } from '../../i18n/en';
import { useI18nStore } from '../../i18n';
import { Webhooks } from '../Webhooks';
import type { WebhookTrigger } from '../../types';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => { localStorage.clear(); useI18nStore.setState({ locale: 'en' }); });

function webhook(overrides: Partial<WebhookTrigger> = {}): WebhookTrigger {
  return {
    id: 'wh1', workflowId: 'w1', workflowName: 'Test Workflow', secretToken: 'abc12345…', tokenVisible: false,
    description: '', defaultContext: {}, enabled: true, webhookUrl: 'http://localhost/api/v1/webhook/trigger/abc12345…',
    triggerCount: 0, lastTriggeredAt: null, createdAt: '2026-09-14T10:00:00Z', ...overrides,
  };
}

const full = { secretToken: 'f'.repeat(40), tokenVisible: true, webhookUrl: `http://localhost/api/v1/webhook/trigger/${'f'.repeat(40)}` };

describe('Webhooks — creating and one-time token reveal', { timeout: 15_000 }, () => {
  it('creates a webhook and shows its URL and token exactly once', async () => {
    const created: unknown[] = [];
    server.use(
      http.get('/api/v1/webhooks', () => HttpResponse.json([])),
      http.post('/api/v1/webhooks', async ({ request }) => {
        created.push(await request.json());
        return HttpResponse.json(webhook(full));
      }),
    );
    renderWithProviders(<Webhooks />);

    fireEvent.click((await screen.findAllByRole('button', { name: en.webhooks.create }))[0]);
    const dialog = within(screen.getByRole('dialog'));
    const select = dialog.getByRole('combobox');
    await waitFor(() => expect(within(select).getByRole('option', { name: 'Test Workflow' })).toBeInTheDocument());
    expect(dialog.getByRole('button', { name: en.common.create })).toBeDisabled();
    fireEvent.change(select, { target: { value: 'w1' } });
    fireEvent.change(dialog.getByLabelText(en.common.description), { target: { value: 'CI hook' } });
    fireEvent.click(dialog.getByRole('button', { name: en.common.create }));

    // The create dialog closes and the reveal opens in the same update
    await screen.findByRole('heading', { name: en.webhooks.tokenShownOnce });
    const reveal = within(screen.getByRole('dialog'));
    expect(created).toEqual([{ workflowId: 'w1', description: 'CI hook' }]);
    expect(reveal.getByText(en.webhooks.tokenShownOnceHint)).toBeInTheDocument();
    expect(reveal.getByText(full.webhookUrl)).toBeInTheDocument();
    expect(reveal.getByText(full.secretToken)).toBeInTheDocument();
    expect(reveal.getAllByRole('button', { name: /copy/i })).toHaveLength(2);

    // The footer button; the header's X is labelled the same
    fireEvent.click(reveal.getAllByRole('button', { name: en.common.close }).at(-1)!);
    expect(screen.queryByRole('dialog')).toBeNull();

    // Created: the form starts over
    fireEvent.click(screen.getAllByRole('button', { name: en.webhooks.create })[0]);
    expect(within(screen.getByRole('dialog')).getByRole('combobox')).toHaveValue('');
  });

  it('keeps the input when the dialog is cancelled', async () => {
    server.use(http.get('/api/v1/webhooks', () => HttpResponse.json([])));
    renderWithProviders(<Webhooks />);
    fireEvent.click((await screen.findAllByRole('button', { name: en.webhooks.create }))[0]);
    fireEvent.change(within(screen.getByRole('dialog')).getByLabelText(en.common.description), { target: { value: 'Draft' } });
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: en.common.cancel }));
    expect(screen.queryByRole('dialog')).toBeNull();

    fireEvent.click(screen.getAllByRole('button', { name: en.webhooks.create })[0]);
    expect(within(screen.getByRole('dialog')).getByLabelText(en.common.description)).toHaveValue('Draft');
  });

  it('reveals the new token after a confirmed regeneration', async () => {
    let regenerated = 0;
    server.use(
      http.get('/api/v1/webhooks', () => HttpResponse.json([webhook()])),
      http.post('/api/v1/webhooks/wh1/regenerate-token', () => {
        regenerated += 1;
        return HttpResponse.json(webhook(full));
      }),
    );
    renderWithProviders(<Webhooks />);
    fireEvent.click(await screen.findByRole('button', { name: en.webhooks.regenerate }));
    const confirm = within(screen.getByRole('dialog'));
    expect(confirm.getByText(en.webhooks.regenerateConfirm)).toBeInTheDocument();
    fireEvent.click(confirm.getByRole('button', { name: en.webhooks.regenerate }));

    await screen.findByRole('heading', { name: en.webhooks.tokenShownOnce });
    expect(within(screen.getByRole('dialog')).getByText(full.secretToken)).toBeInTheDocument();
    expect(regenerated).toBe(1);
  });
});
