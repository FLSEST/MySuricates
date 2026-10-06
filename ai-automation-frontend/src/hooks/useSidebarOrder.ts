import { arrayMove } from '@dnd-kit/sortable';
import { useSettingsStore } from '../stores/settingsStore';
import { useSystemModules } from './useModules';
import { useT } from '../i18n';
import {
  collapseDividers, effectiveOrder, newDividerId, resolveEntries, withDefaultDividers, type SidebarEntry,
} from '../utils/sidebarLayout';
import { SIMPLE_MODE_ROUTES } from '../utils/simpleRoutes';
import { NAV_TILES } from '../utils/homeTiles';
import { navItems, type NavItemDef } from '../utils/navItems';

/** Default order incl. the default dividers — over all pages, so anchors of disabled modules still count. */
const DEFAULT_ORDER = withDefaultDividers(navItems.map((i) => i.to));

export type SidebarNavItem = NavItemDef & { label: string };

/** Id of an entry in the sortable list: the route of a page, the id of a divider. */
export function sidebarEntryId(entry: SidebarEntry<{ to: string }>): string {
  return entry.kind === 'divider' ? entry.id : entry.item.to;
}

/**
 * Order, dividers and hidden pages of the sidebar. `visibleEntries` is the menu:
 * in Pro the stored (or default) order without hidden pages and without
 * dividers that no longer separate anything; in Simple only the Simple pages under their Simple titles,
 * without dividers. `editModeEntries` shows every stored divider, `hiddenNavItems` the
 * hidden pages. Pages of disabled modules are missing everywhere but keep their place.
 */
export function useSidebarOrder() {
  const {
    simpleMode, sidebarItemOrder, sidebarHiddenItems, sidebarDividerLabels,
    setSidebarItemOrder, setSidebarHiddenItems, setSidebarDividerLabel, setSidebarDividerLabels,
  } = useSettingsStore();
  const { data: systemModules } = useSystemModules();
  const t = useT();

  // Pages of disabled modules vanish entirely — also from edit mode and the "add back" list.
  const disabledModules = new Set(
    (systemModules?.modules ?? []).filter((m) => !m.enabled).map((m) => m.name),
  );
  const availableNavItems = navItems.filter((item) => !item.module || !disabledModules.has(item.module));

  // Build label-enriched nav items once (needed for edit mode display).
  // Simple mode names each page like its Simple title (the home tiles), the home itself "Start".
  const simpleLabel = (to: string): string | undefined => {
    if (to === '/') return t.nav.simpleHome;
    const tile = NAV_TILES.find((tl) => tl.to === to);
    return tile ? t.simple.home.tiles[tile.id].title : undefined;
  };
  const navItemsWithLabels: SidebarNavItem[] = availableNavItems.map((item) => ({
    ...item,
    label: (simpleMode ? simpleLabel(item.to) : undefined) ?? t.nav[item.labelKey],
  }));

  // Full order (custom or default, dividers included); new pages are appended.
  const fullOrder = effectiveOrder(sidebarItemOrder, DEFAULT_ORDER, navItemsWithLabels.map((i) => i.to));
  const shownItems = navItemsWithLabels.filter((item) => !sidebarHiddenItems.includes(item.to));

  // Dividers that separate nothing on this installation (disabled modules, hidden pages) drop out.
  const visibleEntries = simpleMode
    ? resolveEntries(fullOrder, navItemsWithLabels.filter((item) => SIMPLE_MODE_ROUTES.has(item.to)))
        .filter((entry) => entry.kind === 'item')
    : collapseDividers(resolveEntries(fullOrder, shownItems), (id) => Boolean(sidebarDividerLabels[id]));

  const hiddenNavItems = navItemsWithLabels.filter((item) => sidebarHiddenItems.includes(item.to));

  // Edit mode shows every divider as stored, so each one can be moved or removed.
  const editModeEntries = resolveEntries(fullOrder, shownItems);

  // Move within the full order so entries not shown here (disabled modules) keep their place.
  const moveEntry = (activeId: string, overId: string) => {
    const oldIndex = fullOrder.indexOf(activeId);
    const newIndex = fullOrder.indexOf(overId);
    setSidebarItemOrder(arrayMove(fullOrder, oldIndex, newIndex));
  };

  const addDivider = () => {
    setSidebarItemOrder([...fullOrder, newDividerId()]);
  };

  const removeDivider = (id: string) => {
    setSidebarItemOrder(fullOrder.filter((entry) => entry !== id));
    setSidebarDividerLabel(id, '');
  };

  const hide = (to: string) => {
    setSidebarHiddenItems([...sidebarHiddenItems, to]);
  };

  const show = (to: string) => {
    setSidebarHiddenItems(sidebarHiddenItems.filter((h) => h !== to));
  };

  const reset = () => {
    setSidebarItemOrder([]);
    setSidebarHiddenItems([]);
    setSidebarDividerLabels({});
  };

  return {
    visibleEntries,
    editModeEntries,
    hiddenNavItems,
    dividerLabels: sidebarDividerLabels,
    setDividerLabel: setSidebarDividerLabel,
    moveEntry,
    addDivider,
    removeDivider,
    hide,
    show,
    reset,
  };
}
