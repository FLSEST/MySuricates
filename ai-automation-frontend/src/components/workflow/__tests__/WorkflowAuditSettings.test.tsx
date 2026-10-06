import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { modulesFixture } from '../../../test/modules';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { en } from '../../../i18n/en';
import type { Workflow, WorkflowMetadata } from '../../../types';

const mutate = vi.fn();
vi.mock('../../../hooks/useWorkflows', () => ({
  useUpdateWorkflow: () => ({ mutate, isPending: false }),
}));

import { WorkflowAutomationSettings } from '../WorkflowAutomationSettings';
import { WorkflowRow } from '../WorkflowRow';
import { AuditLabel } from '../AuditLabel';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  mutate.mockClear();
  useI18nStore.setState({ locale: 'en' });
});

const AUDIT = { enabled: true, key: 'mysuricates.architecture', category: 'architecture', reportBlock: 'report' };

function workflow(metadata?: WorkflowMetadata): Workflow {
  return {
    id: 'wf-1', projectId: 'p1', projectName: 'P', name: 'Architecture Review', description: '', icon: '🏛️', blocks: [],
    activatedSkillIds: [], activatedSkillNames: [], version: '1', runCount: 0, instructions: null, model: null,
    provider: null, effort: null, createdAt: '2026-10-06T10:00:00Z', updatedAt: '2026-10-06T10:00:00Z', metadata,
  };
}

const threshold = () => screen.getByLabelText(en.workflows.auditTodoThreshold) as HTMLSelectElement;

// d8607f77: an audit workflow shows that it is one, and sets its own TODO threshold
describe('WorkflowAutomationSettings for an audit workflow', () => {
  it('shows the audit part instead of the Auto-Todo switch, which the backend ignores for audits', () => {
    renderWithProviders(<WorkflowAutomationSettings workflow={workflow({ audit: AUDIT, autoTodo: { enabled: true } })} />);

    expect(screen.getByTestId('workflow-audit-settings')).toHaveTextContent('mysuricates.architecture');
    expect(screen.queryByRole('switch', { name: 'Auto-Todo' })).not.toBeInTheDocument();
    expect(screen.getAllByRole('switch').map((s) => s.getAttribute('aria-label'))).toEqual(['Auto-Repair', 'Auto-Memory']);
    expect(threshold().value).toBe('HIGH');
    expect(screen.getByRole('link', { name: en.workflows.auditOpenInCenter }))
      .toHaveAttribute('href', '/reports?audit=mysuricates.architecture&project=p1');
  });

  it('saves a new threshold into metadata.audit and keeps the rest of the audit block', () => {
    renderWithProviders(<WorkflowAutomationSettings workflow={workflow({ audit: { ...AUDIT, promptBlock: 'coach' } })} />);

    fireEvent.change(threshold(), { target: { value: 'MEDIUM' } });

    expect(mutate).toHaveBeenCalledWith({ id: 'wf-1', data: { metadata: { audit: { ...AUDIT, promptBlock: 'coach', todoMinSeverity: 'MEDIUM' } } } });
  });

  it('reads the threshold like the backend: any case, an unknown value falls back to High and is named', () => {
    const { unmount } = renderWithProviders(<WorkflowAutomationSettings workflow={workflow({ audit: { ...AUDIT, todoMinSeverity: 'none' } })} />);
    expect(threshold().value).toBe('NONE');
    expect(screen.queryByTestId('audit-threshold-unknown')).not.toBeInTheDocument();
    unmount();

    renderWithProviders(<WorkflowAutomationSettings workflow={workflow({ audit: { ...AUDIT, todoMinSeverity: 'sometimes' } })} />);
    expect(threshold().value).toBe('HIGH');
    expect(screen.getByTestId('audit-threshold-unknown')).toHaveTextContent('Unknown value "sometimes"');
  });

  it('treats an incomplete or switched-off audit block like any other workflow', () => {
    renderWithProviders(<WorkflowAutomationSettings workflow={workflow({ audit: { ...AUDIT, enabled: false } })} />);

    expect(screen.queryByTestId('workflow-audit-settings')).not.toBeInTheDocument();
    expect(screen.getByRole('switch', { name: 'Auto-Todo' })).toBeInTheDocument();
  });

  it('leaves the link out while the audit module is off', async () => {
    server.use(http.get('/api/v1/system/capabilities', () =>
      HttpResponse.json({ apiVersion: 'v1', routes: [], blockTypes: [], ...modulesFixture('full', ['audit']) })));
    renderWithProviders(<WorkflowAutomationSettings workflow={workflow({ audit: AUDIT })} />);

    await vi.waitFor(() => expect(screen.queryByRole('link', { name: en.workflows.auditOpenInCenter })).not.toBeInTheDocument());
    expect(threshold()).toBeInTheDocument();
  });
});

