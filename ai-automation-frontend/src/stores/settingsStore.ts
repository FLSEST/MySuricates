import { create } from 'zustand';
import { persist } from 'zustand/middleware';

interface SettingsState {
  apiKey: string;
  simpleMode: boolean;
  advancedMode: boolean;
  autoTodoDefault: boolean;
  autoRepairDefault: boolean;
  defaultAiProvider: string;
  assistantModel: string;
  sidebarItemOrder: string[];
  sidebarHiddenItems: string[];
  setApiKey: (key: string) => void;
  setSimpleMode: (enabled: boolean) => void;
  setAdvancedMode: (enabled: boolean) => void;
  setAutoTodoDefault: (enabled: boolean) => void;
  setAutoRepairDefault: (enabled: boolean) => void;
  setDefaultAiProvider: (provider: string) => void;
  setAssistantModel: (model: string) => void;
  setSidebarItemOrder: (order: string[]) => void;
  setSidebarHiddenItems: (hidden: string[]) => void;
}

export const useSettingsStore = create<SettingsState>()(
  persist(
    (set) => ({
      apiKey: '',
      simpleMode: false,
      advancedMode: false,
      autoTodoDefault: false,
      autoRepairDefault: false,
      defaultAiProvider: '',
      assistantModel: '',
      sidebarItemOrder: [],
      sidebarHiddenItems: [],
      setApiKey: (apiKey) => set({ apiKey }),
      setSimpleMode: (simpleMode) => set({ simpleMode }),
      setAdvancedMode: (advancedMode) => set({ advancedMode }),
      setAutoTodoDefault: (autoTodoDefault) => set({ autoTodoDefault }),
      setAutoRepairDefault: (autoRepairDefault) => set({ autoRepairDefault }),
      setDefaultAiProvider: (defaultAiProvider) => set({ defaultAiProvider }),
      setAssistantModel: (assistantModel) => set({ assistantModel }),
      setSidebarItemOrder: (sidebarItemOrder) => set({ sidebarItemOrder }),
      setSidebarHiddenItems: (sidebarHiddenItems) => set({ sidebarHiddenItems }),
    }),
    { name: 'app-settings' }
  )
);
