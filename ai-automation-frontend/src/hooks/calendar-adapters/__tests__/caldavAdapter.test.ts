import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { caldavAdapter, toUnifiedEvents } from '../caldavAdapter';
import type { IntegrationProviderInfo } from '../../../types';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('caldavAdapter', () => {
  it('is available only while the caldav integration is connected', () => {
    const provider = (status: string) => ({ providerId: 'caldav', status }) as IntegrationProviderInfo;
    expect(caldavAdapter.isAvailable([provider('CONNECTED')])).toBe(true);
    expect(caldavAdapter.isAvailable([provider('DISCONNECTED')])).toBe(false);
    expect(caldavAdapter.isAvailable([])).toBe(false);
  });

  it('asks all calendars for the range and maps expanded occurrences to unique events', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(
      http.post('/api/v1/integrations/caldav/actions/list_events', async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({
          events: [
            {
              uid: 's1', summary: 'Runde', href: '/dav/work/s1.ics', recurrenceId: '20261006T070000Z',
              start: { dateTime: '2026-10-06T07:00:00Z', timeZone: 'UTC' },
              end: { dateTime: '2026-10-06T07:30:00Z', timeZone: 'UTC' },
            },
            {
              uid: 's1', summary: 'Runde', href: '/dav/work/s1.ics', recurrenceId: '20261013T070000Z',
              start: { dateTime: '2026-10-13T09:00:00+02:00', timeZone: 'Europe/Berlin' },
            },
            { uid: 'h', summary: '', href: '/dav/private/h.ics', location: 'Zuhause', start: { date: '2026-10-03' }, end: { date: '2026-10-04' } },
            { uid: 'broken', summary: 'ohne Beginn', href: '/dav/private/x.ics' },
          ],
        });
      }),
    );

    const events = await caldavAdapter.fetchEvents('2026-10-01T00:00:00.000Z', '2026-10-31T23:59:59.000Z');

    expect(body).toEqual({ from: '2026-10-01T00:00:00.000Z', to: '2026-10-31T23:59:59.000Z', limit: 500 });
    expect(events.map((e) => e.id)).toEqual([
      'caldav-/dav/work/s1.ics#20261006T070000Z',
      'caldav-/dav/work/s1.ics#20261013T070000Z',
      'caldav-/dav/private/h.ics',
    ]);
    expect(events[0]!.start.toISOString()).toBe('2026-10-06T07:00:00.000Z');
    expect(events[0]!.end!.toISOString()).toBe('2026-10-06T07:30:00.000Z');
    expect(events[1]!.start.toISOString()).toBe('2026-10-13T07:00:00.000Z');
    expect(events[1]!.end).toBeUndefined();
    expect(events[2]).toMatchObject({ allDay: true, title: '(Kein Titel)', location: 'Zuhause', source: 'caldav' });
    expect(events[2]!.start.getDate()).toBe(3);
  });

  it('copes with an empty answer', () => {
    expect(toUnifiedEvents({})).toEqual([]);
  });
});
