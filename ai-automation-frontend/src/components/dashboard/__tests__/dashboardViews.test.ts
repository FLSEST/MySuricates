import { describe, it, expect } from 'vitest';
import {
  LAYOUT_LIMITS,
  cleanViewName,
  createViewId,
  layoutContentKey,
  truncateCodePoints,
  type DashboardLayout,
  type DashboardView,
  type WidgetInstance,
} from '../dashboardLayout';
import {
  activeViewOf,
  addLayoutView,
  duplicateLayoutView,
  moveLayoutView,
  removeLayoutView,
  renameLayoutView,
  selectLayoutView,
  setLayoutViewFilter,
} from '../dashboardViews';
import { sanitizeLayout } from '../dashboardLayoutSanitize';

const single = (id: string, extra: Partial<WidgetInstance> = {}): WidgetInstance => ({ instanceId: id, type: id, ...extra });

describe('view operations', () => {
  const view = (id: string, extra: Partial<DashboardView> = {}): DashboardView => ({ id, name: '', widgets: [], ...extra });
  const twoViews: DashboardLayout = {
    activeViewId: 'default',
    views: [view('default'), view('ops', { name: 'Ops', widgets: [single('a')], filter: { days: 7 } })],
  };

  it('creates view ids that follow the id rules', () => {
    const first = createViewId();
    expect(first).toMatch(/^view-[0-9a-f]{8}$/);
    expect(first).not.toBe(createViewId());
  });

  it('trims names and cuts them by code points, never inside an emoji', () => {
    const tooLong = '😀'.repeat(LAYOUT_LIMITS.nameLength + 5);
    expect(cleanViewName('  Betrieb  ')).toBe('Betrieb');
    expect(cleanViewName(tooLong)).toBe('😀'.repeat(LAYOUT_LIMITS.nameLength));
    expect(truncateCodePoints('a😀b', 2)).toBe('a😀');
    expect(sanitizeLayout({ activeViewId: 'x', views: [{ id: 'x', name: tooLong, widgets: [] }] }).views[0]?.name)
      .toBe('😀'.repeat(LAYOUT_LIMITS.nameLength));
  });

  it('adds an empty view, shows it and respects the view limit', () => {
    expect(addLayoutView(twoViews, '  Kosten ', 'view-00000001')).toEqual({
      activeViewId: 'view-00000001',
      views: [...twoViews.views, view('view-00000001', { name: 'Kosten' })],
    });
    expect(addLayoutView(twoViews, '', 'ops')).toBe(twoViews);
    const full: DashboardLayout = { activeViewId: 'v0', views: Array.from({ length: LAYOUT_LIMITS.views }, (_, i) => view(`v${i}`)) };
    expect(addLayoutView(full, '', 'view-00000002')).toBe(full);
  });

  it('adds a view with the given widgets as a copy of the list', () => {
    const widgets = [single('b'), single('a', { hidden: true })];
    const added = addLayoutView(twoViews, 'Betrieb', 'view-00000006', widgets);
    expect(added.views[2]).toEqual(view('view-00000006', { name: 'Betrieb', widgets }));
    expect(added.views[2]?.widgets).not.toBe(widgets);
  });

  it('finds the shown view, falling back to the first view for a stale id', () => {
    expect(activeViewOf({ ...twoViews, activeViewId: 'ops' })?.id).toBe('ops');
    expect(activeViewOf({ ...twoViews, activeViewId: 'gone' })?.id).toBe('default');
  });

  it('renames a view and leaves unknown views and unchanged names alone', () => {
    expect(renameLayoutView(twoViews, 'ops', ' Betrieb ').views[1]?.name).toBe('Betrieb');
    expect(renameLayoutView(twoViews, 'ops', 'Ops')).toBe(twoViews);
    expect(renameLayoutView(twoViews, 'missing', 'x')).toBe(twoViews);
  });

  it('duplicates widgets and filter right after the original and shows the copy', () => {
    const copied = duplicateLayoutView(twoViews, 'ops', 'Ops (copy)', 'view-00000003');
    expect(copied.activeViewId).toBe('view-00000003');
    expect(copied.views[2]).toEqual({ id: 'view-00000003', name: 'Ops (copy)', widgets: [single('a')], filter: { days: 7 } });
    expect(copied.views[2]?.widgets).not.toBe(twoViews.views[1]?.widgets);
    expect(duplicateLayoutView(twoViews, 'default', 'Copy', 'view-00000004').views.map((v) => v.id))
      .toEqual(['default', 'view-00000004', 'ops']);
    expect(duplicateLayoutView(twoViews, 'missing', 'x', 'view-00000005')).toBe(twoViews);
  });

  it('removes a view, hands the active view to a neighbour and keeps the last view', () => {
    expect(removeLayoutView({ ...twoViews, activeViewId: 'ops' }, 'ops')).toEqual({ activeViewId: 'default', views: [view('default')] });
    const three: DashboardLayout = { activeViewId: 'b', views: [view('a'), view('b'), view('c')] };
    expect(removeLayoutView(three, 'b')).toEqual({ activeViewId: 'c', views: [view('a'), view('c')] });
    expect(removeLayoutView(three, 'a')).toEqual({ activeViewId: 'b', views: [view('b'), view('c')] });
    const last: DashboardLayout = { activeViewId: 'a', views: [view('a')] };
    expect(removeLayoutView(last, 'a')).toBe(last);
    expect(removeLayoutView(three, 'missing')).toBe(three);
  });

  it('reorders views and switches only to existing views', () => {
    expect(moveLayoutView(twoViews, 'ops', 'default').views.map((v) => v.id)).toEqual(['ops', 'default']);
    expect(moveLayoutView(twoViews, 'ops', 'ops')).toBe(twoViews);
    expect(selectLayoutView(twoViews, 'ops').activeViewId).toBe('ops');
    expect(selectLayoutView(twoViews, 'default')).toBe(twoViews);
    expect(selectLayoutView(twoViews, 'missing')).toBe(twoViews);
  });

  it('sets a cleaned filter and removes an empty one', () => {
    expect(setLayoutViewFilter(twoViews, 'default', { projectId: 'p1', days: 400 }).views[0])
      .toEqual(view('default', { filter: { projectId: 'p1' } }));
    expect(setLayoutViewFilter(twoViews, 'ops', { days: 7 })).toBe(twoViews);
    expect(setLayoutViewFilter(twoViews, 'ops', {}).views[1]).toEqual(view('ops', { name: 'Ops', widgets: [single('a')] }));
    expect(setLayoutViewFilter(twoViews, 'missing', { days: 7 })).toBe(twoViews);
  });

  it('compares layout content without the active view', () => {
    expect(layoutContentKey({ ...twoViews, activeViewId: 'ops' })).toBe(layoutContentKey(twoViews));
    expect(layoutContentKey(renameLayoutView(twoViews, 'ops', 'Betrieb'))).not.toBe(layoutContentKey(twoViews));
  });
});
