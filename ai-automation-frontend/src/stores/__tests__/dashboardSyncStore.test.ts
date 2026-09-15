import { describe, it, expect, beforeEach } from 'vitest';
import { createDeviceLabel, useDashboardSyncStore } from '../dashboardSyncStore';

beforeEach(() => {
  localStorage.clear();
  useDashboardSyncStore.setState({ deviceLabel: 'Browser test', baseVersionId: null, dirty: false });
});

describe('dashboardSyncStore', () => {
  it('generates a short default device label', () => {
    expect(createDeviceLabel()).toMatch(/^Browser [0-9a-f]{4}$/);
  });

  it('marks local changes and clears them when synced', () => {
    useDashboardSyncStore.getState().markDirty();
    expect(useDashboardSyncStore.getState().dirty).toBe(true);

    useDashboardSyncStore.getState().markSynced('version-7');
    expect(useDashboardSyncStore.getState()).toMatchObject({ baseVersionId: 'version-7', dirty: false });
  });

  it('stores a cleaned device label and ignores empty ones', () => {
    useDashboardSyncStore.getState().setDeviceLabel('  Laptop im Büro  ');
    expect(useDashboardSyncStore.getState().deviceLabel).toBe('Laptop im Büro');

    useDashboardSyncStore.getState().setDeviceLabel('   ');
    expect(useDashboardSyncStore.getState().deviceLabel).toBe('Laptop im Büro');

    useDashboardSyncStore.getState().setDeviceLabel('x'.repeat(120));
    expect(useDashboardSyncStore.getState().deviceLabel).toHaveLength(80);
  });

  it('persists label, base version and dirty flag', () => {
    useDashboardSyncStore.getState().markSynced('version-1');
    useDashboardSyncStore.getState().markDirty();

    expect(JSON.parse(localStorage.getItem('dashboard-sync') ?? '{}')).toEqual({
      version: 1,
      state: { deviceLabel: 'Browser test', baseVersionId: 'version-1', dirty: true },
    });
  });

  it('persists the generated device label on first hydration', async () => {
    localStorage.removeItem('dashboard-sync');

    await useDashboardSyncStore.persist.rehydrate();

    const stored = JSON.parse(localStorage.getItem('dashboard-sync') ?? '{}');
    expect(stored.state?.deviceLabel).toBe(useDashboardSyncStore.getState().deviceLabel);
  });

  it('rehydrates corrupt data with safe values', async () => {
    localStorage.setItem('dashboard-sync', JSON.stringify({
      version: 1,
      state: { deviceLabel: 42, baseVersionId: { id: 'x' }, dirty: 'yes' },
    }));

    await useDashboardSyncStore.persist.rehydrate();

    expect(useDashboardSyncStore.getState()).toMatchObject({ deviceLabel: 'Browser test', baseVersionId: null, dirty: false });
  });
});
