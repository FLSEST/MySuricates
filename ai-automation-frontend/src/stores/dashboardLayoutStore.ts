import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import {
  LAYOUT_FORMAT_VERSION,
  LAYOUT_LIMITS,
  createDefaultLayout,
  migrateLayout,
  sanitizeLayout,
  type DashboardLayout,
  type DashboardView,
  type WidgetInstance,
} from '../components/dashboard/dashboardLayout';
import { useDashboardSyncStore } from './dashboardSyncStore';

interface DashboardLayoutState extends DashboardLayout {
  /** Replaces the widget list of the active view. */
  setActiveViewWidgets: (widgets: WidgetInstance[]) => void;
  /** Back to the default layout for the active view. */
  resetActiveView: () => void;
  /** Replaces the whole layout with one loaded from the server — not a local change. */
  applyRemoteLayout: (layout: DashboardLayout) => void;
  /** Puts a view (e.g. from an older version) into the layout: replaces the view with that id or appends it. */
  upsertView: (view: DashboardView) => void;
}

function updateActiveView(state: DashboardLayout, widgets: WidgetInstance[]): Pick<DashboardLayout, 'views'> {
  return { views: state.views.map((v) => (v.id === state.activeViewId ? { ...v, widgets } : v)) };
}

function markLocalChange() {
  useDashboardSyncStore.getState().markDirty();
}

export const useDashboardLayoutStore = create<DashboardLayoutState>()(
  persist(
    (set, get) => ({
      ...createDefaultLayout(),
      setActiveViewWidgets: (widgets) => {
        set((state) => updateActiveView(state, widgets));
        markLocalChange();
      },
      resetActiveView: () => {
        set((state) => updateActiveView(state, []));
        markLocalChange();
      },
      applyRemoteLayout: (layout) => set(sanitizeLayout(layout)),
      upsertView: (view) => {
        const { views } = get();
        const exists = views.some((v) => v.id === view.id);
        if (!exists && views.length >= LAYOUT_LIMITS.views) return;
        set({ views: exists ? views.map((v) => (v.id === view.id ? view : v)) : [...views, view] });
        markLocalChange();
      },
    }),
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
