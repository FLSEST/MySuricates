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

/** Applies an already-fetched version's layout. False when it cannot be read (newer format). */
function applyFetchedVersion(version: DashboardLayoutVersion): boolean {
  const layout = layoutFromVersion(version);
  if (!layout) {
    notify('warning', getTranslations().dashboard.sync.newerFormat);
    return false;
  }
  useDashboardLayoutStore.getState().applyRemoteLayout(layout);
  useDashboardSyncStore.getState().markSynced(version.id);
  return true;
}

/** Fetches and applies a version by id. False when it cannot be read (newer format). */
async function applyVersion(id: string): Promise<boolean> {
  return applyFetchedVersion(await fetchDashboardLayoutVersion(id));
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
  // Serializes saveSnapshot/keepLocal/restore/adopt: a save that starts while another is still in
  // flight must run only after it, so it reads the baseVersionId the prior save just set.
  const saveChain = useRef<Promise<void>>(Promise.resolve());

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const latest = await fetchLatestDashboardLayoutVersion();
        if (cancelled || userActed.current || !latest) return;
        if (latest.id === useDashboardSyncStore.getState().baseVersionId) return;
        if (latest.formatVersion > LAYOUT_FORMAT_VERSION) {
          notify('warning', getTranslations().dashboard.sync.newerFormat);
          return;
        }
        // Re-check after the detail fetch too: an edit made while it was in flight must not be
        // silently discarded by applying the fetched version underneath it.
        const version = await fetchDashboardLayoutVersion(latest.id);
        if (cancelled || userActed.current) return;
        if (hasLocalLayoutChanges()) {
          // The local layout was never actually changed against this version's content (e.g. a
          // customized layout that happens to match what another device just saved) — no real
          // conflict, just adopt its id as the base.
          const remoteLayout = layoutFromVersion(version);
          if (remoteLayout && JSON.stringify(remoteLayout) === JSON.stringify(currentLayout())) {
            useDashboardSyncStore.getState().markSynced(version.id);
            return;
          }
          setConflict(latest);
          return;
        }
        applyFetchedVersion(version);
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

  const doPersistSnapshot = async (force: boolean) => {
    const { deviceLabel, baseVersionId } = useDashboardSyncStore.getState();
    // Captured up front: an edit made while the request is in flight must not be reported as saved.
    const layout = currentLayout();
    try {
      const saved = await saveDashboardLayoutVersion({
        deviceLabel,
        formatVersion: LAYOUT_FORMAT_VERSION,
        layout,
        baseVersionId,
        force,
      });
      useDashboardSyncStore.getState().markSynced(saved.id);
      if (JSON.stringify(currentLayout()) !== JSON.stringify(layout)) {
        // Something changed the layout after it was captured above — the base moved, but that
        // change itself is still unsaved.
        useDashboardSyncStore.getState().markDirty();
      }
      setConflict(null);
      await queryClient.invalidateQueries({ queryKey: DASHBOARD_LAYOUT_VERSIONS_KEY });
    } catch (err) {
      if (isConflict(err)) {
        const latest = await fetchLatestDashboardLayoutVersion().catch(() => null);
        // Only a genuinely newer version is a conflict to resolve; a missing list result or the
        // version we are already based on means the 409 was transient — report it as a save failure.
        if (latest && latest.id !== useDashboardSyncStore.getState().baseVersionId) {
          setConflict(latest);
        } else {
          notify('error', getTranslations().dashboard.sync.saveFailed);
        }
        return;
      }
      notify('error', apiErrorMessage(err, getTranslations().dashboard.sync.saveFailed));
    }
  };

  /** Chains onto any save already in flight so it reads the baseVersionId that one just set. */
  const persistSnapshot = (force: boolean): Promise<void> => {
    const chained = saveChain.current.then(() => doPersistSnapshot(force));
    saveChain.current = chained;
    return chained;
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
      const version = await fetchDashboardLayoutVersion(id);
      const layout = layoutFromVersion(version);
      if (!layout) {
        notify('warning', getTranslations().dashboard.sync.newerFormat);
        return;
      }
      useDashboardLayoutStore.getState().applyRemoteLayout(layout);
      // Not yet synced: if the save below fails, the layout must not look saved to this version.
      useDashboardSyncStore.getState().markDirty();
      await persistSnapshot(true);
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