describe('WorkflowRow of an audit workflow', () => {
  const row = (wf: Workflow) => renderWithProviders(
    <WorkflowRow workflow={wf} selected={false} onSelect={vi.fn()} onExecute={vi.fn()} onDelete={vi.fn()}
      navigate={vi.fn()} selectMode={false} isChecked={false} onToggleCheck={vi.fn()} />);

  it('carries an audit mark naming its key instead of the Auto-Todo bell', () => {
    row(workflow({ audit: AUDIT, autoTodo: { enabled: true } }));

    expect(screen.getByTestId('row-audit')).toHaveAttribute('title', 'Audit workflow: mysuricates.architecture');
    // f529190a: a word, not only a symbol – the bare 12px shield went unnoticed
    expect(screen.getByTestId('row-audit')).toHaveTextContent(/^Audit/);
  });

  it('keeps only the shield in a selected row, whose actions need the room', () => {
    renderWithProviders(
      <WorkflowRow workflow={workflow({ audit: AUDIT })} selected onSelect={vi.fn()} onExecute={vi.fn()} onDelete={vi.fn()}
        navigate={vi.fn()} selectMode={false} isChecked={false} onToggleCheck={vi.fn()} />);

    expect(screen.getByTestId('row-audit').querySelector('span.hidden')).toHaveTextContent('Audit');
  });

  it('has no audit mark otherwise', () => {
    row(workflow({ autoTodo: { enabled: true } }));

    expect(screen.queryByTestId('row-audit')).not.toBeInTheDocument();
  });
});

// f529190a: the head of detail page and panel says plainly that this is an audit
describe('AuditLabel', () => {
  it('names the audit, its TODO threshold and links to its findings', () => {
    renderWithProviders(<AuditLabel workflow={workflow({ audit: AUDIT })} />);

    const label = screen.getByTestId('audit-label');
    expect(label).toHaveTextContent('Audit·mysuricates.architecture·TODOs from High');
    expect(label).toHaveAttribute('href', '/reports?audit=mysuricates.architecture&project=p1');
  });

  it('follows the threshold the backend applies, also Never and an unknown value', () => {
    const { unmount } = renderWithProviders(<AuditLabel workflow={workflow({ audit: { ...AUDIT, todoMinSeverity: 'medium' } })} />);
    expect(screen.getByTestId('audit-label')).toHaveTextContent('TODOs from Medium');
    unmount();

    const second = renderWithProviders(<AuditLabel workflow={workflow({ audit: { ...AUDIT, todoMinSeverity: 'NONE' } })} />);
    expect(screen.getByTestId('audit-label')).toHaveTextContent('TODOs only by button');
    second.unmount();

    renderWithProviders(<AuditLabel workflow={workflow({ audit: { ...AUDIT, todoMinSeverity: 'sometimes' } })} />);
    expect(screen.getByTestId('audit-label')).toHaveTextContent('TODOs from High');
  });

  it('checks the schema version like AuditConfig.from: missing or 1 is an audit, any other number is not', () => {
    const { unmount } = renderWithProviders(<AuditLabel workflow={workflow({ audit: { ...AUDIT, schemaVersion: 1 } })} />);
    expect(screen.getByTestId('audit-label')).toBeInTheDocument();
    unmount();

    renderWithProviders(<AuditLabel workflow={workflow({ audit: { ...AUDIT, schemaVersion: 2 } })} />);
    expect(screen.queryByTestId('audit-label')).not.toBeInTheDocument();
  });

  it('renders nothing for a workflow that is no audit', () => {
    const { container } = renderWithProviders(<AuditLabel workflow={workflow({ audit: { ...AUDIT, enabled: false } })} />);

    expect(screen.queryByTestId('audit-label')).not.toBeInTheDocument();
    expect(container).toBeEmptyDOMElement();
  });

  it('is plain text without a link while the audit module is off', async () => {
    server.use(http.get('/api/v1/system/capabilities', () =>
      HttpResponse.json({ apiVersion: 'v1', routes: [], blockTypes: [], ...modulesFixture('full', ['audit']) })));
    renderWithProviders(<AuditLabel workflow={workflow({ audit: AUDIT })} />);

    await vi.waitFor(() => expect(screen.getByTestId('audit-label').tagName).toBe('SPAN'));
    expect(screen.getByTestId('audit-label')).toHaveTextContent('mysuricates.architecture');
  });
});
