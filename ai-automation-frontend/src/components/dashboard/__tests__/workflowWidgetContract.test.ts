import { describe, it, expect } from 'vitest';
import type { ResolvedWidget } from '../../../hooks/useDashboardLayout';
import type { DashboardWidgetDef } from '../widgetDefinition';
import {
  MAX_WORKFLOW_WIDGET_IDS,
  WORKFLOW_RESULT_WIDGET_ID,
  WORKFLOW_STATUS_WIDGET_ID,
  isWorkflowId,
  parseWorkflowEntry,
  parseWorkflowResult,
  workflowIdsOf,
} from '../workflowWidgetContract';

const VALID = {
  title: 'Nightly QA',
  status: 'YELLOW',
  kpis: [{ label: 'Hunts', value: '1361', trend: 'UP' }, { label: 'Fehler', value: '12' }],
  items: ['3 Hunts ohne Route'],
  summary: 'Zwei Befunde.',
};

const widget = (type: string, config?: Record<string, unknown>): ResolvedWidget => ({
  def: { id: type } as DashboardWidgetDef,
  instance: { instanceId: `${type}-1`, type, config: config as ResolvedWidget['instance']['config'] },
});

describe('workflow widget contract', () => {
  it('accepts the full and the minimal contract', () => {
    expect(parseWorkflowResult(VALID)).toEqual({
      title: 'Nightly QA',
      status: 'YELLOW',
      kpis: [{ label: 'Hunts', value: '1361', trend: 'UP' }, { label: 'Fehler', value: '12' }],
      items: ['3 Hunts ohne Route'],
      summary: 'Zwei Befunde.',
    });
    expect(parseWorkflowResult({ status: 'GREEN' })).toEqual({ status: 'GREEN', kpis: [], items: [] });
  });

  // Markup, links and images are ordinary text in the contract: nothing is filtered, because
  // widgets render contract values only as React text nodes (spec E3).
  it.each([
    '<script>alert(1)</script>',
    '<img src=x onerror=alert(1)>',
    'javascript:alert(1)',
    '![pwn](https://example.invalid/p.png)',
    '[klick](https://example.invalid)',
    '&lt;b&gt;fett&lt;/b&gt;',
    'https://example.invalid/?key=',
    '**fett** `code`',
  ])('keeps hostile-looking text as plain text: %s', (hostile) => {
    expect(parseWorkflowResult({ status: 'RED', title: hostile, items: [hostile] })).toEqual({
      status: 'RED', title: hostile, kpis: [], items: [hostile],
    });
  });

  it.each([
    ['unknown field', { status: 'GREEN', extra: 1 }],
    ['unknown kpi field', { status: 'GREEN', kpis: [{ label: 'a', value: 'b', oops: 1 }] }],
    ['missing status', { title: 'x' }],
    ['unknown status', { status: 'BLUE' }],
    ['unknown trend', { status: 'GREEN', kpis: [{ label: 'a', value: 'b', trend: 'SIDEWAYS' }] }],
    ['number where a string belongs', { status: 'GREEN', kpis: [{ label: 'a', value: 7 }] }],
    ['NaN', { status: 'GREEN', summary: Number.NaN }],
    ['nested object in items', { status: 'GREEN', items: [{ a: 1 }] }],
    ['items not an array', { status: 'GREEN', items: 'one' }],
    ['empty string', { status: 'GREEN', title: '   ' }],
    ['null', null],
    ['array', [{ status: 'GREEN' }]],
    ['string', 'GREEN'],
  ])('rejects %s entirely', (_name, payload) => {
    expect(parseWorkflowResult(payload)).toBeNull();
  });

  it('rejects oversized fields, too many entries and a huge payload', () => {
    expect(parseWorkflowResult({ status: 'GREEN', title: 't'.repeat(80) })?.title).toHaveLength(80);
    expect(parseWorkflowResult({ status: 'GREEN', title: 't'.repeat(81) })).toBeNull();
    expect(parseWorkflowResult({ status: 'GREEN', summary: 's'.repeat(501) })).toBeNull();
    expect(parseWorkflowResult({ status: 'GREEN', items: ['i'.repeat(201)] })).toBeNull();
    expect(parseWorkflowResult({ status: 'GREEN', kpis: [{ label: 'l'.repeat(41), value: 'v' }] })).toBeNull();
    expect(parseWorkflowResult({ status: 'GREEN', kpis: Array.from({ length: 7 }, () => ({ label: 'l', value: 'v' })) })).toBeNull();
    expect(parseWorkflowResult({ status: 'GREEN', items: Array.from({ length: 11 }, () => 'i') })).toBeNull();
    expect(parseWorkflowResult({ status: 'GREEN', summary: 'x'.repeat(500_000) })).toBeNull();
  });

  it.each([
    ['line break', 'line\nbreak'],
    ['tab', 'tab\tstop'],
    ['zero width', 'zero​width'],
    ['bidi override', 'bidi‮override'],
    ['bom', 'bom﻿text'],
    ['private use', 'privateuse'],
    ['bell', 'bell'],
    // Backend hardening: non-breaking/invisible blanks embedded in otherwise readable text —
    // DashboardWidgetResultParser#isInvisible rejects these anywhere in a string, not just when
    // the whole string is blank-only (see also the blank-only cases below).
    ['non-breaking space', 'nb space'],
    ['figure space', 'figure space'],
    ['narrow no-break space', 'narrow space'],
    // Blank-only strings: individually legal Unicode letters/symbols that render as blank and are
    // not stripped by trim() — the practical exploit this hardening round closes.
    ['blank-only non-breaking space', ' '],
    ['blank-only figure space', ' '],
    ['blank-only narrow no-break space', ' '],
    ['blank-only hangul filler', 'ㅤ'],
    ['blank-only braille blank', '⠀'],
    ['blank-only hangul choseong filler', 'ᅟ'],
    ['blank-only hangul jungseong filler', 'ᅠ'],
    ['blank-only halfwidth hangul filler', 'ﾠ'],
  ])('rejects %s', (_name, text) => {
    expect(parseWorkflowResult({ status: 'GREEN', items: [text] })).toBeNull();
  });

  it('parses an entry and downgrades a result that does not match the contract', () => {
    const base = {
      workflowId: 'wf-a1', workflowName: 'Nightly QA', available: true,
      run: { runId: 'run-1', status: 'COMPLETED', startedAt: '2026-09-18T08:00:00Z', completedAt: null, durationMs: 1200, gateStatus: 'GREEN' },
      resultState: 'VALID', result: VALID,
    };

    expect(parseWorkflowEntry(base)?.result?.status).toBe('YELLOW');
    expect(parseWorkflowEntry({ ...base, result: { status: 'BLUE' } })).toMatchObject({ resultState: 'INVALID', result: null });
    expect(parseWorkflowEntry({ ...base, resultState: 'MISSING', result: null })).toMatchObject({ resultState: 'MISSING', result: null });
    expect(parseWorkflowEntry({ ...base, available: false, run: null, result: null, resultState: 'MISSING' })?.available).toBe(false);
    expect(parseWorkflowEntry({ ...base, run: { ...base.run, status: 'NOPE' } })?.run).toBeNull();
    expect(parseWorkflowEntry({ ...base, workflowId: 'WF-A1' })).toBeNull();
    expect(parseWorkflowEntry(null)).toBeNull();
  });

  it('cleans workflowName like a contract string, falling back to null when nothing usable remains', () => {
    const base = {
      workflowId: 'wf-a1', available: true,
      run: null, resultState: 'MISSING', result: null,
    };

    // Rendered as a widget heading, so it goes through the same invisible-character cleaning as
    // the contract fields (bidi override reorders how the name reads without looking different).
    expect(parseWorkflowEntry({ ...base, workflowName: 'Nightly‮QA' })?.workflowName).toBeNull();
    // Blank-only: individually legal Unicode, renders as an empty heading, not stripped by trim().
    expect(parseWorkflowEntry({ ...base, workflowName: '⠀' })?.workflowName).toBeNull();
    // A plain blank name falls back the same way a missing name does.
    expect(parseWorkflowEntry({ ...base, workflowName: '   ' })?.workflowName).toBeNull();
    expect(parseWorkflowEntry({ ...base, workflowName: null })?.workflowName).toBeNull();
    expect(parseWorkflowEntry({ ...base, workflowName: 'Nightly QA' })?.workflowName).toBe('Nightly QA');
  });

  it('collects the configured workflow ids of the shown widgets, sorted and deduplicated', () => {
    const widgets = [
      widget(WORKFLOW_RESULT_WIDGET_ID, { workflowId: 'wf-b1' }),
      widget('recent-runs'),
      widget(WORKFLOW_STATUS_WIDGET_ID, { workflowId: 'wf-a1' }),
      widget(WORKFLOW_STATUS_WIDGET_ID, { workflowId: 'wf-b1' }),
      widget(WORKFLOW_STATUS_WIDGET_ID, { workflowId: 'NOT AN ID' }),
      widget(WORKFLOW_STATUS_WIDGET_ID),
    ];

    expect(workflowIdsOf(widgets)).toEqual(['wf-a1', 'wf-b1']);
    expect(workflowIdsOf([])).toEqual([]);
    expect(workflowIdsOf(
      Array.from({ length: MAX_WORKFLOW_WIDGET_IDS + 5 }, (_, i) => widget(WORKFLOW_STATUS_WIDGET_ID, { workflowId: `wf-${i}` })),
    )).toHaveLength(MAX_WORKFLOW_WIDGET_IDS);
  });

  it('knows which strings can be a workflow id', () => {
    expect(isWorkflowId('wf-a1')).toBe(true);
    expect(isWorkflowId('WF-A1')).toBe(false);
    expect(isWorkflowId('../etc')).toBe(false);
    expect(isWorkflowId('a'.repeat(81))).toBe(false);
    expect(isWorkflowId(7)).toBe(false);
  });
});
