import { describe, it, expect } from 'vitest';
import { en } from '../../../i18n/en';
import { de } from '../../../i18n/de';
import { modulesFixture } from '../../../test/modules';
import { DASHBOARD_WIDGETS, WIDGET_SIZE_CLASSES } from '../widgetRegistry';

describe('dashboard widget registry', () => {
  it('uses unique kebab-case ids (persisted in layouts, never rename)', () => {
    const ids = DASHBOARD_WIDGETS.map((w) => w.id);
    expect(new Set(ids).size).toBe(ids.length);
    for (const id of ids) expect(id).toMatch(/^[a-z][a-z0-9]*(-[a-z0-9]+)*$/);
  });

  it.each([['en', en], ['de', de]] as const)('has unique, non-empty labels in %s', (_locale, t) => {
    const labels = DASHBOARD_WIDGETS.map((w) => w.label(t));
    for (const label of labels) expect(label.trim()).not.toBe('');
    expect(new Set(labels).size).toBe(labels.length);
  });

  it('references only known modules and sizes', () => {
    const knownModules = new Set<string>(modulesFixture().modules.map((m) => m.name));
    for (const widget of DASHBOARD_WIDGETS) {
      if (widget.module) expect(knownModules).toContain(widget.module);
      expect(Object.keys(WIDGET_SIZE_CLASSES)).toContain(widget.size);
    }
  });
});
