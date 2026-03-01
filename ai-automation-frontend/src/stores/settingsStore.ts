import { create } from 'zustand';
import { persist } from 'zustand/middleware';

interface SettingsState {
  apiKey: string;
  advancedMode: boolean;
  autoTodoDefault: boolean;
  autoRepairDefault: boolean;
  setApiKey: (key: string) => void;
  setAdvancedMode: (enabled: boolean) => void;
  setAutoTodoDefault: (enabled: boolean) => void;
  setAutoRepairDefault: (enabled: boolean) => void;
}

export const useSettingsStore = create<SettingsState>()(
  persist(
    (set) => ({
      apiKey: '',
      advancedMode: false,
      autoTodoDefault: false,
      autoRepairDefault: false,
      setApiKey: (apiKey) => set({ apiKey }),
      setAdvancedMode: (advancedMode) => set({ advancedMode }),
      setAutoTodoDefault: (autoTodoDefault) => set({ autoTodoDefault }),
      setAutoRepairDefault: (autoRepairDefault) => set({ autoRepairDefault }),
    }),
    { name: 'app-settings' }
  )
);
