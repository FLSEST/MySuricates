import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { withDefaultDividers } from '../utils/sidebarLayout';

/** Bounds for the todo page sizes — small enough to stay responsive, large enough to be useful. */
export const PAGE_SIZE_MIN = 10;
export const PAGE_SIZE_MAX = 200;

/** Clamps a user-entered page size into [PAGE_SIZE_MIN, PAGE_SIZE_MAX]; garbage falls back to the max. */
export function clampPageSize(value: number): number {
  if (!Number.isFinite(value)) return PAGE_SIZE_MAX;
  return Math.min(PAGE_SIZE_MAX, Math.max(PAGE_SIZE_MIN, Math.floor(value)));
}

interface SettingsState {
  apiKey: string;
  simpleMode: boolean;
  advancedMode: boolean;
  autoTodoDefault: boolean;
  autoRepairDefault: boolean;
  autoRepairMode: string;
  defaultAiProvider: string;
  assistantModel: string;
  suggestionsModel: string;
  suggestionsEnabled: boolean;
  sidebarItemOrder: string[];
  sidebarHiddenItems: string[];
  /** Optional names of sidebar dividers, keyed by divider id; unnamed dividers have no entry. */
  sidebarDividerLabels: Record<string, string>;
  pinnedWorkflowIds: string[];
  simpleOnboardingSeen: boolean;
  /** Welcome walkthrough dismissed; set back to false to show it again. */
  walkthroughSeen: boolean;
  /** Todos list view: rows per page. */
  listPageSize: number;
  /** Todos board view: cards loaded per kanban column (one query per column). */
  kanbanColumnSize: number;
  setApiKey: (key: string) => void;
  setSimpleMode: (enabled: boolean) => void;
  setAdvancedMode: (enabled: boolean) => void;
  setAutoTodoDefault: (enabled: boolean) => void;
  setAutoRepairDefault: (enabled: boolean) => void;
  setAutoRepairMode: (mode: string) => void;
  setDefaultAiProvider: (provider: string) => void;
  setAssistantModel: (model: string) => void;
  setSuggestionsModel: (model: string) => void;
  setSuggestionsEnabled: (enabled: boolean) => void;
  setSidebarItemOrder: (order: string[]) => void;
  setSidebarHiddenItems: (hidden: string[]) => void;
  setSidebarDividerLabel: (id: string, label: string) => void;
  setSidebarDividerLabels: (labels: Record<string, string>) => void;
  togglePinnedWorkflow: (id: string) => void;
  setSimpleOnboardingSeen: (seen: boolean) => void;
  setWalkthroughSeen: (seen: boolean) => void;
  setListPageSize: (size: number) => void;
  setKanbanColumnSize: (size: number) => void;
}

export const useSettingsStore = create<SettingsState>()(
  persist(
    (set) => ({
      apiKey: '',
      simpleMode: false,
      advancedMode: false,
      autoTodoDefault: false,
      autoRepairDefault: false,
      autoRepairMode: 'auto',
      defaultAiProvider: '',
      assistantModel: '',
      suggestionsModel: '',
      suggestionsEnabled: true,
      sidebarItemOrder: [],
      sidebarHiddenItems: [],
      sidebarDividerLabels: {},
      pinnedWorkflowIds: [],
      simpleOnboardingSeen: false,
      walkthroughSeen: false,
      listPageSize: 30,
      kanbanColumnSize: 50,
      setApiKey: (apiKey) => set({ apiKey }),
      setSimpleMode: (simpleMode) => set({ simpleMode }),
      setAdvancedMode: (advancedMode) => set({ advancedMode }),
      setAutoTodoDefault: (autoTodoDefault) => set({ autoTodoDefault }),
      setAutoRepairDefault: (autoRepairDefault) => set({ autoRepairDefault }),
      setAutoRepairMode: (autoRepairMode) => set({ autoRepairMode }),
      setDefaultAiProvider: (defaultAiProvider) => set({ defaultAiProvider }),
      setAssistantModel: (assistantModel) => set({ assistantModel }),
      setSuggestionsModel: (suggestionsModel) => set({ suggestionsModel }),
      setSuggestionsEnabled: (suggestionsEnabled) => set({ suggestionsEnabled }),
      setSidebarItemOrder: (sidebarItemOrder) => set({ sidebarItemOrder }),
      setSidebarHiddenItems: (sidebarHiddenItems) => set({ sidebarHiddenItems }),
      setSidebarDividerLabel: (id, label) =>
        set((state) => {
          const { [id]: _previous, ...rest } = state.sidebarDividerLabels;
          const trimmed = label.trim();
          return { sidebarDividerLabels: trimmed ? { ...rest, [id]: label } : rest };
        }),
      setSidebarDividerLabels: (sidebarDividerLabels) => set({ sidebarDividerLabels }),
      togglePinnedWorkflow: (id) =>
        set((state) => ({
          pinnedWorkflowIds: state.pinnedWorkflowIds.includes(id)
            ? state.pinnedWorkflowIds.filter((x) => x !== id)
            : [...state.pinnedWorkflowIds, id],
        })),
      setSimpleOnboardingSeen: (simpleOnboardingSeen) => set({ simpleOnboardingSeen }),
      setWalkthroughSeen: (walkthroughSeen) => set({ walkthroughSeen }),
      setListPageSize: (size) => set({ listPageSize: clampPageSize(size) }),
      setKanbanColumnSize: (size) => set({ kanbanColumnSize: clampPageSize(size) }),
    }),
    {
      name: 'app-settings',
      version: 1,
      migrate: (persisted, version) => {
        const state = persisted as Partial<SettingsState>;
        // v1 introduced sidebar dividers: a custom order saved before gets the default dividers.
        if (version < 1 && state.sidebarItemOrder?.length) {
          state.sidebarItemOrder = withDefaultDividers(state.sidebarItemOrder);
        }
        return state as SettingsState;
      },
    }
  )
);
