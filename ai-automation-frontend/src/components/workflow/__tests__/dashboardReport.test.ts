import { describe, it, expect } from 'vitest';
import {
  EMPTY_REPORT_FORM, configJsonWithReport, formFromConfig, formFromProposal,
  hasPlaceholder, reportWarnings, REPORT_STATUSES,
} from '../dashboardReport';
import { WORKFLOW_RESULT_CAPS } from '../../dashboard/workflowWidgetContract';

describe('dashboardReport form model', () => {
  it('reads a full config into the form', () => {
    const form = formFromConfig(JSON.stringify({
      status: '{{ gate_status }}',
      title: 'Nightly QA',
      summary: 'Zwei Befunde.',
      kpis: [{ label: 'Hunts', value: '1361', trend: 'UP' }, { label: 'Fehler', value: '12' }],
      items: ['3 Hunts ohne Route'],
    }));

    expect(form).toEqual({
      status: '{{ gate_status }}',
      title: 'Nightly QA',
      summary: 'Zwei Befunde.',
      kpis: [{ label: 'Hunts', value: '1361', trend: 'UP' }, { label: 'Fehler', value: '12', trend: '' }],
      items: ['3 Hunts ohne Route'],
    });
  });

  it('is forgiving about a config that has none of the fields, and refuses broken JSON', () => {
    expect(formFromConfig('{}')).toEqual(EMPTY_REPORT_FORM);
    expect(formFromConfig('{"status": 5, "items": "nope", "kpis": [1]}')).toEqual(EMPTY_REPORT_FORM);
    expect(formFromConfig('not json')).toBeNull();
    expect(formFromConfig('[1,2]')).toBeNull();
  });

  it('writes the five contract fields and keeps every other config key', () => {
    const before = JSON.stringify({ timeout_seconds: 300, status: 'GREEN', note: 'keep me' });
    const after = JSON.parse(configJsonWithReport(before, {
      status: 'RED', title: '', summary: 'done',
      kpis: [{ label: 'a', value: 'b', trend: '' }], items: ['x'],
    })) as Record<string, unknown>;

    expect(after).toEqual({
      timeout_seconds: 300, note: 'keep me',
      status: 'RED', summary: 'done', kpis: [{ label: 'a', value: 'b' }], items: ['x'],
    });
    // An empty scalar field is removed, not written as an empty string.
    expect(after).not.toHaveProperty('title');
  });

  /**
   * A row the user just added is still empty, and the form is rebuilt from the config on every
   * keystroke — so an empty row has to survive the round trip, or it would vanish before it could
   * be filled in. The backend drops blank rows at run time, so nothing reaches the dashboard.
   */
  it('keeps an empty row so the editor can show it, and round-trips it', () => {
    const json = configJsonWithReport('{}', {
      status: 'GREEN', title: '', summary: '',
      kpis: [{ label: '', value: '', trend: '' }], items: [''],
    });

    expect(JSON.parse(json)).toEqual({
      status: 'GREEN', kpis: [{ label: '', value: '' }], items: [''],
    });
    expect(formFromConfig(json)).toEqual({
      status: 'GREEN', title: '', summary: '',
      kpis: [{ label: '', value: '', trend: '' }], items: [''],
    });
  });

  it('removes the contract fields again when the form empties them', () => {
    const before = JSON.stringify({ status: 'GREEN', title: 'gone', kpis: [{ label: 'a', value: 'b' }] });
    const after = JSON.parse(configJsonWithReport(before, EMPTY_REPORT_FORM)) as Record<string, unknown>;

    expect(after).toEqual({});
  });

  it('spots a placeholder', () => {
    expect(hasPlaceholder('{{ gate_status }}')).toBe(true);
    expect(hasPlaceholder('QA {{count}} runs')).toBe(true);
    expect(hasPlaceholder('plain text')).toBe(false);
    expect(hasPlaceholder('')).toBe(false);
  });

  it('warns about what the backend would refuse — but never about a placeholder value', () => {
    expect(reportWarnings(EMPTY_REPORT_FORM)).toEqual([{ field: 'status', rule: 'statusRequired' }]);
    expect(reportWarnings({ ...EMPTY_REPORT_FORM, status: 'BLUE' }))
      .toEqual([{ field: 'status', rule: 'statusUnknown' }]);
    // A placeholder cannot be judged here: its value only exists at run time.
    expect(reportWarnings({ ...EMPTY_REPORT_FORM, status: '{{ gate_status }}' })).toEqual([]);

    expect(reportWarnings({
      ...EMPTY_REPORT_FORM, status: 'GREEN', title: 't'.repeat(WORKFLOW_RESULT_CAPS.title + 1),
    })).toEqual([{ field: 'title', rule: 'tooLong' }]);

    expect(reportWarnings({
      ...EMPTY_REPORT_FORM, status: 'GREEN',
      items: Array.from({ length: WORKFLOW_RESULT_CAPS.items + 1 }, () => 'x'),
    })).toEqual([{ field: 'items', rule: 'tooMany' }]);

    expect(reportWarnings({
      ...EMPTY_REPORT_FORM, status: 'GREEN', kpis: [{ label: 'a', value: '', trend: '' }],
    })).toEqual([{ field: 'kpis[1]', rule: 'kpiIncomplete' }]);

    expect(reportWarnings({
      ...EMPTY_REPORT_FORM, status: 'GREEN',
      kpis: [{ label: 'x'.repeat(WORKFLOW_RESULT_CAPS.kpiText + 1), value: 'v', trend: '' }],
    })).toEqual([{ field: 'kpis[1].label', rule: 'tooLong' }]);
  });

  it('turns a validated proposal into a form', () => {
    expect(formFromProposal({
      title: 'Nightly QA', status: 'YELLOW',
      kpis: [{ label: 'Hunts', value: '1361', trend: 'UP' }, { label: 'Fehler', value: '12' }],
      items: ['3 Hunts ohne Route'], summary: 'Zwei Befunde.',
    })).toEqual({
      status: 'YELLOW', title: 'Nightly QA', summary: 'Zwei Befunde.',
      kpis: [{ label: 'Hunts', value: '1361', trend: 'UP' }, { label: 'Fehler', value: '12', trend: '' }],
      items: ['3 Hunts ohne Route'],
    });
  });

  it('knows the three contract statuses and nothing else', () => {
    expect([...REPORT_STATUSES]).toEqual(['GREEN', 'YELLOW', 'RED']);
  });
});
