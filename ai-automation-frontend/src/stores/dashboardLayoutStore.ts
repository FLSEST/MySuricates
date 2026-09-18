import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import {
  LAYOUT_FORMAT_VERSION,
  LAYOUT_LIMITS,
  createDefaultLayout,
  createViewId,
  type DashboardLayout,
  type DashboardView,
  type DashboardViewFilter,
  type WidgetInstance,
} from '../components/dashboard/dashboardLayout';
import {
  addLayoutView,
  duplicateLayoutView,
  moveLayoutView,
  removeLayoutView,
  renameLayoutView,
  selectLayoutView,
  setLayoutViewFilter,
} from '../components/dashboard/dashboardViews';
import { migrateLayout, sanitizeLayout } from '../components/dashboard/dashboardLayoutSanitize';
import { useDashboardSyncStore } from './dashboardSyncStore';

interface DashboardLayoutState extends DashboardLayout {
  /** Replaces the widget list of the active view. */
  setActiveViewWidgets: (widgets: WidgetInstance[]) => void;
  /** Back to the default layout for the active view. */
  resetActiveView: () => void;
  /**
   * Replaces the whole layout with one loaded from the server — not a local change. The active view
   * is chosen per device: kept when the incoming layout has it, otherwise its first view.
   */
  applyRemoteLayout: (layout: DashboardLayout) => void;
  /**
   * Puts a view (e.g. from an older version) into the layout: replaces the view with that id or
   * appends it. False when the view limit blocks appending.
   */
  upsertView: (view: DashboardView) => boolean;
  /** Shows another view. Per device: never a local change, never a new version. */
  setActiveView: (viewId: string) => void;
  /** Appends an empty view and shows it. Its id, or null at the view limit. */
  addView: (name?: string) => string | null;
  renameView: (viewId: string, name: string) => void;
  /** Copies widgets and filter into a new view right after the original and shows it. Its id, or null at the limit. */
  duplicateView: (viewId: string, name: string) => string | null;
  /** Deletes a view (never the last one); a deleted active view hands over to its neighbour. */
  removeView: (viewId: string) => boolean;
  moveView: (draggedViewId: string, overViewId: string) => void;
  setViewFilter: (viewId: string, filter: DashboardViewFilter) => void;
}

function updateActiveView(state: DashboardLayout, widgets: WidgetInstance[]): Pick<DashboardLayout, 'views'> {
  return { views: state.views.map((v) => (v.id === state.activeViewId ? { ...v, widgets } : v)) };
}

function markLocalChange() {
  useDashboardSyncStore.getState().markDirty();
}

export const useDashboardLayoutStore = create<DashboardLayoutState>()(
  persist(
    (set, get) => {
      /** Applies a pure layout update and marks a local change — only when something actually changed. */
      const change = (update: (layout: DashboardLayout) => DashboardLayout): boolean => {
        const { activeViewId, views } = get();
        const current: DashboardLayout = { activeViewId, views };
        const next = update(current);
        if (next === current) return false;
        set({ activeViewId: next.activeViewId, views: next.views });
        markLocalChange();
        return true;
      };

      return {
        ...createDefaultLayout(),
        setActiveViewWidgets: (widgets) => {
          set((state) => updateActiveView(state, widgets));
          markLocalChange();
        },
        resetActiveView: () => {
          set((state) => updateActiveView(state, []));
          markLocalChange();
        },
        applyRemoteLayout: (layout) => {
          const incoming = sanitizeLayout(layout);
          const { activeViewId } = get();
          const keepsActive = incoming.views.some((v) => v.id === activeViewId);
          set({ views: incoming.views, activeViewId: keepsActive ? activeViewId : incoming.views[0]?.id ?? incoming.activeViewId });
        },
        upsertView: (view) => {
          const { views } = get();
          const exists = views.some((v) => v.id === view.id);
          if (!exists && views.length >= LAYOUT_LIMITS.views) return false;
          set({ views: exists ? views.map((v) => (v.id === view.id ? view : v)) : [...views, view] });
          markLocalChange();
          return true;
        },
        setActiveView: (viewId) => {
          const { activeViewId, views } = get();
          const next = selectLayoutView({ activeViewId, views }, viewId);
          if (next.activeViewId !== activeViewId) set({ activeViewId: next.activeViewId });
        },
        addView: (name = '') => {
          const id = createViewId();
          return change((layout) => addLayoutView(layout, name, id)) ? id : null;
        },
        renameView: (viewId, name) => {
          change((layout) => renameLayoutView(layout, viewId, name));
        },
        duplicateView: (viewId, name) => {
          const id = createViewId();
          return change((layout) => duplicateLayoutView(layout, viewId, name, id)) ? id : null;
        },
        removeView: (viewId) => change((layout) => removeLayoutView(layout, viewId)),
        moveView: (draggedViewId, overViewId) => {
          change((layout) => moveLayoutView(layout, draggedViewId, overViewId));
        },
        setViewFilter: (viewId, filter) => {
          change((layout) => setLayoutViewFilter(layout, viewId, filter));
        },
      };
    },
    {
      name: 'dashboard-layout',
      version: LAYOUT_FORMAT_VERSION,
      partialize: ({ activeViewId, views }) => ({ activeViewId, views }),
      migrate: (persisted, version) => {
        // Data written by a newer app version: refuse (zustand then keeps the default in memory and
        // does not write) instead of overwriting it with an older format.
        if (version > LAYOUT_FORMAT_VERSION) {
          throw new Error(`dashboard-layout: stored format ${version} is newer than ${LAYOUT_FORMAT_VERSION}`);
        }
        return sanitizeLayout(migrateLayout(persisted, version));
      },
      // Per-browser data — never trust its shape.
      merge: (persisted, current) => ({ ...current, ...sanitizeLayout(persisted) }),
    },
  ),
);
