/**
 * Calendar Source Adapter Interface
 *
 * Each adapter represents one source of events for the unified calendar view
 * (e.g., Google Calendar, scheduled workflows, past runs).
 *
 * The calendar page discovers all registered adapters via the registry
 * and renders events from all available and enabled sources.
 *
 * ## Adding a new calendar source
 *
 * 1. Create a new file in `hooks/calendar-adapters/` implementing this interface
 * 2. Import and add it to the `CALENDAR_ADAPTERS` array in `registry.ts`
 * 3. The calendar page will automatically show the new source with its filter toggle
 *
 * @example
 * ```ts
 * export const myAdapter: CalendarSourceAdapter = {
 *   sourceId: 'my-source',
 *   label: 'My Source',
 *   color: 'bg-chart-8',
 *   isAvailable: () => true,
 *   fetchEvents: async (from, to) => {
 *     const data = await api.get(`/my-endpoint?from=${from}&to=${to}`);
 *     return data.items.map(item => ({ ... }));
 *   },
 * };
 * ```
 */

import type { IntegrationProviderInfo, UnifiedCalendarEvent } from '../../types';

/** What an adapter returns when the source cut its list: the events plus how many exist in the range. */
export interface CalendarSourceResult {
  events: UnifiedCalendarEvent[];
  /** Set only when the source delivered fewer events than exist (e.g. the runs capped at 1000 per range). */
  limited?: { shown: number; total: number };
}

/** A source the calendar had to cut, for the notice above the grid. */
export interface CalendarSourceLimit {
  sourceId: string;
  label: string;
  shown: number;
  total: number;
}

export interface CalendarSourceAdapter {
  /** Unique identifier, e.g. 'google-calendar', 'schedules', 'runs' */
  sourceId: string;

  /** Display label shown in the source filter toggle */
  label: string;

  /** Background color class for calendar dots and event stripes: a theme token (bg-chart-1..8 or a status token) */
  color: string;

  /**
   * Whether this source is currently available.
   *
   * Called with the current list of integration providers.
   * - System sources (schedules, runs) always return `true`.
   * - Integration sources check if their provider status is 'CONNECTED'.
   *
   * Note: This controls the `enabled` flag on the useQuery call,
   * NOT whether the hook is called (React Rules of Hooks).
   */
  isAvailable: (integrations: IntegrationProviderInfo[]) => boolean;

  /**
   * Fetch events for a time range and transform them to UnifiedCalendarEvent[]. A source that may cut
   * its list returns a CalendarSourceResult instead, so the calendar can say so.
   *
   * Called as the `queryFn` in a useQuery hook. The returned events must have
   * `source` and `color` matching this adapter's `sourceId` and `color`.
   *
   * @param from - ISO 8601 start of the time range
   * @param to - ISO 8601 end of the time range
   */
  fetchEvents: (from: string, to: string) => Promise<UnifiedCalendarEvent[] | CalendarSourceResult>;
}
