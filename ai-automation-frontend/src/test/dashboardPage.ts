import { afterAll, afterEach, beforeAll, beforeEach } from 'vitest';
import { http, HttpResponse } from 'msw';
import { server } from './server';
import { useI18nStore } from '../i18n';
import { useDashboardLayoutStore } from '../stores/dashboardLayoutStore';
import { useDashboardSyncStore } from '../stores/dashboardSyncStore';
import { useToastStore } from '../stores/toastStore';
import { createDefaultLayout } from '../components/dashboard/dashboardLayout';
import type { DashboardData } from '../types';

/** Learning-dashboard answer for tests that render the whole dashboard page. */
export const DASHBOARD_FIXTURE: DashboardData = {
  totalWorkflows: 4,
  totalRuns: 10,
  runsToday: 2,
  successfulRuns: 8,
  failedRuns: 2,
  averageDurationMs: 1000,
  successRate: 0.8,
  totalCostUsd30d: 1.5,
  totalTokens30d: 0,
  totalInputTokens: 0,
  totalOutputTokens: 0,
  topWorkflows: [],
  recentRuns: [],
  timeSeries: [],
  providerStats: [],
  integrationStats: [{ provider: 'trello', runCount: 3, totalTokens: 0, successRate: 1 }],
  modelStats: [],
};

/** msw lifecycle, store resets and the page's default answers — call once at the top of a page test file. */
export function setupDashboardPageTests(): void {
  beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
  afterEach(() => server.resetHandlers());
  afterAll(() => server.close());
  beforeEach(() => {
    localStorage.clear();
    useI18nStore.setState({ locale: 'en' });
    useDashboardLayoutStore.setState(createDefaultLayout());
    useDashboardSyncStore.setState({ deviceLabel: 'Laptop', baseVersionId: null, dirty: false });
    useToastStore.setState({ toasts: [] });
    server.use(
      http.get('/api/v1/learning/dashboard', () => HttpResponse.json(DASHBOARD_FIXTURE)),
      http.get('/api/v1/system/info', () => HttpResponse.json({ version: '1.0.0', buildType: 'SNAPSHOT' })),
      http.get('/api/v1/integrations', () => HttpResponse.json([])),
    );
  });
}

/** Instance ids of the rendered widget slots, in page order. */
export function widgetOrder(): string[] {
  return Array.from(document.querySelectorAll('[data-widget-id]')).map((el) => el.getAttribute('data-widget-id') ?? '');
}

export function slot(id: string): HTMLElement | null {
  return document.querySelector(`[data-widget-id="${id}"]`);
}
