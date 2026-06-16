import { create } from 'zustand';
import { persist } from 'zustand/middleware';

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
  pinnedWorkflowIds: string[];
  simpleOnboardingSeen: boolean;
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
  togglePinnedWorkflow: (id: string) => void;
  setSimpleOnboardingSeen: (seen: boolean) => void;
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
      pinnedWorkflowIds: [],
      simpleOnboardingSeen: false,
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
      togglePinnedWorkflow: (id) =>
        set((state) => ({
          pinnedWorkflowIds: state.pinnedWorkflowIds.includes(id)
            ? state.pinnedWorkflowIds.filter((x) => x !== id)
            : [...state.pinnedWorkflowIds, id],
        })),
      setSimpleOnboardingSeen: (simpleOnboardingSeen) => set({ simpleOnboardingSeen }),
    }),
    { name: 'app-settings' }
  )
);
