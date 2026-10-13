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
  kpis: [{ label: 'Orders', value: '1361', trend: 'UP' }, { label: 'Fehler', value: '12' }],
  items: ['3 Bestellungen ohne Versanddatum'],
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
      kpis: [{ label: 'Orders', value: '1361', trend: 'UP' }, { label: 'Fehler', value: '12' }],
      items: ['3 Bestellungen ohne Versanddatum'],
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
  ])('rejects %s (fatal anywhere in the string)', (_name, text) => {
    expect(parseWorkflowResult({ status: 'GREEN', items: [text] })).toBeNull();
  });

  // Fix round 2 (B1+B2): a code point that is merely "not visible" (every space separator other
  // than U+0020, the filler denylist, the non-format default-ignorables, unassigned) is legal
  // anywhere in a string that still has visible content elsewhere — DashboardWidgetTextRule#isVisible
  // only decides whether a code point counts toward that "at least one visible" requirement, it does
  // not make the whole string fatal. Round 1's stricter "any space separator anywhere is fatal"
  // broke ordinary German text such as "12 000", "z. B." or "§ 5" and is not the backend's rule.
  it.each([
    ['non-breaking space embedded in visible text', 'nb space', 'nb space'],
    ['figure space embedded in visible text', 'figure space', 'figure space'],
    ['narrow no-break space embedded in visible text', 'narrow space', 'narrow space'],
    ['German thousands separator (narrow no-break space)', '12 000 Zeilen', '12 000 Zeilen'],
    ['German abbreviation (non-breaking space)', 'z. B.', 'z. B.'],
    ['German paragraph reference (non-breaking space)', '§ 5 Abs. 1', '§ 5 Abs. 1'],
    ['emoji with a variation selector', '✅️', '✅️'],
  ])('accepts %s: %s', (_name, text, expected) => {
    expect(parseWorkflowResult({ status: 'GREEN', items: [text] })).toEqual({
      status: 'GREEN', kpis: [], items: [expected],
    });
  });

  // Blank-only strings: only "not visible" code points remain, so none of them counts toward "at
  // least one visible code point" — rejected in every field the contract validates as a string.
  const CLASS_TWO_ALONE: readonly [string, string][] = [
    ['non-breaking space', ' '],
    ['figure space', ' '],
    ['narrow no-break space', ' '],
    ['hangul filler', 'ㅤ'],
    ['braille pattern blank', '⠀'],
    ['hangul choseong filler', 'ᅟ'],
    ['hangul jungseong filler', 'ᅠ'],
    ['halfwidth hangul filler', 'ﾠ'],
  ];

  it.each(CLASS_TWO_ALONE)('rejects a title made only of %s', (_name, blank) => {
    expect(parseWorkflowResult({ status: 'GREEN', title: blank })).toBeNull();
  });
  it.each(CLASS_TWO_ALONE)('rejects a kpi label made only of %s', (_name, blank) => {
    expect(parseWorkflowResult({ status: 'GREEN', kpis: [{ label: blank, value: 'v' }] })).toBeNull();
  });
  it.each(CLASS_TWO_ALONE)('rejects a kpi value made only of %s', (_name, blank) => {
    expect(parseWorkflowResult({ status: 'GREEN', kpis: [{ label: 'l', value: blank }] })).toBeNull();
  });
  it.each(CLASS_TWO_ALONE)('rejects an item made only of %s', (_name, blank) => {
    expect(parseWorkflowResult({ status: 'GREEN', items: [blank] })).toBeNull();
  });
  it.each(CLASS_TWO_ALONE)('rejects a summary made only of %s', (_name, blank) => {
    expect(parseWorkflowResult({ status: 'GREEN', summary: blank })).toBeNull();
  });

  // Fix round 3: cleanText boundary-strips like Java's String#strip(), not JavaScript's own .trim()
  // — the two remove a different set of code points, which caused three concrete divergences from
  // the backend (all exercised below): .trim() silently removes a leading/trailing BOM before the
  // fatal check ever runs, and removes a leading NBSP/U+2007/U+202F before the length check runs
  // (letting an over-cap value through); conversely .trim() leaves U+001C-U+001F in place, where
  // Java's strip() (and this fix) removes them, so a value the backend accepts must not be rejected
  // here as "fatal".
  it.each([
    ['leading', '﻿Hello'],
    ['trailing', 'Hello﻿'],
  ])('rejects a %s BOM: a boundary strip must not remove a fatal character before the fatal check', (_pos, text) => {
    expect(parseWorkflowResult({ status: 'GREEN', title: text })).toBeNull();
  });

  it.each([
    ['non-breaking space', ' '],
    ['figure space', ' '],
    ['narrow no-break space', ' '],
  ])('rejects a title one character over the cap when a leading %s makes it 81 long — the boundary strip must not shrink it under the cap', (_name, ch) => {
    expect(parseWorkflowResult({ status: 'GREEN', title: ch + 't'.repeat(80) })).toBeNull();
  });

  it.each([
    ['leading', 'Hello', 'Hello'],
    ['trailing', 'Hello', 'Hello'],
  ])('accepts a %s U+001C at the boundary, stripped like Java whitespace rather than treated as fatal', (_pos, text, expected) => {
    expect(parseWorkflowResult({ status: 'GREEN', title: text })).toEqual({
      status: 'GREEN', title: expected, kpis: [], items: [],
    });
  });

  it('rejects a lone unpaired surrogate wherever it appears, in a contract field and in workflowName', () => {
    expect(parseWorkflowResult({ status: 'GREEN', items: ['a\ud800b'] })).toBeNull();
    expect(parseWorkflowEntry({
      workflowId: 'wf-a1', workflowName: 'Team\ud800QA', available: true,
      run: null, resultState: 'MISSING', result: null,
    })?.workflowName).toBeNull();
  });

  it('accepts a real astral character unchanged, in a contract field and in workflowName', () => {
    const partyPopper = '\u{1F389}'; // U+1F389, a full surrogate pair — not a lone one
    expect(parseWorkflowResult({ status: 'GREEN', items: [`Great ${partyPopper} work`] })).toEqual({
      status: 'GREEN', kpis: [], items: [`Great ${partyPopper} work`],
    });
    expect(parseWorkflowEntry({
      workflowId: 'wf-a1', workflowName: `Team ${partyPopper} QA`, available: true,
      run: null, resultState: 'MISSING', result: null,
    })?.workflowName).toBe(`Team ${partyPopper} QA`);
  });

  it('accepts an unassigned code point embedded in visible text unchanged, in a contract field and in workflowName', () => {
    const unassigned = '͸'; // individually legal here: "not visible", but not fatal either
    expect(parseWorkflowResult({ status: 'GREEN', items: [`code${unassigned}point`] })).toEqual({
      status: 'GREEN', kpis: [], items: [`code${unassigned}point`],
    });
    expect(parseWorkflowEntry({
      workflowId: 'wf-a1', workflowName: `Team${unassigned}QA`, available: true,
      run: null, resultState: 'MISSING', result: null,
    })?.workflowName).toBe(`Team${unassigned}QA`);
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
    // A non-breaking space is "not visible" but not fatal: legal next to visible text (fix round 2).
    expect(parseWorkflowEntry({ ...base, workflowName: 'Team QA' })?.workflowName).toBe('Team QA');
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
