import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
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

const CONFIG = {
  executionMaxConcurrent: 5, claudeMaxConcurrentCalls: 5, claudeReservedForInteractive: 1, claudeTimeoutSeconds: 300,
  claudeInterCallDelayMs: 0, chainWorkflowTimeoutMinutes: 30, rateLimitEnabled: 1, rateLimitRequestsPerMinute: 120,
  assistantChatTimeoutSeconds: 240, schedulerCatchUpOnResume: 0, schedulerResumeGraceMinutes: 5,
  runAutoResumeEnabled: 1, liveLogPersistDefault: 0, dashboardReportAssistEnabled: 1, activeRuns: 0, queuedRuns: 0,
};

describe('ExecutionSettingsCard — switches', () => {
  it('names every switch by its label and shows its stored state', async () => {
    server.use(http.get('/api/v1/system/execution-config', () => HttpResponse.json(CONFIG)));
    renderWithProviders(<ExecutionSettingsCard />);

    const expected: [string, string][] = [
      [en.settings.executionRateLimitEnabled, 'true'],
      [en.settings.schedulerCatchUpOnResume, 'false'],
      [en.settings.runAutoResumeEnabled, 'true'],
      [en.settings.dashboardReportAssist, 'true'],
      [en.settings.liveLogPersistDefault, 'false'],
    ];
    for (const [name, checked] of expected) {
      expect(await screen.findByRole('switch', { name })).toHaveAttribute('aria-checked', checked);
    }
    expect(screen.getAllByRole('switch')).toHaveLength(expected.length);
  });
});
