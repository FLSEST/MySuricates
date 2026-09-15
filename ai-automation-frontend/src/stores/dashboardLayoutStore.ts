import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import {
  LAYOUT_FORMAT_VERSION,
  createDefaultLayout,
  migrateLayout,
  sanitizeLayout,
  type DashboardLayout,
  type WidgetInstance,
} from '../components/dashboard/dashboardLayout';

interface DashboardLayoutState extends DashboardLayout {
  /** Replaces the widget list of the active view. */
  setActiveViewWidgets: (widgets: WidgetInstance[]) => void;
  /** Back to the default layout for the active view. */
  resetActiveView: () => void;
}

function updateActiveView(state: DashboardLayout, widgets: WidgetInstance[]): Pick<DashboardLayout, 'views'> {
  return { views: state.views.map((v) => (v.id === state.activeViewId ? { ...v, widgets } : v)) };
}

export const useDashboardLayoutStore = create<DashboardLayoutState>()(
  persist(
    (set) => ({
      ...createDefaultLayout(),
      setActiveViewWidgets: (widgets) => set((state) => updateActiveView(state, widgets)),
      resetActiveView: () => set((state) => updateActiveView(state, [])),
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
