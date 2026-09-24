import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, waitFor, within, fireEvent } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import { SimpleSchedules } from '../SimpleSchedules';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
});

describe('SimpleSchedules create', () => {
  it('sends a six-field Spring cron expression by default', async () => {
    let body: Record<string, unknown> | null = null;
    server.use(
      http.get('/api/v1/schedules', () => HttpResponse.json([])),
      http.get('/api/v1/workflows', () => HttpResponse.json([{ id: 'wf-1', name: 'Daily digest', blocks: [] }])),
      http.post('/api/v1/schedules', async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ id: 's-1', ...body });
      }),
    );

    renderWithProviders(<SimpleSchedules />);
    fireEvent.click(await screen.findByRole('button', { name: /create/i }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByRole('combobox'), { target: { value: 'wf-1' } });
    fireEvent.click(within(dialog).getByRole('button', { name: /create/i }));

    await waitFor(() => expect(body).not.toBeNull());
    const cron = String(body!.cronExpression);
    expect(cron.trim().split(/\s+/)).toHaveLength(6);
  });
});
