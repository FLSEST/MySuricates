import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { screen, fireEvent, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { modulesFixture } from '../../../test/modules';
import { en } from '../../../i18n/en';
import { useI18nStore } from '../../../i18n';
import { WorkflowDetailHeader } from '../WorkflowDetailHeader';
import type { Workflow } from '../../../types';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => { server.resetHandlers(); vi.restoreAllMocks(); });
afterAll(() => server.close());
beforeEach(() => { localStorage.clear(); useI18nStore.setState({ locale: 'en' }); });

function workflow(overrides: Partial<Workflow> = {}): Workflow {
  return {
    id: 'wf-1', projectId: 'p1', projectName: 'P', name: 'Deploy', description: '', icon: '🚀', blocks: [],
    activatedSkillIds: [], activatedSkillNames: [], version: '1', runCount: 0, instructions: null, model: null,
    provider: null, effort: null, createdAt: '2026-09-14T10:00:00Z', updatedAt: '2026-09-14T10:00:00Z', ...overrides,
  };
}

function renderHeader(wf: Workflow, hasFailedRuns = false) {
  const noop = () => {};
  return renderWithProviders(
    <WorkflowDetailHeader workflow={wf} t={en} onNavigateBack={noop} onEdit={noop} onExecute={noop} onOptimize={noop}
      onRepair={noop} onClone={noop} onDelete={noop} executeLoading={false} optimizeLoading={false}
      repairLoading={false} hasFailedRuns={hasFailedRuns} />,
  );
}

describe('WorkflowDetailHeader sealing', () => {
  it('shows the seal and locks edit and delete for a sealed workflow', () => {
    renderHeader(workflow({ sealed: true, sealedHash: 'a'.repeat(64) }), true);

    expect(screen.getByTestId('sealed-badge')).toHaveTextContent('Sealed');
    expect(screen.getByRole('button', { name: en.common.edit })).toBeDisabled();
    expect(screen.getByRole('button', { name: en.common.delete })).toBeDisabled();
    // AI rewrites could not be saved anyway
    expect(screen.getByRole('button', { name: new RegExp(en.workflows.repair) })).toBeDisabled();
    expect(screen.getByRole('button', { name: new RegExp(en.workflows.optimize) })).toBeDisabled();
  });

  it('offers no seal toggle when the sealing module is off', async () => {
    server.use(http.get('/api/v1/system/capabilities', () => HttpResponse.json(modulesFixture('core', ['sealing']))));
    localStorage.setItem('mysuricates-modules', JSON.stringify(modulesFixture('core', ['sealing'])));

    renderHeader(workflow());

    expect(screen.queryByRole('button', { name: en.workflows.seal })).not.toBeInTheDocument();
  });

  it('seals after confirmation when the module is on', async () => {
    let sealedCalls = 0;
    server.use(http.post('/api/v1/workflows/wf-1/seal', () => {
      sealedCalls += 1;
      return HttpResponse.json(workflow({ sealed: true }));
    }));
    vi.spyOn(window, 'confirm').mockReturnValue(true);

    renderHeader(workflow());
    fireEvent.click(screen.getByRole('button', { name: en.workflows.seal }));

    await waitFor(() => expect(sealedCalls).toBe(1));
  });
});
