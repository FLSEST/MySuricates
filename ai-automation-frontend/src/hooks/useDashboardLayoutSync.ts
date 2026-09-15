import { useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { apiErrorMessage } from '../api/client';
import { getTranslations } from '../i18n';
import { useToastStore } from '../stores/toastStore';
import { useDashboardLayoutStore } from '../stores/dashboardLayoutStore';
import { useDashboardSyncStore } from '../stores/dashboardSyncStore';
import {
  LAYOUT_FORMAT_VERSION,
  isCustomizedLayout,
  migrateLayout,
  sanitizeLayout,
  type DashboardLayout,
} from '../components/dashboard/dashboardLayout';
import {
  DASHBOARD_LAYOUT_VERSIONS_KEY,
  fetchDashboardLayoutVersion,
  fetchLatestDashboardLayoutVersion,
  saveDashboardLayoutVersion,
} from './useDashboardLayoutVersions';
import type { DashboardLayoutVersion, DashboardLayoutVersionSummary } from '../types';

function currentLayout(): DashboardLayout {
  const { activeViewId, views } = useDashboardLayoutStore.getState();
  return sanitizeLayout({ activeViewId, views });
}

function isConflict(err: unknown): boolean {
  return !!err && typeof err === 'object' && (err as { status?: number }).status === 409;
}

function notify(type: 'error' | 'warning', message: string) {
  useToastStore.getState().addToast(type, message);
}

/** The local layout differs from the server version it is based on. */
export function hasLocalLayoutChanges(): boolean {
  const { baseVersionId, dirty } = useDashboardSyncStore.getState();
  return dirty || (baseVersionId === null && isCustomizedLayout(currentLayout()));
}

/** Current-format layout of a stored version; null when it was written by a newer app version. */
export function layoutFromVersion(version: DashboardLayoutVersion): DashboardLayout | null {
  if (version.formatVersion > LAYOUT_FORMAT_VERSION) return null;
  return sanitizeLayout(migrateLayout(version.layout, version.formatVersion));
}

/** Loads a version into the local layout. False when it cannot be read (newer format). */
async function applyVersion(id: string): Promise<boolean> {
  const version = await fetchDashboardLayoutVersion(id);
  const layout = layoutFromVersion(version);
  if (!layout) {
    notify('warning', getTranslations().dashboard.sync.newerFormat);
    return false;
  }
  useDashboardLayoutStore.getState().applyRemoteLayout(layout);
  useDashboardSyncStore.getState().markSynced(version.id);
  return true;
}

/**
 * Keeps the per-browser dashboard layout in step with the version history on the server (spec E1).
 * Reads store state directly so the actions never work with a stale render snapshot.
 */
export function useDashboardLayoutSync() {
  const queryClient = useQueryClient();
  const [conflict, setConflict] = useState<DashboardLayoutVersionSummary | null>(null);
  const [busy, setBusy] = useState(false);
  // Once the user acts, a late answer of the initial check must not overwrite the result.
  const userActed = useRef(false);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const latest = await fetchLatestDashboardLayoutVersion();
        if (cancelled || userActed.current || !latest) return;
        if (latest.id === useDashboardSyncStore.getState().baseVersionId) return;
        if (hasLocalLayoutChanges()) {
          setConflict(latest);
        } else {
          await applyVersion(latest.id);
        }
      } catch {
        // Offline or older backend: keep working locally; the next save tries again.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const run = async (task: () => Promise<void>) => {
    userActed.current = true;
    setBusy(true);
    try {
      await task();
    } finally {
      setBusy(false);
    }
  };

  const persistSnapshot = async (force: boolean) => {
    const { deviceLabel, baseVersionId } = useDashboardSyncStore.getState();
    try {
      const saved = await saveDashboardLayoutVersion({
        deviceLabel,
        formatVersion: LAYOUT_FORMAT_VERSION,
        layout: currentLayout(),
        baseVersionId,
        force,
      });
      useDashboardSyncStore.getState().markSynced(saved.id);
      setConflict(null);
      await queryClient.invalidateQueries({ queryKey: DASHBOARD_LAYOUT_VERSIONS_KEY });
    } catch (err) {
      if (isConflict(err)) {
        const latest = await fetchLatestDashboardLayoutVersion().catch(() => null);
        if (latest) setConflict(latest);
        return;
      }
      notify('error', apiErrorMessage(err, getTranslations().dashboard.sync.saveFailed));
    }
  };

  const loadAndThen = async (task: () => Promise<void>) => {
    try {
      await task();
    } catch (err) {
      notify('error', apiErrorMessage(err, getTranslations().dashboard.sync.loadFailed));
    }
  };

  return {
    conflict,
    busy,
    /** Stores the local layout as a new version; `force` skips the check that no other device saved meanwhile. */
    saveSnapshot: (force = false) => run(() => persistSnapshot(force)),
    keepRemote: () => run(() => loadAndThen(async () => {
      if (conflict && (await applyVersion(conflict.id))) setConflict(null);
    })),
    keepLocal: () => run(() => persistSnapshot(true)),
    restoreVersion: (id: string) => run(() => loadAndThen(async () => {
      if (await applyVersion(id)) await persistSnapshot(true);
    })),
    adoptView: (versionId: string, viewId: string) => run(() => loadAndThen(async () => {
      const layout = layoutFromVersion(await fetchDashboardLayoutVersion(versionId));
      if (!layout) {
        notify('warning', getTranslations().dashboard.sync.newerFormat);
        return;
      }
      const view = layout.views.find((v) => v.id === viewId);
      if (!view) return;
      useDashboardLayoutStore.getState().upsertView(view);
      await persistSnapshot(false);
    })),
  };
}
