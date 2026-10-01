import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, waitFor, fireEvent } from '@testing-library/react';
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

const autoResumeSwitch = () => screen.findByRole('switch', { name: en.settings.runAutoResumeEnabled });

describe('ExecutionSettingsCard — automatic resume (TODO 90d10ba1)', () => {
  it('an older backend without the keys shows the defaults: on, 30 s', async () => {
    server.use(http.get('/api/v1/system/execution-config', () => HttpResponse.json(BASE_CONFIG)));
    renderWithProviders(<ExecutionSettingsCard />);

    expect(await autoResumeSwitch()).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByText(en.settings.runAutoResumeDelaySeconds)).toBeInTheDocument();
    expect(screen.getByText(en.settings.runAutoResumeDelaySeconds).parentElement?.querySelector('input'))
      .toHaveValue(30);
  });

  it('shows the stored values and saves the switch together with the wait', async () => {
    const saved: Record<string, unknown>[] = [];
    server.use(
      http.get('/api/v1/system/execution-config', () =>
        HttpResponse.json({ ...BASE_CONFIG, runAutoResumeEnabled: 0, runAutoResumeDelaySeconds: 90 })),
      http.put('/api/v1/system/execution-config', async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>;
        saved.push(body);
        return HttpResponse.json({ ...BASE_CONFIG, ...body });
      }),
    );
    renderWithProviders(<ExecutionSettingsCard />);

    const toggle = await autoResumeSwitch();
    expect(toggle).toHaveAttribute('aria-checked', 'false');
    expect(screen.getByText(en.settings.runAutoResumeDelaySeconds).parentElement?.querySelector('input'))
      .toHaveValue(90);

    fireEvent.click(toggle);
    fireEvent.click(screen.getByRole('button', { name: en.settings.executionSave }));

    await waitFor(() => expect(saved).toHaveLength(1));
    expect(saved[0]).toMatchObject({ runAutoResumeEnabled: 1, runAutoResumeDelaySeconds: 90 });
  });
});
