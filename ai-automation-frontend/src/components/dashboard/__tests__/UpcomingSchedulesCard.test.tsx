import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { formatDateTime } from '../../../utils/formatTime';
import { UpcomingSchedulesCard } from '../UpcomingSchedulesCard';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  useI18nStore.setState({ locale: 'en' });
});

const PAUSED_UNTIL = '2026-10-01T22:00:00Z';

/** The dashboard names an active skip-until pause instead of silently listing fewer runs (a776fd2a). */
describe('UpcomingSchedulesCard', () => {
  it('says until when schedules are paused', async () => {
    server.use(
      http.get('/api/v1/schedules/remaining-today', () =>
        HttpResponse.json({ count: 0, schedules: [], pausedUntil: PAUSED_UNTIL })),
      http.get('/api/v1/schedules/remaining-this-week', () =>
        HttpResponse.json({
          count: 1,
          pausedUntil: PAUSED_UNTIL,
          schedules: [{
            scheduleId: 's1', workflowName: 'Nightly', projectName: null, chainId: null, chainName: null,
            targetType: 'WORKFLOW', scheduledAt: '2026-10-02T06:00:00Z',
          }],
        })),
    );

    renderWithProviders(<UpcomingSchedulesCard />);

    expect(await screen.findByTestId('upcoming-schedules-paused'))
      .toHaveTextContent(`Schedules paused until ${formatDateTime(PAUSED_UNTIL, 'en')}`);
    expect(screen.getByText('Nightly')).toBeInTheDocument();
  });

  it('shows no pause note without a pause', async () => {
    server.use(
      http.get('/api/v1/schedules/remaining-today', () => HttpResponse.json({ count: 0, schedules: [], pausedUntil: null })),
      http.get('/api/v1/schedules/remaining-this-week', () => HttpResponse.json({ count: 0, schedules: [], pausedUntil: null })),
    );

    renderWithProviders(<UpcomingSchedulesCard />);

    expect(await screen.findByText('No scheduled runs')).toBeInTheDocument();
    expect(screen.queryByTestId('upcoming-schedules-paused')).not.toBeInTheDocument();
    expect(screen.queryByTestId('upcoming-schedules-maintenance')).not.toBeInTheDocument();
  });

  /** Under the maintenance switch nothing starts: the backend lists no run and says why (7704b1e9). */
  it.each([
    ['en', 'Maintenance mode – nothing starts', 'No scheduled runs'],
    ['de', 'Wartungsmodus – startet nichts', 'Keine geplanten Ausführungen'],
  ] as const)('says that nothing starts in maintenance mode (%s)', async (locale, note, empty) => {
    useI18nStore.setState({ locale });
    server.use(
      http.get('/api/v1/schedules/remaining-today', () =>
        HttpResponse.json({ count: 0, schedules: [], pausedUntil: null, maintenance: true })),
      http.get('/api/v1/schedules/remaining-this-week', () =>
        HttpResponse.json({ count: 0, schedules: [], pausedUntil: null, maintenance: true })),
    );

    renderWithProviders(<UpcomingSchedulesCard />);

    expect(await screen.findByTestId('upcoming-schedules-maintenance')).toHaveTextContent(note);
    expect(screen.getByText(empty)).toBeInTheDocument();
    expect(screen.queryByTestId('upcoming-schedules-paused')).not.toBeInTheDocument();
  });
});
