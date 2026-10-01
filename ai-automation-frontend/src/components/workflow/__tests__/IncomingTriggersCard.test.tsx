import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, within, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { modulesFixture } from '../../../test/modules';
import { useI18nStore } from '../../../i18n';
import { IncomingTriggersCard } from '../IncomingTriggersCard';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => useI18nStore.setState({ locale: 'en' }));

const WF = 'wf-1';
const conditionLabel = () => 'On success';

const schedules = [
  { id: 's1', workflowId: WF, cronExpression: '0 6 * * 5', scheduleType: 'CRON', description: '', enabled: true,
    nextRunAt: new Date(Date.now() + 3 * 3600_000).toISOString(), scheduledAt: null, delayExpression: null },
  { id: 's2', workflowId: WF, cronExpression: '0 0 * * *', scheduleType: 'CRON', description: 'Nightly', enabled: false,
    nextRunAt: null, scheduledAt: null, delayExpression: null },
];
const webhooks = [{ id: 'h1', workflowId: WF, description: 'GitHub push', enabled: true, triggerCount: 4 }];
const todoTriggers = [{ id: 't1', tagPattern: 'bug', minPriority: 'HIGH', targetWorkflowId: WF, enabled: true,
  description: null, triggerCount: 0 }];
const completion = [{ id: 'c1', sourceWorkflowId: 'wf-0', sourceWorkflowName: 'Build', targetWorkflowId: WF,
  enabled: true, triggerOnSuccess: true, triggerOnFailure: false, triggerAlways: false }];

let capabilitiesServed = false;

function serve(opts: { webhooksOff?: boolean; empty?: boolean } = {}) {
  capabilitiesServed = false;
  const list = <T,>(items: T[]) => HttpResponse.json(opts.empty ? [] : items);
  server.use(
    http.get('/api/v1/system/capabilities', () => {
      capabilitiesServed = true;
      return HttpResponse.json({
        apiVersion: 'v1', routes: [], blockTypes: [], ...modulesFixture('full', opts.webhooksOff ? ['webhooks'] : []),
      });
    }),
    http.get(`/api/v1/schedules/workflow/${WF}`, () => list(schedules)),
    http.get(`/api/v1/webhooks/workflow/${WF}`, () => list(webhooks)),
    http.get(`/api/v1/todo-triggers/workflow/${WF}`, () => list(todoTriggers)),
    http.get(`/api/v1/completion-triggers/workflow/${WF}/as-target`, () => list(completion)),
  );
}

describe('IncomingTriggersCard', () => {
  it('lists schedules, webhooks, TODO triggers and completion triggers', async () => {
    serve();
    renderWithProviders(<IncomingTriggersCard workflowId={WF} conditionLabel={conditionLabel} />);

    const sched = await screen.findByTestId('incoming-schedules');
    expect(within(sched).getByText('0 6 * * 5')).toBeInTheDocument();
    expect(within(sched).getByText(/next run/)).toBeInTheDocument();
    expect(within(sched).getByText('Nightly')).toBeInTheDocument();
    expect(within(sched).getByText('paused')).toBeInTheDocument();

    const hooks = await screen.findByTestId('incoming-webhooks');
    expect(within(hooks).getByText('GitHub push')).toBeInTheDocument();
    expect(within(hooks).getByText('4×')).toBeInTheDocument();

    const todo = screen.getByTestId('incoming-todo-triggers');
    expect(within(todo).getByText('Tag bug')).toBeInTheDocument();
    expect(within(todo).getByText('≥ HIGH')).toBeInTheDocument();

    const wfs = screen.getByTestId('incoming-workflows');
    expect(within(wfs).getByRole('link', { name: 'Build' })).toHaveAttribute('href', '/workflows/wf-0');
    expect(screen.queryByText(/only runs when started by hand/)).not.toBeInTheDocument();
  });

  it('says so when nothing starts the workflow', async () => {
    serve({ empty: true });
    renderWithProviders(<IncomingTriggersCard workflowId={WF} conditionLabel={conditionLabel} />);

    expect(await screen.findByText(/only runs when started by hand/)).toBeInTheDocument();
    expect(screen.getByText(/Not listed: chains and sub-workflow blocks/)).toBeInTheDocument();
  });

  it('leaves webhooks out while the webhooks module is off', async () => {
    serve({ webhooksOff: true });
    renderWithProviders(<IncomingTriggersCard workflowId={WF} conditionLabel={conditionLabel} />);

    // Module state starts optimistic; once the backend reports webhooks off, the group must go.
    await waitFor(() => {
      expect(capabilitiesServed).toBe(true);
      expect(screen.getByTestId('incoming-schedules')).toBeInTheDocument();
      expect(screen.queryByTestId('incoming-webhooks')).not.toBeInTheDocument();
    });
  });
});
