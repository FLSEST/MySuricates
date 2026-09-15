import { useEffect, useRef } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useDashboardLayoutStore } from '../stores/dashboardLayoutStore';

const VIEW_PARAM = 'view';

function withView(params: URLSearchParams, viewId: string): URLSearchParams {
  const next = new URLSearchParams(params);
  next.set(VIEW_PARAM, viewId);
  return next;
}

/**
 * Keeps `?view=<id>` and the active dashboard view in step (spec E4). A link opens its view; an
 * unknown id keeps the current view and the link is corrected; switching views rewrites the
 * parameter without adding history entries. The active view is per device — nothing here marks
 * the layout as changed.
 */
export function useDashboardViewParam() {
  const [searchParams, setSearchParams] = useSearchParams();
  const requested = searchParams.get(VIEW_PARAM);
  const activeViewId = useDashboardLayoutStore((s) => s.activeViewId);
  // Last parameter value acted on (or written here), so only real link changes select a view.
  const handledRequest = useRef<string | null>(null);
  const previousActive = useRef(activeViewId);

  // Link → view: on load and whenever the parameter itself changes.
  useEffect(() => {
    if (requested === handledRequest.current) return;
    handledRequest.current = requested;
    if (requested === null) return;
    const store = useDashboardLayoutStore.getState();
    if (store.views.some((v) => v.id === requested)) {
      store.setActiveView(requested);
    } else {
      handledRequest.current = store.activeViewId;
      setSearchParams((prev) => withView(prev, store.activeViewId), { replace: true });
    }
  }, [requested, setSearchParams]);

  // View → link: only after the active view changed, so a plain dashboard URL stays clean.
  useEffect(() => {
    if (previousActive.current === activeViewId) return;
    previousActive.current = activeViewId;
    if (requested === activeViewId) return;
    handledRequest.current = activeViewId;
    setSearchParams((prev) => withView(prev, activeViewId), { replace: true });
  }, [activeViewId, requested, setSearchParams]);
}
