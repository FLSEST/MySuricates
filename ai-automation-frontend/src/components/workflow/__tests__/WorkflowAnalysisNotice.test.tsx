import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, waitFor, fireEvent } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { WorkflowAnalysisNotice } from '../WorkflowAnalysisNotice';
import type { Workflow } from '../../../types';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => { localStorage.clear(); useI18nStore.setState({ locale: 'en' }); });

const workflow = { id: 'wf-1', name: 'Deploy', updatedAt: '2026-09-26T10:00:00Z', blocks: [] } as unknown as Workflow;

const findings = [
  { rule: 'missing-required-config-key', severity: 'HIGH', blockIndex: 1, blockName: 'agent', detail: 'Pflichtschlüssel goal fehlt' },
  { rule: 'unknown-config-key', severity: 'MEDIUM', blockIndex: 1, blockName: 'agent', detail: 'unbekannter Config-Schlüssel command' },
  { rule: 'missing-strict-mode', severity: 'LOW', blockIndex: 0, blockName: null, detail: 'ohne set -euo pipefail' },
];

describe('WorkflowAnalysisNotice', () => {
  it('shows the counts per severity and lists the findings on demand', async () => {
    server.use(http.get('/api/v1/workflows/wf-1/analyze', () => HttpResponse.json({
      workflowId: 'wf-1', workflowName: 'Deploy', findingCount: 3, bySeverity: { HIGH: 1, MEDIUM: 1, LOW: 1 }, findings,
    })));

    renderWithProviders(<WorkflowAnalysisNotice workflow={workflow} />);

    const notice = await screen.findByTestId('workflow-analysis');
    // LOW is hygiene: listed, but not counted in the header
    expect(notice).toHaveTextContent('2 finding(s) of the build check');
    expect(screen.getByTestId('analysis-count-HIGH')).toHaveTextContent('1 severe');
    expect(screen.getByTestId('analysis-count-MEDIUM')).toHaveTextContent('1 medium');
    expect(screen.queryByTestId('analysis-count-LOW')).not.toBeInTheDocument();
    expect(screen.queryByText('Pflichtschlüssel goal fehlt')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Show findings' }));

    expect(screen.getByText('Pflichtschlüssel goal fehlt')).toBeInTheDocument();
    expect(screen.getByText('missing-required-config-key')).toBeInTheDocument();
    expect(screen.getAllByText('Block 2 · agent')).toHaveLength(2);
    expect(screen.getByText('Block 1')).toBeInTheDocument();
    expect(screen.getByText('ohne set -euo pipefail')).toBeInTheDocument();
    expect(screen.getByText('minor')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Hide findings' })).toHaveAttribute('aria-expanded', 'true');
  });

  // TODO 81773d7b: the new form carries detailEn — the English UI shows it, the German one keeps detail
  it.each([
    ['en', 'required key goal is missing', 'Pflichtschlüssel goal fehlt', 'Show findings'],
    ['de', 'Pflichtschlüssel goal fehlt', 'required key goal is missing', 'Befunde anzeigen'],
  ] as const)('shows the finding text in the UI language (%s)', async (locale, shown, hidden, button) => {
    useI18nStore.setState({ locale });
    server.use(http.get('/api/v1/workflows/wf-1/analyze', () => HttpResponse.json({
      workflowId: 'wf-1', workflowName: 'Deploy', findingCount: 1, bySeverity: { HIGH: 1 },
      findings: [{ ...findings[0], detailEn: 'required key goal is missing' }],
    })));

    renderWithProviders(<WorkflowAnalysisNotice workflow={workflow} />);
    await screen.findByTestId('workflow-analysis');
    fireEvent.click(screen.getByRole('button', { name: button }));

    expect(screen.getByText(shown)).toBeInTheDocument();
    expect(screen.queryByText(hidden)).not.toBeInTheDocument();
  });

  it('counts from the findings when the response has no bySeverity', async () => {
    server.use(http.get('/api/v1/workflows/wf-1/analyze', () => HttpResponse.json({
      workflowId: 'wf-1', workflowName: 'Deploy', findingCount: 1, findings: [findings[0]],
    })));

    renderWithProviders(<WorkflowAnalysisNotice workflow={workflow} />);

    expect(await screen.findByTestId('analysis-count-HIGH')).toHaveTextContent('1 severe');
    expect(screen.queryByTestId('analysis-count-MEDIUM')).not.toBeInTheDocument();
  });

  it('stays hidden when the definition is clean', async () => {
    let asked = false;
    server.use(http.get('/api/v1/workflows/wf-1/analyze', () => {
      asked = true;
      return HttpResponse.json({ workflowId: 'wf-1', workflowName: 'Deploy', findingCount: 0, bySeverity: {}, findings: [] });
    }));

    renderWithProviders(<WorkflowAnalysisNotice workflow={workflow} />);

    await waitFor(() => expect(asked).toBe(true));
    expect(screen.queryByTestId('workflow-analysis')).not.toBeInTheDocument();
  });

  it('stays hidden when there are only LOW (hygiene) findings', async () => {
    let asked = false;
    server.use(http.get('/api/v1/workflows/wf-1/analyze', () => {
      asked = true;
      return HttpResponse.json({ workflowId: 'wf-1', workflowName: 'Deploy', findingCount: 1, bySeverity: { LOW: 1 }, findings: [findings[2]] });
    }));

    renderWithProviders(<WorkflowAnalysisNotice workflow={workflow} />);

    await waitFor(() => expect(asked).toBe(true));
    expect(screen.queryByTestId('workflow-analysis')).not.toBeInTheDocument();
  });

  it('stays hidden against an older backend without the endpoint', async () => {
    let asked = false;
    server.use(http.get('/api/v1/workflows/wf-1/analyze', () => {
      asked = true;
      return new HttpResponse(null, { status: 404 });
    }));

    renderWithProviders(<WorkflowAnalysisNotice workflow={workflow} />);

    await waitFor(() => expect(asked).toBe(true));
    expect(screen.queryByTestId('workflow-analysis')).not.toBeInTheDocument();
  });

  it('speaks German with the German locale', async () => {
    useI18nStore.setState({ locale: 'de' });
    server.use(http.get('/api/v1/workflows/wf-1/analyze', () => HttpResponse.json({
      workflowId: 'wf-1', workflowName: 'Deploy', findingCount: 1, bySeverity: { HIGH: 1 }, findings: [findings[0]],
    })));

    renderWithProviders(<WorkflowAnalysisNotice workflow={workflow} />);

    expect(await screen.findByTestId('workflow-analysis')).toHaveTextContent('1 Befund(e) der Baufehler-Prüfung');
    expect(screen.getByRole('button', { name: 'Befunde anzeigen' })).toBeInTheDocument();
  });
});
