/**
 * Calendar Source Adapter Registry
 *
 * All calendar event sources are registered here. The `useCalendarAggregated`
 * hook discovers available sources from this array.
 *
 * ## Adding a new calendar source
 *
 * 1. Create a new adapter file in this directory implementing `CalendarSourceAdapter`
 * 2. Import it below and add it to the `CALENDAR_ADAPTERS` array
 * 3. The calendar page will automatically show the new source with its filter toggle
 *
 * IMPORTANT: This array must be static (not dynamically constructed at runtime)
 * because React's Rules of Hooks require a constant number of hook calls per render.
 * Each adapter gets its own `useQuery` call in `useCalendarAggregated`.
 * The `isAvailable` check controls the `enabled` flag, not whether the hook is called.
 */

import { googleCalendarAdapter } from './googleCalendarAdapter';
import { trelloAdapter } from './trelloAdapter';
import { schedulesAdapter } from './schedulesAdapter';
import { runsAdapter } from './runsAdapter';
import type { CalendarSourceAdapter } from './types';

export const CALENDAR_ADAPTERS: CalendarSourceAdapter[] = [
  googleCalendarAdapter,
  trelloAdapter,
  schedulesAdapter,
  runsAdapter,
];

export type { CalendarSourceAdapter } from './types';
