import { useEffect, useRef } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useDashboardLayoutStore } from '../stores/dashboardLayoutStore';

const VIEW_PARAM = 'view';

function withView(params: URLSearchParams, viewId: string): URLSearchParams {
  const next = new URLSearchParams(params);
  next.set(VIEW_PARAM, viewId);
  return next;
}

interface PendingLink {
  /** The `?view=` id that did not exist locally yet. */
  id: string;
  /** The active view when the link arrived — a later user switch away from it drops the link. */
  sinceActiveId: string;
}

/**
 * Keeps `?view=<id>` and the active dashboard view in step (spec E4). A link opens its view; an
 * unknown id keeps the current view, corrects the link, and is remembered as a pending link — a
 * layout that arrives later (the sync mount check, or "Use that version") and turns out to contain
 * that view still opens it, unless the user switched views meanwhile. Switching views rewrites the
 * parameter without adding history entries. The active view is per device — nothing here marks
 * the layout as changed.
 */
export function useDashboardViewParam() {
  const [searchParams, setSearchParams] = useSearchParams();
  const requested = searchParams.get(VIEW_PARAM);
  const activeViewId = useDashboardLayoutStore((s) => s.activeViewId);
  const views = useDashboardLayoutStore((s) => s.views);
  // Last parameter value acted on (or written here), so only real link changes select a view.
  const handledRequest = useRef<string | null>(null);
  const previousActive = useRef(activeViewId);
  const pendingLink = useRef<PendingLink | null>(null);

  // Link → view: on load and whenever the parameter itself changes.
  useEffect(() => {
    if (requested === handledRequest.current) return;
    handledRequest.current = requested;
    if (requested === null) {
      pendingLink.current = null;
      return;
    }
    const store = useDashboardLayoutStore.getState();
    if (store.views.some((v) => v.id === requested)) {
      pendingLink.current = null;
      store.setActiveView(requested);
    } else {
      pendingLink.current = { id: requested, sinceActiveId: store.activeViewId };
      handledRequest.current = store.activeViewId;
      setSearchParams((prev) => withView(prev, store.activeViewId), { replace: true });
    }
  }, [requested, setSearchParams]);

  // A pending link resolves once the store gains its view — unless the user switched meanwhile.
  useEffect(() => {
    const pending = pendingLink.current;
    if (!pending) return;
    if (activeViewId !== pending.sinceActiveId) {
      pendingLink.current = null;
      return;
    }
    if (views.some((v) => v.id === pending.id)) {
      pendingLink.current = null;
      useDashboardLayoutStore.getState().setActiveView(pending.id);
    }
  }, [views, activeViewId]);

  // View → link: only after the active view changed, so a plain dashboard URL stays clean.
  useEffect(() => {
    if (previousActive.current === activeViewId) return;
    previousActive.current = activeViewId;
    if (requested === activeViewId) return;
    handledRequest.current = activeViewId;
    setSearchParams((prev) => withView(prev, activeViewId), { replace: true });
  }, [activeViewId, requested, setSearchParams]);
}
