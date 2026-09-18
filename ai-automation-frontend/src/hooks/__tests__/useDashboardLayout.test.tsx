import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import type { ReactNode } from 'react';
import { act, renderHook } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { server } from '../../test/server';
import { createTestQueryClient } from '../../test/utils';
import { useDashboardLayoutStore } from '../../stores/dashboardLayoutStore';
import { useDashboardSyncStore } from '../../stores/dashboardSyncStore';
import { createDefaultLayout, type WidgetInstance } from '../../components/dashboard/dashboardLayout';
import type { DashboardWidgetDef } from '../../components/dashboard/widgetRegistry';
import { useDashboardLayout } from '../useDashboardLayout';

vi.mock('../../components/dashboard/widgetRegistry', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../components/dashboard/widgetRegistry')>();
  const Empty = () => null;
  const text = () => 'text';
  const widgets: DashboardWidgetDef[] = [
    { id: 'test-stat', label: () => 'Stat', description: text, category: 'overview', size: 'stat', component: Empty },
    { id: 'test-chart', label: () => 'Chart', description: text, category: 'runs', size: 'chart', catalogOnly: true, component: Empty },
    { id: 'test-note', label: () => 'Note', description: text, category: 'overview', size: 'third', multiple: true, component: Empty },
  ];
  return { ...actual, DASHBOARD_WIDGETS: widgets, DASHBOARD_WIDGET_IDS: widgets.map((w) => w.id) };
});

const stored = () => useDashboardLayoutStore.getState().views[0]?.widgets;
const dirty = () => useDashboardSyncStore.getState().dirty;

function renderLayout() {
  const queryClient = createTestQueryClient();
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  return renderHook(() => useDashboardLayout(), { wrapper });
}

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useDashboardLayoutStore.setState(createDefaultLayout());
  useDashboardSyncStore.setState({ baseVersionId: 'v1', dirty: false });
});

describe('useDashboardLayout', () => {
  it('offers every enabled widget in the catalog but shows only standard singletons by default', () => {
    const { result } = renderLayout();
    expect(result.current.available.map((d) => d.id)).toEqual(['test-stat', 'test-chart', 'test-note']);
    expect(result.current.visible.map((w) => w.instance.instanceId)).toEqual(['test-stat']);
    expect(result.current.canAppend).toBe(true);
  });

  it('adds a catalog widget and a multi instance, and removes them again', () => {
    const { result } = renderLayout();

    act(() => result.current.add('test-chart'));
    act(() => result.current.add('test-note', { text: 'hello' }));

    expect(result.current.visible.map((w) => w.def.id)).toEqual(['test-stat', 'test-chart', 'test-note']);
    const note = result.current.visible[2]?.instance as WidgetInstance;
    expect(note.config).toEqual({ text: 'hello' });
    expect(dirty()).toBe(true);

    act(() => result.current.remove('test-chart'));
    act(() => result.current.remove(note.instanceId));
    expect(stored()).toEqual([{ instanceId: 'test-stat', type: 'test-stat' }]);
  });

  it('never removes a standard singleton and ignores no-op edits', () => {
    const { result } = renderLayout();

    act(() => result.current.remove('test-stat'));
    act(() => result.current.add('test-stat'));
    act(() => result.current.move('test-stat', 'test-stat'));

    expect(stored()).toEqual([]);
    expect(dirty()).toBe(false);
  });

  it('sets a size and drops it again when the default size is chosen', () => {
    const { result } = renderLayout();

    act(() => result.current.setSize('test-stat', 'full'));
    expect(stored()).toEqual([{ instanceId: 'test-stat', type: 'test-stat', size: 'full' }]);

    act(() => result.current.setSize('test-stat', 'stat'));
    expect(stored()).toEqual([{ instanceId: 'test-stat', type: 'test-stat' }]);
  });

  it('shows a hidden singleton again through add', () => {
    useDashboardLayoutStore.setState({ views: [{ id: 'default', name: '', widgets: [{ instanceId: 'test-stat', type: 'test-stat', hidden: true }] }] });
    const { result } = renderLayout();
    expect(result.current.hidden.map((w) => w.def.id)).toEqual(['test-stat']);

    act(() => result.current.add('test-stat'));
    expect(result.current.visible.map((w) => w.def.id)).toEqual(['test-stat']);
  });
});
