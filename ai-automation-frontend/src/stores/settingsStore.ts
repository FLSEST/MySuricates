import { create } from 'zustand';
import { persist } from 'zustand/middleware';

interface SettingsState {
  apiKey: string;
  advancedMode: boolean;
  setApiKey: (key: string) => void;
  setAdvancedMode: (enabled: boolean) => void;
}

export const useSettingsStore = create<SettingsState>()(
  persist(
    (set) => ({
      apiKey: '',
      advancedMode: false,
      setApiKey: (apiKey) => set({ apiKey }),
      setAdvancedMode: (advancedMode) => set({ advancedMode }),
    }),
    { name: 'app-settings' }
  )
);
