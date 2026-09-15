import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { generateId } from '../utils/uuid';

const MAX_DEVICE_LABEL = 80;

export function createDeviceLabel(): string {
  return `Browser ${generateId().slice(0, 4)}`;
}

/** Trims, drops control characters (the backend rejects them) and caps the length. */
function cleanLabel(label: string): string {
  return Array.from(label)
    .filter((ch) => {
      const code = ch.charCodeAt(0);
      return code >= 32 && (code < 127 || code > 159);
    })
    .join('')
    .trim()
    .slice(0, MAX_DEVICE_LABEL);
}

interface DashboardSyncState {
  /** Shown in the version history; the platform has no user accounts, so each browser names itself. */
  deviceLabel: string;
  /** Server version the local layout was last saved as or loaded from. */
  baseVersionId: string | null;
  /** The local layout changed since baseVersionId. */
  dirty: boolean;
  setDeviceLabel: (label: string) => void;
  markDirty: () => void;
  markSynced: (versionId: string) => void;
}

export const useDashboardSyncStore = create<DashboardSyncState>()(
  persist(
    (set) => ({
      deviceLabel: createDeviceLabel(),
      baseVersionId: null,
      dirty: false,
      setDeviceLabel: (label) => {
        const cleaned = cleanLabel(label);
        if (cleaned) set({ deviceLabel: cleaned });
      },
      markDirty: () => set({ dirty: true }),
      markSynced: (versionId) => set({ baseVersionId: versionId, dirty: false }),
    }),
    {
      name: 'dashboard-sync',
      version: 1,
      partialize: ({ deviceLabel, baseVersionId, dirty }) => ({ deviceLabel, baseVersionId, dirty }),
      // Per-browser data — never trust its shape.
      merge: (persisted, current) => {
        const stored = persisted && typeof persisted === 'object' ? (persisted as Record<string, unknown>) : {};
        const label = typeof stored.deviceLabel === 'string' ? cleanLabel(stored.deviceLabel) : '';
        return {
          ...current,
          deviceLabel: label || current.deviceLabel,
          baseVersionId: typeof stored.baseVersionId === 'string' ? stored.baseVersionId : null,
          dirty: stored.dirty === true,
        };
      },
      // First-ever hydration (no stored entry yet) must write the generated device label back to
      // storage right away, instead of waiting for the next unrelated state change.
      onRehydrateStorage: () => (state) => {
        if (state) state.setDeviceLabel(state.deviceLabel);
      },
    },
  ),
);
