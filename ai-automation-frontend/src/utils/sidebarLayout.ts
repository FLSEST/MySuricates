import { generateId } from './uuid';

/**
 * Sidebar order with dividers. The order is a list of routes plus divider ids (`divider:<id>`);
 * what is actually shown depends on the installation (enabled modules) and the user's hidden
 * items, so dividers are never trusted as stored — {@link collapseDividers} removes every
 * divider that no longer separates anything.
 */
export const DIVIDER_PREFIX = 'divider:';

/** Default dividers, each placed directly before its anchor route. */
export const DEFAULT_DIVIDERS: ReadonlyArray<{ id: string; before: string }> = [
  { id: 'divider:default-triggers', before: '/cockpit' },
  { id: 'divider:default-onboarding', before: '/onboarding-profiles' },
  { id: 'divider:default-ideas', before: '/wishes' },
  { id: 'divider:default-insight', before: '/skills' },
];

export type SidebarEntry<T> = { kind: 'item'; item: T } | { kind: 'divider'; id: string };

export function isDividerId(id: string): boolean {
  return id.startsWith(DIVIDER_PREFIX);
}

export function newDividerId(): string {
  return `${DIVIDER_PREFIX}${generateId()}`;
}

/** Inserts the default dividers before their anchors; missing anchors and existing dividers are skipped. */
export function withDefaultDividers(order: readonly string[]): string[] {
  const result: string[] = [];
  for (const entry of order) {
    for (const divider of DEFAULT_DIVIDERS) {
      if (divider.before === entry && !order.includes(divider.id)) result.push(divider.id);
    }
    result.push(entry);
  }
  return result;
}

/**
 * The full order to work on: the saved one (or the default) plus available routes it does not know
 * yet. Entries of unavailable routes stay, so a module switched off and on keeps its place.
 */
export function effectiveOrder(
  saved: readonly string[],
  defaultOrder: readonly string[],
  availableRoutes: readonly string[],
): string[] {
  const base = saved.length > 0 ? [...saved] : [...defaultOrder];
  return [...base, ...availableRoutes.filter((to) => !base.includes(to))];
}

/** Maps an order onto dividers and the given items; routes without an item are left out. */
export function resolveEntries<T extends { to: string }>(order: readonly string[], items: readonly T[]): SidebarEntry<T>[] {
  const byRoute = new Map(items.map((i) => [i.to, i]));
  const entries: SidebarEntry<T>[] = [];
  for (const id of order) {
    if (isDividerId(id)) {
      entries.push({ kind: 'divider', id });
    } else {
      const found = byRoute.get(id);
      if (found) entries.push({ kind: 'item', item: found });
    }
  }
  return entries;
}

/**
 * Removes dividers that separate nothing: at the end, at the start (unless named — then it heads the
 * first group) and all but the last of a run (an emptied group takes its own divider with it, the
 * next group keeps its heading).
 */
export function collapseDividers<T>(entries: readonly SidebarEntry<T>[], isNamed: (id: string) => boolean): SidebarEntry<T>[] {
  const result: SidebarEntry<T>[] = [];
  let pending: SidebarEntry<T> | null = null;
  for (const entry of entries) {
    if (entry.kind === 'divider') {
      pending = entry;
      continue;
    }
    if (pending && (result.length > 0 || isNamed(pending.id))) result.push(pending);
    pending = null;
    result.push(entry);
  }
  return result;
}
