import { describe, it, expect } from 'vitest';
import { en } from '../../../i18n/en';
import { de } from '../../../i18n/de';
import { DASHBOARD_TEMPLATES } from '../dashboardTemplates';
import { normalizeWidgets, templateWidgets } from '../dashboardWidgets';
import { DASHBOARD_WIDGETS } from '../widgetRegistry';

const byId = new Map(DASHBOARD_WIDGETS.map((w) => [w.id, w]));

describe('dashboard templates', () => {
  it('offers the four templates of the spec with their widgets', () => {
    expect(Object.fromEntries(DASHBOARD_TEMPLATES.map((t) => [t.id, t.widgetIds]))).toEqual({
      operations: ['failing-workflows', 'recent-runs', 'upcoming-schedules', 'todos'],
      costs: ['stat-total-cost', 'insights-cost-over-time', 'insights-cost-by-workflow', 'insights-tokens', 'model-stats'],
      quality: ['insights-gate-status', 'insights-failure-reasons', 'chart-success-rate'],
      planning: ['stat-planned-today', 'stat-planned-week', 'upcoming-schedules'],
    });
  });

  it('references only registered widgets and never multi (workflow) widgets', () => {
    for (const template of DASHBOARD_TEMPLATES) {
      for (const id of template.widgetIds) {
        expect(byId.has(id)).toBe(true);
        expect(byId.get(id)?.multiple).toBeFalsy();
      }
    }
  });

  it.each([['en', en], ['de', de]] as const)('has unique, non-empty names in %s', (_locale, t) => {
    const names = DASHBOARD_TEMPLATES.map((template) => template.name(t));
    for (const name of names) expect(name.trim()).not.toBe('');
    expect(new Set(names).size).toBe(names.length);
  });

  it.each(DASHBOARD_TEMPLATES.map((t) => [t.id, t] as const))('a view from "%s" shows exactly its widgets', (_id, template) => {
    const widgets = normalizeWidgets(templateWidgets(template.widgetIds, DASHBOARD_WIDGETS), DASHBOARD_WIDGETS);
    expect(widgets.filter((w) => !w.hidden).map((w) => w.instanceId)).toEqual(template.widgetIds);
  });
});
