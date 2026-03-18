import { create } from 'zustand';
import { persist } from 'zustand/middleware';

interface SettingsState {
  apiKey: string;
  simpleMode: boolean;
  advancedMode: boolean;
  autoTodoDefault: boolean;
  autoRepairDefault: boolean;
  defaultAiProvider: string;
  setApiKey: (key: string) => void;
  setSimpleMode: (enabled: boolean) => void;
  setAdvancedMode: (enabled: boolean) => void;
  setAutoTodoDefault: (enabled: boolean) => void;
  setAutoRepairDefault: (enabled: boolean) => void;
  setDefaultAiProvider: (provider: string) => void;
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
      setApiKey: (apiKey) => set({ apiKey }),
      setSimpleMode: (simpleMode) => set({ simpleMode }),
      setAdvancedMode: (advancedMode) => set({ advancedMode }),
      setAutoTodoDefault: (autoTodoDefault) => set({ autoTodoDefault }),
      setAutoRepairDefault: (autoRepairDefault) => set({ autoRepairDefault }),
      setDefaultAiProvider: (defaultAiProvider) => set({ defaultAiProvider }),
    }),
    { name: 'app-settings' }
  )
);
