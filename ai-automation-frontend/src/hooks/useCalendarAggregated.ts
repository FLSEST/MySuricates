/**
 * Aggregated Calendar Hook
 *
 * Collects events from all registered calendar source adapters and merges
 * them into a single sorted event list. Each adapter fetches independently
 * via its own useQuery call (parallel loading).
 *
 * Supports user-defined color overrides per source via useCalendarColors.
 *
 * @see hooks/calendar-adapters/registry.ts — where sources are registered
 * @see hooks/calendar-adapters/types.ts — CalendarSourceAdapter interface
 */

import { useQuery } from '@tanstack/react-query';
import { useIntegrations } from './useIntegrations';
import { useCalendarColors } from './useCalendarColors';
import { CALENDAR_ADAPTERS } from './calendar-adapters/registry';
import type { UnifiedCalendarEvent, CalendarSourceConfig } from '../types';

export function useCalendarAggregated(from: string, to: string, enabledSources: Set<string>) {
  const { data: integrations } = useIntegrations();
  const integrationsResolved = integrations ?? [];
  const { colors, setColor, getColor } = useCalendarColors();

  // One useQuery per adapter (static array → constant hook count → Rules of Hooks safe).
  const queries = CALENDAR_ADAPTERS.map((adapter) => {
    const available = adapter.isAvailable(integrationsResolved);
    const active = available && enabledSources.has(adapter.sourceId);

    // eslint-disable-next-line react-hooks/rules-of-hooks
    return useQuery({
      queryKey: ['calendar', adapter.sourceId, from, to],
      queryFn: () => adapter.fetchEvents(from, to),
      enabled: active && !!from && !!to,
      staleTime: 60_000,
    });
  });

  // Build source configs with user color overrides
  const sources: CalendarSourceConfig[] = CALENDAR_ADAPTERS
    .filter((adapter) => adapter.isAvailable(integrationsResolved))
    .map((adapter) => ({
      sourceId: adapter.sourceId,
      label: adapter.label,
      color: getColor(adapter.sourceId, adapter.color),
      enabled: enabledSources.has(adapter.sourceId),
    }));

  // Aggregate events, applying user color overrides
  const events: UnifiedCalendarEvent[] = queries
    .flatMap((query, i) => {
      const adapter = CALENDAR_ADAPTERS[i]!;
      if (!enabledSources.has(adapter.sourceId)) return [];
      const userColor = colors[adapter.sourceId];
      if (!userColor) return query.data ?? [];
      // Apply user color but preserve dynamic per-event colors (e.g. runs red/green)
      return (query.data ?? []).map((ev) => ({
        ...ev,
        color: ev.color === adapter.color ? userColor : ev.color,
      }));
    })
    .sort((a, b) => a.start.getTime() - b.start.getTime());

  const isLoading = queries.some((q) => q.isLoading);

  return { events, sources, isLoading, setSourceColor: setColor };
}
