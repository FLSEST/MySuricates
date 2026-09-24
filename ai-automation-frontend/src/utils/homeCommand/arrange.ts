import type { GroupId, HomeGroup, HomeItem, HomeSource, Intent } from './types';

/** The group an intent lifts to the top. */
export const INTENT_GROUP: Record<Intent, GroupId> = {
  failed: 'runs',
  schedule: 'schedules',
  waiting: 'approvals',
  todo: 'todos',
  locale: 'actions',
  theme: 'actions',
  settings: 'actions',
  create: 'templates',
  explain: 'explanations',
};

/** Order without an intent: own workflows first, then what waits, then the rest. */
export const DEFAULT_GROUP_ORDER: readonly GroupId[] = [
  'workflows', 'approvals', 'runs', 'templates', 'todos', 'schedules', 'projects', 'explanations', 'actions',
];

export const PER_GROUP = 3;
export const MAX_ITEMS = 12;

/**
 * Orders the sources into the groups the field shows: groups an intent names first (in the order
 * the intents were typed), then the default order; at most `perGroup` hits each and `total` in all.
 * Empty groups drop out. Pure function.
 */
export function arrangeGroups(
  sources: readonly HomeSource[], intents: readonly Intent[], perGroup = PER_GROUP, total = MAX_ITEMS,
): HomeGroup[] {
  const order: GroupId[] = [];
  for (const intent of intents) {
    const id = INTENT_GROUP[intent];
    if (!order.includes(id)) order.push(id);
  }
  for (const id of DEFAULT_GROUP_ORDER) if (!order.includes(id)) order.push(id);

  const byId = new Map(sources.map((s) => [s.id, s]));
  const groups: HomeGroup[] = [];
  let left = total;
  for (const id of order) {
    const source = byId.get(id);
    if (!source || source.items.length === 0 || left <= 0) continue;
    const items = source.items.slice(0, Math.min(perGroup, left));
    left -= items.length;
    const found = source.items.filter((i) => !i.fallback).length;
    const shownFound = items.filter((i) => !i.fallback).length;
    groups.push({ id, items, showAllTo: source.showAllTo, more: !!source.more || found > shownFound });
  }
  return groups;
}

/** All items of the groups in display order — what ↑/↓ walks through. */
export function flattenGroups(groups: readonly HomeGroup[]): HomeItem[] {
  return groups.flatMap((g) => g.items);
}

/** Whether the groups hold a found hit (not only the generated "add as task" row). */
export function hasFoundHit(groups: readonly HomeGroup[]): boolean {
  return groups.some((g) => g.items.some((i) => !i.fallback));
}
