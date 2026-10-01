import { failOnUnhandledRequest } from '../../../test/msw';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { de } from '../../../i18n/de';
import { en } from '../../../i18n/en';
import { CalendarView } from '../CalendarView';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => {
  server.resetHandlers();
  vi.useRealTimers();
  useI18nStore.setState({ locale: 'en' });
});
afterAll(() => server.close());
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(2026, 2, 15, 12, 0));
  server.use(http.get('/api/v1/schedules/calendar', () => HttpResponse.json({ upcoming: [], pastRuns: [] })));
});

// 17eb7994: month names came from own tables ("Maerz"), the day heading read "24. September 2026" in English.
describe('CalendarView dates', () => {
  it.each([
    ['en', en, 'March 2026', 'March 24, 2026', 'April 2026'],
    ['de', de, 'März 2026', '24. März 2026', 'April 2026'],
  ] as const)('writes month and day in the app language (%s)', (locale, texts, month, day, next) => {
    useI18nStore.setState({ locale });
    renderWithProviders(<CalendarView />);

    expect(screen.getByRole('heading', { name: month })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '24' }));
    expect(screen.getByRole('heading', { name: day })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: texts.schedules.calendarNextMonth }));
    expect(screen.getByRole('heading', { name: next })).toBeInTheDocument();
  });
});
