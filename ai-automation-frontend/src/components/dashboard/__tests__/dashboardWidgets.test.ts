import { describe, it, expect, vi } from 'vitest';
import {
  createDefaultLayout,
  createInstanceId,
  isCustomizedLayout,
  type WidgetInstance,
  type WidgetTypeInfo,
} from '../dashboardLayout';
import {
  addWidgetInstance,
  moveWidget,
  normalizeWidgets,
  removeWidgetInstance,
  setWidgetHidden,
  updateWidgetConfig,
} from '../dashboardWidgets';

const TYPES: WidgetTypeInfo[] = [{ id: 'a' }, { id: 'b' }, { id: 'note', multiple: true }, { id: 'c' }];
const single = (id: string, extra: Partial<WidgetInstance> = {}): WidgetInstance => ({ instanceId: id, type: id, ...extra });

describe('normalizeWidgets', () => {
  it('returns all singleton widgets in registry order for an empty (default) list, never multi widgets', () => {
    expect(normalizeWidgets([], TYPES)).toEqual([single('a'), single('b'), single('c')]);
  });

  it('keeps the stored order, drops unknown types and duplicates, appends missing singletons', () => {
    const stored: WidgetInstance[] = [
      single('c', { hidden: true }),
      { instanceId: 'gone', type: 'removed-widget' },
      { instanceId: 'note-1', type: 'note', config: { text: 'x' } },
      single('a'),
      single('c'),
      { instanceId: 'a-copy', type: 'a' },
      { instanceId: 'note-1', type: 'note' },
      { instanceId: 'note-2', type: 'note' },
    ];
    expect(normalizeWidgets(stored, TYPES)).toEqual([
      single('c', { hidden: true }),
      { instanceId: 'note-1', type: 'note', config: { text: 'x' } },
      single('a'),
      { instanceId: 'note-2', type: 'note' },
      single('b'),
    ]);
  });

  it('drops singletons stored under another id and multi instances that take a singleton id', () => {
    const stored: WidgetInstance[] = [
      { instanceId: 'b', type: 'a' },
      { instanceId: 'a', type: 'note' },
      { instanceId: 'note-1', type: 'note' },
    ];
    expect(normalizeWidgets(stored, TYPES)).toEqual([
      { instanceId: 'note-1', type: 'note' }, single('a'), single('b'), single('c'),
    ]);
  });
});

describe('moveWidget', () => {
  const widgets = [single('a'), single('b', { hidden: true }), single('c'), { instanceId: 'note-1', type: 'note' }];

  it('moves an instance onto the position of another one, across hidden widgets', () => {
    expect(moveWidget(widgets, 'a', 'c').map((w) => w.instanceId)).toEqual(['b', 'c', 'a', 'note-1']);
    expect(moveWidget(widgets, 'note-1', 'a').map((w) => w.instanceId)).toEqual(['note-1', 'a', 'b', 'c']);
  });

  it('returns the list unchanged for unknown ids or a drop onto itself', () => {
    expect(moveWidget(widgets, 'x', 'a')).toBe(widgets);
    expect(moveWidget(widgets, 'a', 'x')).toBe(widgets);
    expect(moveWidget(widgets, 'a', 'a')).toBe(widgets);
  });
});

describe('instance operations', () => {
  it('sets and clears the hidden flag of one instance only', () => {
    const hidden = setWidgetHidden([single('a'), single('b', { size: 'full' })], 'b', true);
    expect(hidden).toEqual([single('a'), single('b', { size: 'full', hidden: true })]);
    expect(setWidgetHidden(hidden, 'b', false)).toEqual([single('a'), single('b', { size: 'full' })]);
  });

  it('adds, reconfigures and removes multi instances', () => {
    const added = addWidgetInstance([single('a')], TYPES, 'note', { text: 'hello' }, 'note-abc');
    expect(added).toEqual([single('a'), { instanceId: 'note-abc', type: 'note', config: { text: 'hello' } }]);
    const updated = updateWidgetConfig(added, 'note-abc', { text: 'bye', pinned: true });
    expect(updated[1]).toEqual({ instanceId: 'note-abc', type: 'note', config: { text: 'bye', pinned: true } });
    expect(removeWidgetInstance(updated, 'note-abc')).toEqual([single('a')]);
  });

  it('refuses to add singletons, unknown types and ids that are taken', () => {
    const widgets: WidgetInstance[] = [single('a'), { instanceId: 'note-1', type: 'note' }];
    expect(addWidgetInstance(widgets, TYPES, 'a', {}, 'a-2')).toBe(widgets);
    expect(addWidgetInstance(widgets, TYPES, 'unknown', {}, 'unknown-1')).toBe(widgets);
    expect(addWidgetInstance(widgets, TYPES, 'note', {}, 'note-1')).toBe(widgets);
    expect(addWidgetInstance(widgets, TYPES, 'note', {}, 'b')).toBe(widgets);
    expect(addWidgetInstance(widgets, TYPES, 'note', {}, 'note-2')).toEqual([...widgets, { instanceId: 'note-2', type: 'note' }]);
  });

  it('removes an empty config instead of storing {}', () => {
    expect(updateWidgetConfig([{ instanceId: 'note-1', type: 'note', config: { text: 'x' } }], 'note-1', {}))
      .toEqual([{ instanceId: 'note-1', type: 'note' }]);
  });

  it('recognizes layouts that differ from the default', () => {
    expect(isCustomizedLayout(createDefaultLayout())).toBe(false);
    expect(isCustomizedLayout({ activeViewId: 'default', views: [{ id: 'default', name: '', widgets: [single('a')] }] })).toBe(true);
    expect(isCustomizedLayout({ activeViewId: 'default', views: [{ id: 'default', name: '', widgets: [], filter: { days: 7 } }] })).toBe(true);
    expect(isCustomizedLayout({
      activeViewId: 'default',
      views: [{ id: 'default', name: '', widgets: [] }, { id: 'ops', name: 'Ops', widgets: [] }],
    })).toBe(true);
  });

  it('creates instance ids that are valid, type-prefixed and unique', () => {
    const first = createInstanceId('workflow-status');
    const second = createInstanceId('workflow-status');
    expect(first).toMatch(/^workflow-status-[0-9a-f]{8}$/);
    expect(first).not.toBe(second);
  });

  it('creates instance ids in a non-secure context, where crypto.randomUUID is undefined', () => {
    vi.stubGlobal('crypto', { getRandomValues: globalThis.crypto.getRandomValues.bind(globalThis.crypto) });
    try {
      expect(createInstanceId('workflow-status')).toMatch(/^workflow-status-[0-9a-f]{8}$/);
    } finally {
      vi.unstubAllGlobals();
    }
  });
});
