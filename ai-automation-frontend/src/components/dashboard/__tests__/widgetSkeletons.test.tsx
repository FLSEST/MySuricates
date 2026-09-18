import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import { http, HttpResponse, delay } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { TodosWidget } from '../TodosWidget';
import { TodosDonutChart } from '../TodosDonutChart';
import { UpcomingSchedulesCard } from '../UpcomingSchedulesCard';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  useI18nStore.setState({ locale: 'en' });
});

const hang = (path: string) => http.get(path, async () => {
  await delay('infinite');
  return HttpResponse.json({});
});

describe('widget skeletons', () => {
  it.each([
    ['todo counts', '/api/v1/todos/count'],
    ['open todos', '/api/v1/todos'],
  ])('TodosWidget shows a skeleton while the %s load', async (_what, path) => {
    server.use(hang(path));
    renderWithProviders(<TodosWidget />);
    expect(await screen.findByTestId('widget-skeleton')).toBeInTheDocument();
    expect(screen.queryByText('Todos')).not.toBeInTheDocument();
  });

  it('TodosWidget shows its todos once loaded', async () => {
    renderWithProviders(<TodosWidget />);
    expect(await screen.findByText('Test Todo')).toBeInTheDocument();
    expect(screen.queryByTestId('widget-skeleton')).not.toBeInTheDocument();
  });

  it('TodosDonutChart shows a skeleton while the counts load, then the total', async () => {
    server.use(hang('/api/v1/todos/count'));
    const { unmount } = renderWithProviders(<TodosDonutChart />);
    expect(await screen.findByTestId('widget-skeleton')).toBeInTheDocument();
    unmount();

    server.resetHandlers();
    renderWithProviders(<TodosDonutChart />);
    expect(await screen.findByText('9')).toBeInTheDocument();
    expect(screen.queryByTestId('widget-skeleton')).not.toBeInTheDocument();
  });

  it.each([
    ['today', '/api/v1/schedules/remaining-today'],
    ['this week', '/api/v1/schedules/remaining-this-week'],
  ])('UpcomingSchedulesCard shows a skeleton while %s loads', async (_what, path) => {
    server.use(hang(path));
    renderWithProviders(<UpcomingSchedulesCard />);
    expect(await screen.findByTestId('widget-skeleton')).toBeInTheDocument();
    expect(screen.queryByText('No scheduled runs')).not.toBeInTheDocument();
  });

  it('UpcomingSchedulesCard shows the empty state once loaded', async () => {
    renderWithProviders(<UpcomingSchedulesCard />);
    expect(await screen.findByText('No scheduled runs')).toBeInTheDocument();
    expect(screen.queryByTestId('widget-skeleton')).not.toBeInTheDocument();
  });
});
