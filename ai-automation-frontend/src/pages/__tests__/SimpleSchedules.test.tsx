import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, waitFor, within, fireEvent } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { Routes, Route, useLocation } from 'react-router-dom';
import { useI18nStore } from '../../i18n';
import { de } from '../../i18n/de';
import { en } from '../../i18n/en';
import { SimpleSchedules } from '../SimpleSchedules';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
});

function schedule(id: string, name: string, cronExpression: string, scheduleType = 'CRON') {
  return {
    id, workflowId: 'wf-1', workflowName: name, projectName: null, chainId: null, chainName: null,
    targetType: 'WORKFLOW', cronExpression, scheduleType, scheduledAt: null, delayExpression: null,
    description: '', inputContext: {}, enabled: true, nextRunAt: null, lastRunAt: null,
    createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-01T00:00:00Z', lastRunStatus: null,
  };
}

function serve(schedules: unknown[]) {
  server.use(
    http.get('/api/v1/schedules', () => HttpResponse.json(schedules)),
    http.get('/api/v1/workflows', () => HttpResponse.json([{ id: 'wf-1', name: 'Daily digest', blocks: [] }])),
    http.post('/api/v1/schedules/preview', () => HttpResponse.json({ runs: [] })),
  );
}

function rowOf(name: string): HTMLElement {
  return screen.getByText(name).closest('div.min-w-0') as HTMLElement;
}

describe('SimpleSchedules create', () => {
  it('sends a six-field Spring cron expression by default', async () => {
    let body: Record<string, unknown> | null = null;
    serve([]);
    server.use(
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
    expect(cron).toBe('0 0 9 * * *');
  });

  it('picks the timing with the simple picker instead of a raw cron field', async () => {
    let body: Record<string, unknown> | null = null;
    serve([]);
    server.use(
      http.post('/api/v1/schedules', async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ id: 's-1', ...body });
      }),
    );

    renderWithProviders(<SimpleSchedules />);
    fireEvent.click(await screen.findByRole('button', { name: /create/i }));
    const dialog = await screen.findByRole('dialog');

    expect(within(dialog).queryByRole('button', { name: en.schedules.advanced })).not.toBeInTheDocument();
    expect(within(dialog).queryByText('Cron:')).not.toBeInTheDocument();
    expect(within(dialog).queryByDisplayValue('0 0 9 * * *')).not.toBeInTheDocument();

    fireEvent.click(within(dialog).getByRole('button', { name: en.schedules.weekly }));
    fireEvent.click(within(dialog).getByRole('button', { name: en.schedules.weekdays.mon }));
    fireEvent.change(dialog.querySelector('input[type="time"]')!, { target: { value: '08:30' } });
    fireEvent.change(within(dialog).getByRole('combobox'), { target: { value: 'wf-1' } });
    fireEvent.click(within(dialog).getByRole('button', { name: en.common.create }));

    await waitFor(() => expect(body).not.toBeNull());
    expect(body!.cronExpression).toBe('0 30 8 * * 1');
  });
});

describe('SimpleSchedules list', () => {
  it('describes each schedule in words and marks raw cron as Pro', async () => {
    useI18nStore.setState({ locale: 'de' });
    serve([
      schedule('s-1', 'Morning digest', '0 0 9 * * *'),
      schedule('s-2', 'Team check', '0 30 8 * * 1,3'),
      schedule('s-3', 'Quarterly report', '0 0 12 1 1,4,7,10 *'),
      schedule('s-4', 'Reminder', '', 'ONCE'),
    ]);

    renderWithProviders(<SimpleSchedules />);

    expect(await screen.findByText(/Täglich um 09:00/)).toBeInTheDocument();
    expect(screen.getByText(/Mo, Mi um 08:30/)).toBeInTheDocument();
    expect(within(rowOf('Reminder')).getByText(new RegExp(de.schedules.once))).toBeInTheDocument();

    const exotic = rowOf('Quarterly report');
    expect(within(exotic).getByText(new RegExp(de.simple.schedules.customTiming))).toBeInTheDocument();
    expect(within(exotic).getByText(de.simple.handoff.badge)).toBeInTheDocument();
    expect(screen.getAllByText(de.simple.handoff.badge)).toHaveLength(1);
    expect(screen.queryByText(/CRON/)).not.toBeInTheDocument();
    expect(screen.queryByText(/0 0 12/)).not.toBeInTheDocument();
  });
});

// 23635ef6: the app's own dialog instead of window.confirm
describe('SimpleSchedules delete', () => {
  it('names the schedule and deletes it only after confirmation', async () => {
    const deleted: string[] = [];
    serve([schedule('s-1', 'Morning digest', '0 0 9 * * *')]);
    server.use(http.delete('/api/v1/schedules/:id', ({ params }) => {
      deleted.push(String(params.id));
      return new HttpResponse(null, { status: 204 });
    }));
    useI18nStore.setState({ locale: 'de' });
    renderWithProviders(<SimpleSchedules />);

    fireEvent.click(await screen.findByRole('button', { name: de.common.delete }));
    const dialog = screen.getByRole('dialog', { name: 'Zeitplan löschen' });
    expect(within(dialog).getByText('Der Zeitplan für „Morning digest“ wird gelöscht. Der Workflow selbst bleibt.')).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: de.common.cancel }));
    expect(deleted).toEqual([]);

    fireEvent.click(screen.getByRole('button', { name: de.common.delete }));
    fireEvent.click(within(screen.getByRole('dialog', { name: 'Zeitplan löschen' })).getByRole('button', { name: de.common.delete }));
    await waitFor(() => expect(deleted).toEqual(['s-1']));
  });
});

describe('SimpleSchedules — ?new=1&workflow= (the home field)', () => {
  function LocationProbe() {
    const location = useLocation();
    return <p data-testid="location">{location.pathname + location.search}</p>;
  }

  it('opens the create dialog with the workflow chosen and drops the params', async () => {
    serve([]);
    renderWithProviders(
      <>
        <Routes><Route path="/schedules" element={<SimpleSchedules />} /></Routes>
        <LocationProbe />
      </>,
      { route: '/schedules?new=1&workflow=wf-1' },
    );

    const dialog = await screen.findByRole('dialog');
    await waitFor(() => expect(within(dialog).getByRole('combobox')).toHaveValue('wf-1'));
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent(/^\/schedules$/));
  });
});
