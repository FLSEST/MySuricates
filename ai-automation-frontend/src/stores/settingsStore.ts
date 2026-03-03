import { create } from 'zustand';
import { persist } from 'zustand/middleware';

interface SettingsState {
  apiKey: string;
  advancedMode: boolean;
  autoTodoDefault: boolean;
  autoRepairDefault: boolean;
  defaultAiProvider: string;
  setApiKey: (key: string) => void;
  setAdvancedMode: (enabled: boolean) => void;
  setAutoTodoDefault: (enabled: boolean) => void;
  setAutoRepairDefault: (enabled: boolean) => void;
  setDefaultAiProvider: (provider: string) => void;
}

export const useSettingsStore = create<SettingsState>()(
  persist(
    (set) => ({
      apiKey: '',
      advancedMode: false,
      autoTodoDefault: false,
      autoRepairDefault: false,
      defaultAiProvider: '',
      setApiKey: (apiKey) => set({ apiKey }),
      setAdvancedMode: (advancedMode) => set({ advancedMode }),
      setAutoTodoDefault: (autoTodoDefault) => set({ autoTodoDefault }),
      setAutoRepairDefault: (autoRepairDefault) => set({ autoRepairDefault }),
      setDefaultAiProvider: (defaultAiProvider) => set({ defaultAiProvider }),
    }),
    { name: 'app-settings' }
  )
);
