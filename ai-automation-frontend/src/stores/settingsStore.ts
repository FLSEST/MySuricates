import { create } from 'zustand';
import { persist } from 'zustand/middleware';

interface SettingsState {
  apiKey: string;
  advancedMode: boolean;
  autoTodoDefault: boolean;
  setApiKey: (key: string) => void;
  setAdvancedMode: (enabled: boolean) => void;
  setAutoTodoDefault: (enabled: boolean) => void;
}

export const useSettingsStore = create<SettingsState>()(
  persist(
    (set) => ({
      apiKey: '',
      advancedMode: false,
      autoTodoDefault: false,
      setApiKey: (apiKey) => set({ apiKey }),
      setAdvancedMode: (advancedMode) => set({ advancedMode }),
      setAutoTodoDefault: (autoTodoDefault) => set({ autoTodoDefault }),
    }),
    { name: 'app-settings' }
  )
);
