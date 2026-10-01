import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import { http, HttpResponse, delay } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { DASHBOARD_FIXTURE } from '../../../test/dashboardPage';
import { en } from '../../../i18n/en';
import { useI18nStore } from '../../../i18n';
import type { DashboardData } from '../../../types';
import { DashboardDataProvider } from '../DashboardDataProvider';
import { WidgetContent } from '../WidgetContent';
import { INSIGHTS_WIDGETS } from '../insightsWidgetDefs';
import { DEFAULT_WIDGET_IDS } from '../widgetRegistry';
import type { DashboardWidgetDef } from '../widgetDefinition';

const INSIGHTS_DATA: DashboardData = {
  ...DASHBOARD_FIXTURE,
  totalInputTokens: 1200,
  totalOutputTokens: 300,
  timeSeries: [{ date: '2026-09-14', count: 2, successRate: 1, failedCount: 0, avgDurationMs: 1500, totalCostUsd: 0.2, totalTokens: 1500 }],
  gateStatusSummary: [{ status: 'GREEN', count: 3 }],
  failureReasons: [{ reason: 'Timeout <img src=x onerror=alert(1)>', count: 2 }],
  costByWorkflow: [{ workflowId: 'w1', workflowName: 'Nightly report', totalCostUsd: 0.5, runCount: 3 }],
  blockTypeUsage: [{ blockType: 'shell', stepCount: 4, failedCount: 0, failureRate: 0 }],
  triggerDistribution: { manual: 1, scheduled: 2, webhook: 0, completionTrigger: 0, todoTrigger: 0 },
};

function renderWidget(def: DashboardWidgetDef) {
  return renderWithProviders(
    <DashboardDataProvider filter={{ days: 7, projectId: 'p1' }}>
      <WidgetContent widget={{ def, instance: { instanceId: def.id, type: def.id } }} />
    </DashboardDataProvider>,
  );
}

let searches: string[] = [];

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  searches = [];
  useI18nStore.setState({ locale: 'en' });
  server.use(
    http.get('/api/v1/learning/dashboard', ({ request }) => {
      searches.push(new URL(request.url).search);
      return HttpResponse.json(INSIGHTS_DATA);
    }),
  );
});

describe('Insights widgets', () => {
  it('registers the eight Insights charts as lazy, catalog-only widgets', () => {
    expect(INSIGHTS_WIDGETS.map((w) => w.id)).toEqual([
      'insights-gate-status', 'insights-failure-reasons', 'insights-cost-by-workflow', 'insights-block-types',
      'insights-triggers', 'insights-cost-over-time', 'insights-duration-over-time', 'insights-tokens',
    ]);
    for (const widget of INSIGHTS_WIDGETS) {
      expect(widget.catalogOnly).toBe(true);
      expect(DEFAULT_WIDGET_IDS).not.toContain(widget.id);
      expect((widget.component as unknown as { $$typeof: symbol }).$$typeof).toBe(Symbol.for('react.lazy'));
    }
  });

  it.each(INSIGHTS_WIDGETS.map((w) => [w.id, w] as const))('%s loads its code and renders with the view filter', async (_id, def) => {
    renderWidget(def);
    expect(await screen.findByText(def.label(en), {}, { timeout: 5000 })).toBeInTheDocument();
    expect(searches.every((search) => search === '?days=7&projectId=p1')).toBe(true);
  });

  it('shows a skeleton while the dashboard data loads', async () => {
    server.use(
      http.get('/api/v1/learning/dashboard', async () => {
        await delay('infinite');
        return HttpResponse.json(INSIGHTS_DATA);
      }),
    );
    renderWidget(INSIGHTS_WIDGETS[0]!);
    expect(await screen.findByTestId('widget-skeleton')).toBeInTheDocument();
  });

  it('renders failure reasons from runs as plain text, never as markup', async () => {
    renderWidget(INSIGHTS_WIDGETS[1]!);
    expect(await screen.findByText('Timeout <img src=x onerror=alert(1)>', {}, { timeout: 5000 })).toBeInTheDocument();
    expect(document.querySelector('img')).toBeNull();
  });
});
