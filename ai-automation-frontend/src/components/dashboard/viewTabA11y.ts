import { useEffect, useRef } from 'react';

/** The element the view tabs control: the widget area of the page. */
export const DASHBOARD_VIEW_PANEL_ID = 'dashboard-view-panel';

/** DOM id of a view's tab (view ids are unique and valid in ids). */
export function viewTabId(viewId: string): string {
  return `dashboard-view-tab-${viewId}`;
}

/**
 * Keeps the shown view's tab visible: on a phone the strip scrolls sideways, and a view opened by
 * link or switched to by keyboard can sit outside its visible part. `nearest` leaves a visible tab
 * (and the page) where it is.
 */
export function useScrollIntoViewWhenActive<T extends HTMLElement>(active: boolean) {
  const ref = useRef<T>(null);
  useEffect(() => {
    // Optional call: not every environment implements scrollIntoView (e.g. jsdom).
    if (active) ref.current?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' });
  }, [active]);
  return ref;
}
