import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { isDividerId, withDefaultDividers } from '../utils/sidebarLayout';
import { HINTS } from '../data/hints';

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
  /**
   * The user picked a mode on purpose (start-page card or the mode switch). Until then a fresh
   * installation without projects offers the choice; the default stays Pro.
   */
  modeChoiceMade: boolean;
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
  /** Welcome walkthrough dismissed; set back to false to show it again. */
  walkthroughSeen: boolean;
  /** Hints in place (components/hints/Hint) shown at all. */
  hintsEnabled: boolean;
  /** Dismissed hints: id → the hint version that was dismissed (a raised version shows it again). */
  dismissedHints: Record<string, number>;
  /**
   * AI provider card on the start page hidden for exactly this status (`aiStatusFingerprint`); a changed
   * status shows it again. Empty = not hidden.
   */
  aiStatusDismissed: string;
  /** Todos list view: rows per page. */
  listPageSize: number;
  /** Todos board view: cards loaded per kanban column (one query per column). */
  kanbanColumnSize: number;
  setApiKey: (key: string) => void;
  setSimpleMode: (enabled: boolean) => void;
  /** An explicit mode choice: sets the mode and marks the choice as made. */
  chooseMode: (simple: boolean) => void;
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
  setWalkthroughSeen: (seen: boolean) => void;
  setHintsEnabled: (enabled: boolean) => void;
  dismissHint: (id: string, version: number) => void;
  restoreHint: (id: string) => void;
  /** Shows every hint again and switches hints on. */
  resetHints: () => void;
  setAiStatusDismissed: (fingerprint: string) => void;
  setListPageSize: (size: number) => void;
  setKanbanColumnSize: (size: number) => void;
}

/** Persisted shape before v2 — `simpleOnboardingSeen` is gone since. */
type PersistedSettings = Partial<SettingsState> & { simpleOnboardingSeen?: boolean };

/**
 * A custom order saved while the goals sat behind the cockpit (or were appended at the end, because
 * the order did not know them yet) gets them right after the todos. A place chosen on purpose stays.
 */
function moveGoalsUnderTodos(order: string[]): string[] {
  const todos = order.indexOf('/todos');
  if (todos < 0) return order;
  const goals = order.indexOf('/goals');
  let lastRoute = order.length - 1;
  while (lastRoute >= 0 && isDividerId(order[lastRoute]!)) lastRoute -= 1;
  const misplaced = goals < 0 || goals === lastRoute || order[goals - 1] === '/cockpit';
  if (!misplaced || goals === todos + 1) return order;
  const without = order.filter((entry) => entry !== '/goals');
  without.splice(without.indexOf('/todos') + 1, 0, '/goals');
  return without;
}

/** Upgrades a persisted state from an older `version`. */
function migrateSettings(persisted: unknown, version: number): SettingsState {
  const state = { ...(persisted as PersistedSettings) };
  // v1 introduced sidebar dividers: a custom order saved before gets the default dividers.
  if (version < 1 && state.sidebarItemOrder?.length) {
    state.sidebarItemOrder = withDefaultDividers(state.sidebarItemOrder);
  }
  // v2 replaced the Simple-mode welcome card by the hint `simple-home`: whoever closed the card
  // does not get the same welcome again as a hint.
  if (version < 2) {
    if (state.simpleOnboardingSeen === true) {
      state.dismissedHints = { ...state.dismissedHints, 'simple-home': HINTS['simple-home'].version };
    }
    delete state.simpleOnboardingSeen;
  }
  // v3 put the goals directly under the todos (TODO 49080447).
  if (version < 3 && state.sidebarItemOrder?.length) {
    state.sidebarItemOrder = moveGoalsUnderTodos(state.sidebarItemOrder);
  }
  return state as SettingsState;
}

export const useSettingsStore = create<SettingsState>()(
  persist(
    (set) => ({
      apiKey: '',
      simpleMode: false,
      modeChoiceMade: false,
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
      walkthroughSeen: false,
      hintsEnabled: true,
      dismissedHints: {},
      aiStatusDismissed: '',
      listPageSize: 30,
      kanbanColumnSize: 50,
      setApiKey: (apiKey) => set({ apiKey }),
      setSimpleMode: (simpleMode) => set({ simpleMode }),
      chooseMode: (simpleMode) => set({ simpleMode, modeChoiceMade: true }),
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
      setWalkthroughSeen: (walkthroughSeen) => set({ walkthroughSeen }),
      setHintsEnabled: (hintsEnabled) => set({ hintsEnabled }),
      dismissHint: (id, version) => set((state) => ({ dismissedHints: { ...state.dismissedHints, [id]: version } })),
      restoreHint: (id) =>
        set((state) => {
          const { [id]: _dismissed, ...rest } = state.dismissedHints;
          return { dismissedHints: rest };
        }),
      resetHints: () => set({ hintsEnabled: true, dismissedHints: {} }),
      setAiStatusDismissed: (aiStatusDismissed) => set({ aiStatusDismissed }),
      setListPageSize: (size) => set({ listPageSize: clampPageSize(size) }),
      setKanbanColumnSize: (size) => set({ kanbanColumnSize: clampPageSize(size) }),
    }),
    {
      name: 'app-settings',
      version: 3,
      migrate: migrateSettings,
    }
  )
);
