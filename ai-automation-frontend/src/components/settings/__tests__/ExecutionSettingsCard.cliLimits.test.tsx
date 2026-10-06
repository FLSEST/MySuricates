import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, waitFor, fireEvent, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { en } from '../../../i18n/en';
import { ExecutionSettingsCard } from '../ExecutionSettingsCard';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => useI18nStore.setState({ locale: 'en' }));

const BASE_CONFIG = {
  executionMaxConcurrent: 5, claudeMaxConcurrentCalls: 5, claudeReservedForInteractive: 1, claudeTimeoutSeconds: 300,
  claudeInterCallDelayMs: 0, chainWorkflowTimeoutMinutes: 30, rateLimitEnabled: 1, rateLimitRequestsPerMinute: 120,
  assistantChatTimeoutSeconds: 240, schedulerCatchUpOnResume: 1, schedulerResumeGraceMinutes: 5, activeRuns: 0, queuedRuns: 0,
};

const concurrent = (name: string) =>
  screen.getByRole('spinbutton', { name: en.settings.aiCliConcurrentFor.replace('{provider}', name) });
const reserved = (name: string) =>
  screen.getByRole('spinbutton', { name: en.settings.aiCliReservedFor.replace('{provider}', name) });

// TODO bd5df8d7: limits per provider and across all of them, all of them editable
describe('ExecutionSettingsCard — AI CLI limits', () => {
  it('lists Claude, Pi, Codex and the overall limit with the values in force', async () => {
    server.use(http.get('/api/v1/system/execution-config', () => HttpResponse.json({
      ...BASE_CONFIG, claudeMaxConcurrentCalls: 6, piMaxConcurrentCalls: 3, piReservedForInteractive: 0,
      codexMaxConcurrentCalls: 2, codexReservedForInteractive: 1, aiCliMaxConcurrentCalls: 9, aiCliReservedForInteractive: 2,
    })));
    renderWithProviders(<ExecutionSettingsCard />);

    const table = await screen.findByTestId('ai-cli-limits');
    expect(within(table).getAllByRole('rowheader').map((cell) => cell.textContent))
      .toEqual(['Claude', 'Pi', 'Codex', en.settings.aiCliAll]);
    expect(concurrent('Claude')).toHaveValue(6);
    expect(concurrent('Pi')).toHaveValue(3);
    expect(reserved('Pi')).toHaveValue(0);
    expect(concurrent('Codex')).toHaveValue(2);
    expect(concurrent(en.settings.aiCliAll)).toHaveValue(9);
    expect(reserved(en.settings.aiCliAll)).toHaveValue(2);
  });

  it('shows the defaults when an older backend does not send the new limits', async () => {
    server.use(http.get('/api/v1/system/execution-config', () => HttpResponse.json(BASE_CONFIG)));
    renderWithProviders(<ExecutionSettingsCard />);

    await screen.findByTestId('ai-cli-limits');
    expect(concurrent('Codex')).toHaveValue(5);
    expect(reserved('Codex')).toHaveValue(1);
    expect(concurrent(en.settings.aiCliAll)).toHaveValue(8);
  });

  it('saves a changed limit together with the others', async () => {
    const saved: Record<string, unknown>[] = [];
    server.use(
      http.get('/api/v1/system/execution-config', () => HttpResponse.json(BASE_CONFIG)),
      http.put('/api/v1/system/execution-config', async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>;
        saved.push(body);
        return HttpResponse.json({ ...BASE_CONFIG, ...body });
      }),
    );
    renderWithProviders(<ExecutionSettingsCard />);
    await screen.findByTestId('ai-cli-limits');

    fireEvent.change(concurrent('Codex'), { target: { value: '3' } });
    fireEvent.change(concurrent(en.settings.aiCliAll), { target: { value: '6' } });
    fireEvent.click(screen.getByRole('button', { name: en.settings.executionSave }));

    await waitFor(() => expect(saved).toHaveLength(1));
    expect(saved[0]).toMatchObject({
      codexMaxConcurrentCalls: 3, aiCliMaxConcurrentCalls: 6,
      claudeMaxConcurrentCalls: 5, piMaxConcurrentCalls: 5, aiCliReservedForInteractive: 1,
    });
  });
});
