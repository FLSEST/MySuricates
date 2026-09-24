import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { modulesFixture } from '../../test/modules';
import { useI18nStore } from '../../i18n';
import { useSettingsStore } from '../../stores/settingsStore';
import { isNestedAt } from '../../components/layout/navItems';
import { sidebarEntryId, useSidebarOrder } from '../useSidebarOrder';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
  useSettingsStore.setState({ simpleMode: false, sidebarItemOrder: [], sidebarHiddenItems: [], sidebarDividerLabels: {} });
});

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

function serveModules(disabled: Parameters<typeof modulesFixture>[1]) {
  server.use(http.get('/api/v1/system/capabilities', () =>
    HttpResponse.json({ routes: [], ...modulesFixture('full', disabled) })));
}

type Order = ReturnType<typeof useSidebarOrder>;
const ids = (entries: Order['visibleEntries']) => entries.map(sidebarEntryId);
const dividers = (entries: Order['visibleEntries']) => ids(entries).filter((id) => id.startsWith('divider:'));

describe('useSidebarOrder', () => {
  it('shows the default order with the four default dividers when every module is on', async () => {
    const { result } = renderHook(() => useSidebarOrder(), { wrapper });
    await waitFor(() => expect(ids(result.current.visibleEntries)).toContain('/chains'));

    const visible = ids(result.current.visibleEntries);
    expect(visible.slice(0, 4)).toEqual(['/', '/projects', '/todos', '/goals']);
    expect(visible.indexOf('divider:default-triggers')).toBe(visible.indexOf('/cockpit') - 1);
    expect(dividers(result.current.visibleEntries)).toHaveLength(4);
    expect(result.current.hiddenNavItems).toEqual([]);
  });

  it('drops pages of disabled modules and the divider of a group that became empty', async () => {
    serveModules(['chains', 'onboarding-profiles']);
    const { result } = renderHook(() => useSidebarOrder(), { wrapper });
    await waitFor(() => expect(ids(result.current.visibleEntries)).not.toContain('/chains'));

    expect(ids(result.current.visibleEntries)).not.toContain('/onboarding-profiles');
    expect(dividers(result.current.visibleEntries)).not.toContain('divider:default-onboarding');
    // Edit mode still lists every stored divider, but no page of a disabled module
    expect(ids(result.current.editModeEntries)).toContain('divider:default-onboarding');
    expect(ids(result.current.editModeEntries)).not.toContain('/chains');
  });

  it('shows only the Simple pages under their Simple titles, without dividers', () => {
    useSettingsStore.setState({ simpleMode: true });
    const { result } = renderHook(() => useSidebarOrder(), { wrapper });

    const entries = result.current.visibleEntries;
    expect(ids(entries)).toEqual(['/', '/projects', '/todos', '/workflows', '/runs', '/schedules', '/activity']);
    const labels = entries.map((entry) => (entry.kind === 'item' ? entry.item.label : entry.id));
    expect(labels).toEqual(['Home', 'Projects', 'Tasks', 'My Workflows', 'Results', 'Scheduled', 'Activity']);
  });

  it('hides a page and shows it again', () => {
    const { result } = renderHook(() => useSidebarOrder(), { wrapper });

    act(() => result.current.hide('/runs'));
    expect(useSettingsStore.getState().sidebarHiddenItems).toEqual(['/runs']);
    expect(ids(result.current.visibleEntries)).not.toContain('/runs');
    expect(ids(result.current.editModeEntries)).not.toContain('/runs');
    expect(result.current.hiddenNavItems.map((item) => [item.to, item.label])).toEqual([['/runs', 'Runs']]);

    act(() => result.current.show('/runs'));
    expect(useSettingsStore.getState().sidebarHiddenItems).toEqual([]);
    expect(ids(result.current.visibleEntries)).toContain('/runs');
  });

  it('moves an entry within the full order, so pages of disabled modules keep their place', async () => {
    serveModules(['chains']);
    const { result } = renderHook(() => useSidebarOrder(), { wrapper });
    await waitFor(() => expect(ids(result.current.visibleEntries)).not.toContain('/chains'));

    act(() => result.current.moveEntry('/cockpit', '/runs'));
    const stored = useSettingsStore.getState().sidebarItemOrder;
    expect(stored.indexOf('/cockpit')).toBe(stored.indexOf('/runs') - 1);
    // /chains is not shown, but stays stored right after /debug as before
    expect(stored.indexOf('/chains')).toBe(stored.indexOf('/debug') + 1);
  });

  it('adds a divider at the end and removes one together with its name', () => {
    const { result } = renderHook(() => useSidebarOrder(), { wrapper });

    act(() => result.current.addDivider());
    const added = useSettingsStore.getState().sidebarItemOrder.at(-1)!;
    expect(added).toMatch(/^divider:/);
    // At the end it separates nothing: edit mode shows it, the menu does not
    expect(ids(result.current.editModeEntries).at(-1)).toBe(added);
    expect(ids(result.current.visibleEntries)).not.toContain(added);

    act(() => result.current.setDividerLabel('divider:default-ideas', 'Ideen'));
    expect(result.current.dividerLabels).toEqual({ 'divider:default-ideas': 'Ideen' });
    act(() => result.current.removeDivider('divider:default-ideas'));
    expect(useSettingsStore.getState().sidebarItemOrder).not.toContain('divider:default-ideas');
    expect(result.current.dividerLabels).toEqual({});
  });

  it('resets order, hidden pages and divider names', () => {
    useSettingsStore.setState({
      sidebarItemOrder: ['/todos', '/'], sidebarHiddenItems: ['/runs'], sidebarDividerLabels: { 'divider:x': 'X' },
    });
    const { result } = renderHook(() => useSidebarOrder(), { wrapper });
    expect(ids(result.current.visibleEntries).slice(0, 2)).toEqual(['/todos', '/']);

    act(() => result.current.reset());
    expect(useSettingsStore.getState()).toMatchObject({
      sidebarItemOrder: [], sidebarHiddenItems: [], sidebarDividerLabels: {},
    });
    expect(ids(result.current.visibleEntries).slice(0, 2)).toEqual(['/', '/projects']);
  });
});

describe('isNestedAt', () => {
  it('nests goals only while they follow the todos directly', () => {
    const { result } = renderHook(() => useSidebarOrder(), { wrapper });
    const entries = result.current.visibleEntries;
    expect(isNestedAt(entries, ids(entries).indexOf('/goals'))).toBe(true);
    expect(isNestedAt(entries, ids(entries).indexOf('/todos'))).toBe(false);

    act(() => result.current.moveEntry('/goals', '/workflows'));
    const moved = result.current.visibleEntries;
    expect(isNestedAt(moved, ids(moved).indexOf('/goals'))).toBe(false);
  });
});
