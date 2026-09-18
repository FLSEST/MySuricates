import { describe, it, expect } from 'vitest';
import { en } from '../../../i18n/en';
import { de } from '../../../i18n/de';
import { modulesFixture } from '../../../test/modules';
import { DASHBOARD_WIDGETS, DEFAULT_WIDGET_IDS, WIDGET_SIZE_CLASSES } from '../widgetRegistry';
import { WIDGET_CATEGORIES } from '../widgetDefinition';
import { LAYOUT_LIMITS } from '../dashboardLayout';

// createInstanceId appends "-" + 8 hex chars
const INSTANCE_SUFFIX_LENGTH = '-xxxxxxxx'.length;

describe('dashboard widget registry', () => {
  it('uses unique kebab-case ids (persisted in layouts, never rename)', () => {
    const ids = DASHBOARD_WIDGETS.map((w) => w.id);
    expect(new Set(ids).size).toBe(ids.length);
    for (const id of ids) expect(id).toMatch(/^[a-z][a-z0-9]*(-[a-z0-9]+)*$/);
  });

  it('keeps ids short enough for multiple instances to stay under the registry guard', () => {
    for (const id of DASHBOARD_WIDGETS.map((w) => w.id)) {
      expect(id.length).toBeLessThanOrEqual(LAYOUT_LIMITS.idLength - INSTANCE_SUFFIX_LENGTH);
    }
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

  it('pins the standard singletons: new widgets must be catalogOnly or multiple', () => {
    // Standard singletons appear in every view by themselves, also in views made from a template.
    // A widget added later that is not catalogOnly would suddenly show up everywhere — change this
    // list only on purpose.
    expect(DEFAULT_WIDGET_IDS).toEqual([
      'stat-active-projects', 'stat-total-workflows', 'stat-runs-today', 'stat-success-rate', 'stat-total-cost',
      'stat-planned-today', 'stat-planned-week', 'stat-version', 'chart-runs', 'chart-success-rate', 'chart-todos',
      'upcoming-schedules', 'failing-workflows', 'provider-stats', 'integration-stats', 'model-stats', 'todos',
      'recent-runs', 'quick-actions',
    ]);
  });

  it.each([['en', en], ['de', de]] as const)('describes every widget for the catalog in %s', (_locale, t) => {
    for (const widget of DASHBOARD_WIDGETS) {
      expect(widget.description(t).trim()).not.toBe('');
      expect(WIDGET_CATEGORIES).toContain(widget.category);
    }
  });

  it('keeps the widget kinds apart', () => {
    for (const widget of DASHBOARD_WIDGETS) {
      expect(widget.multiple && widget.catalogOnly).toBeFalsy();
      if (widget.configForm) expect(widget.multiple).toBe(true);
    }
  });
});
