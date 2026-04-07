/**
 * Trello Adapter
 *
 * Fetches Trello cards with due dates and shows them as calendar events.
 * Only available when the 'trello' integration is connected.
 *
 * Uses the Trello search API to find cards with due dates in the given range,
 * then transforms them into UnifiedCalendarEvent[].
 *
 * API: POST /api/v1/integrations/trello/actions/search
 */

import { api } from '../../api/client';
import type { UnifiedCalendarEvent } from '../../types';
import type { CalendarSourceAdapter } from './types';

interface TrelloCardResponse {
  id: string;
  name: string;
  desc?: string;
  url: string;
  due: string | null;
  dueComplete: boolean;
  idBoard: string;
  labels?: { name: string; color: string }[];
}

interface TrelloSearchResponse {
  cards: TrelloCardResponse[];
}

export const trelloAdapter: CalendarSourceAdapter = {
  sourceId: 'trello',
  label: 'Trello',
  color: 'bg-sky-500',

  isAvailable: (integrations) =>
    integrations.some((p) => p.providerId === 'trello' && p.status === 'CONNECTED'),

  fetchEvents: async (from, to) => {
    const data = await api.post<TrelloSearchResponse>(
      '/integrations/trello/actions/search',
      { query: 'is:open has:due', modelTypes: 'cards', cards_limit: 100 }
    );

    const fromDate = new Date(from);
    const toDate = new Date(to);

    return (data.cards ?? [])
      .filter((card) => {
        if (!card.due) return false;
        const dueDate = new Date(card.due);
        return dueDate >= fromDate && dueDate <= toDate;
      })
      .map((card): UnifiedCalendarEvent => ({
        id: `trello-${card.id}`,
        title: card.name,
        start: new Date(card.due!),
        allDay: !card.due!.includes('T'),
        source: 'trello',
        sourceLabel: 'Trello',
        color: card.dueComplete ? 'bg-sky-300' : 'bg-sky-500',
        status: card.dueComplete ? 'DONE' : 'DUE',
        link: card.url,
      }));
  },
};
