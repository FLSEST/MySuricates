import type { TriggerSource } from '../types';

/**
 * Filter deep links to a single chain or schedule (3a35ced1). Neither has a detail page — the chains page
 * shows one chain beside its list, the schedules page is a table — so the link carries the id as a query
 * param, and the page selects or highlights that entry. Reports and run origins link here.
 */
export const CHAIN_PARAM = 'chain';
export const SCHEDULE_PARAM = 'schedule';

export function chainLink(chainId: string): string {
  return `/chains?${new URLSearchParams({ [CHAIN_PARAM]: chainId }).toString()}`;
}

export function scheduleLink(scheduleId: string): string {
  return `/schedules?${new URLSearchParams({ [SCHEDULE_PARAM]: scheduleId }).toString()}`;
}

/** Link to the schedule that started a run, or null when no schedule started it or its id is unknown. */
export function scheduleTriggerLink(
  source: TriggerSource | null | undefined, scheduleId: string | null | undefined,
): string | null {
  return source === 'SCHEDULE' && scheduleId ? scheduleLink(scheduleId) : null;
}
