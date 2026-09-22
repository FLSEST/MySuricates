import { describe, it, expect } from 'vitest';
import {
  DEFAULT_DIVIDERS,
  collapseDividers,
  effectiveOrder,
  isDividerId,
  newDividerId,
  resolveEntries,
  withDefaultDividers,
  type SidebarEntry,
} from './sidebarLayout';

const item = (to: string) => ({ to });
const shape = (entries: SidebarEntry<{ to: string }>[]) =>
  entries.map((e) => (e.kind === 'divider' ? `|${e.id}` : e.item.to));

describe('withDefaultDividers', () => {
  it('puts every default divider directly before its anchor route', () => {
    const routes = DEFAULT_DIVIDERS.map((d) => d.before);
    const order = withDefaultDividers(['/', ...routes]);
    DEFAULT_DIVIDERS.forEach((d) => {
      expect(order[order.indexOf(d.before) - 1]).toBe(d.id);
    });
  });

  it('skips dividers whose anchor is missing and does not duplicate existing ones', () => {
    const first = DEFAULT_DIVIDERS[0]!;
    expect(withDefaultDividers(['/a', '/b'])).toEqual(['/a', '/b']);
    expect(withDefaultDividers(['/a', first.id, first.before])).toEqual(['/a', first.id, first.before]);
  });
});

describe('effectiveOrder', () => {
  it('falls back to the default order and appends unknown available routes', () => {
    expect(effectiveOrder([], ['/a', '/b'], ['/a', '/b', '/new'])).toEqual(['/a', '/b', '/new']);
  });

  it('keeps entries of currently unavailable routes so their position survives', () => {
    expect(effectiveOrder(['/b', '/off', '/a'], ['/a', '/b'], ['/a', '/b'])).toEqual(['/b', '/off', '/a']);
  });
});

describe('resolveEntries', () => {
  it('maps dividers and available items, dropping unavailable routes', () => {
    const entries = resolveEntries(['/a', 'divider:x', '/off', '/b'], [item('/a'), item('/b')]);
    expect(shape(entries)).toEqual(['/a', '|divider:x', '/b']);
  });
});

describe('collapseDividers', () => {
  const entries = (...ids: string[]) =>
    ids.map((id): SidebarEntry<{ to: string }> =>
      isDividerId(id) ? { kind: 'divider', id } : { kind: 'item', item: item(id) });
  const noLabel = () => false;

  it('drops dividers at the start and at the end', () => {
    expect(shape(collapseDividers(entries('divider:1', '/a', 'divider:2'), noLabel))).toEqual(['/a']);
  });

  it('keeps only the last divider of a run, so the following group keeps its heading', () => {
    expect(shape(collapseDividers(entries('/a', 'divider:1', 'divider:2', '/b'), noLabel)))
      .toEqual(['/a', '|divider:2', '/b']);
  });

  it('keeps a named divider at the start — it is the heading of the first group', () => {
    const labelled = (id: string) => id === 'divider:head';
    expect(shape(collapseDividers(entries('divider:head', '/a', 'divider:tail'), labelled)))
      .toEqual(['|divider:head', '/a']);
  });

  it('returns nothing when no items are left', () => {
    expect(collapseDividers(entries('divider:1', 'divider:2'), noLabel)).toEqual([]);
  });
});

describe('newDividerId', () => {
  it('creates distinct divider ids', () => {
    const a = newDividerId();
    const b = newDividerId();
    expect(isDividerId(a)).toBe(true);
    expect(a).not.toBe(b);
  });
});
