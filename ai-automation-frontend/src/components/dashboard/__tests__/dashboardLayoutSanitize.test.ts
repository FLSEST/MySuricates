import { describe, it, expect } from 'vitest';
import { LAYOUT_LIMITS, createDefaultLayout } from '../dashboardLayout';
import { migrateLayout, sanitizeLayout } from '../dashboardLayoutSanitize';

describe('sanitizeLayout', () => {
  it.each([null, undefined, 'garbage', 42, {}, { views: 'nope' }, { views: [] }, { views: [{ name: 'no id' }] }])(
    'falls back to the default layout for %j',
    (value) => {
      expect(sanitizeLayout(value)).toEqual(createDefaultLayout());
    },
  );

  it('keeps valid instances, config primitives and filters; drops everything else', () => {
    const layout = sanitizeLayout({
      activeViewId: 'missing',
      views: [
        {
          id: 'ops',
          name: 'Ops',
          filter: { projectId: '00000000-0000-0000-0000-000000000000', days: 7, extra: 'x' },
          widgets: [
            { instanceId: 'a', type: 'a', hidden: true, size: 'huge' },
            { instanceId: 'note-1', type: 'note', config: { text: 'hi', count: 3, on: false, nested: { x: 1 }, list: [1], bad: Infinity } },
            { instanceId: 'Bad Id', type: 'a' },
            { type: 'a' },
            'b',
            { instanceId: 'c', type: 'c', hidden: 'yes', config: { '<script>': 'x', long: 'x'.repeat(LAYOUT_LIMITS.configString + 1) } },
          ],
        },
        { id: 'ops', name: 'duplicate id', widgets: [] },
        { id: 'plan', name: 'P'.repeat(200), filter: { days: 0 }, widgets: [] },
      ],
    });
    expect(layout).toEqual({
      activeViewId: 'ops',
      views: [
        {
          id: 'ops',
          name: 'Ops',
          filter: { projectId: '00000000-0000-0000-0000-000000000000', days: 7 },
          widgets: [
            { instanceId: 'a', type: 'a', hidden: true },
            { instanceId: 'note-1', type: 'note', config: { text: 'hi', count: 3, on: false } },
            { instanceId: 'c', type: 'c' },
          ],
        },
        { id: 'plan', name: 'P'.repeat(LAYOUT_LIMITS.nameLength), widgets: [] },
      ],
    });
  });

  it('keeps only the first widget per instance id within a view', () => {
    const layout = sanitizeLayout({
      activeViewId: 'default',
      views: [{
        id: 'default',
        name: '',
        widgets: [
          { instanceId: 'a', type: 'a', hidden: true },
          { instanceId: 'a', type: 'a' },
          { instanceId: 'note-1', type: 'note' },
          { instanceId: 'note-1', type: 'note', config: { text: 'x' } },
        ],
      }],
    });
    expect(layout.views[0]?.widgets).toEqual([
      { instanceId: 'a', type: 'a', hidden: true },
      { instanceId: 'note-1', type: 'note' },
    ]);
  });
});

describe('migrateLayout', () => {
  const v1 = {
    activeViewId: 'default',
    views: [{ id: 'default', name: '', widgets: [{ id: 'quick-actions' }, { id: 'stat-version', hidden: true, size: 'full' }] }],
  };

  it('turns v1 placements into v2 singleton instances', () => {
    expect(sanitizeLayout(migrateLayout(v1, 1))).toEqual({
      activeViewId: 'default',
      views: [{
        id: 'default',
        name: '',
        widgets: [
          { instanceId: 'quick-actions', type: 'quick-actions' },
          { instanceId: 'stat-version', type: 'stat-version', hidden: true, size: 'full' },
        ],
      }],
    });
  });

  it('leaves current-version data and non-objects untouched', () => {
    const v2 = { activeViewId: 'default', views: [] };
    expect(migrateLayout(v2, 2)).toBe(v2);
    expect(migrateLayout('garbage', 1)).toBe('garbage');
  });
});
