import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { server } from '../../../test/server';
import { failOnUnhandledRequest } from '../../../test/msw';
import { screen, fireEvent, waitFor } from '@testing-library/react';
import { renderWithProviders } from '../../../test/utils';
import { en } from '../../../i18n/en';
import { useI18nStore } from '../../../i18n';
import type { Workflow } from '../../../types';

const toastError = vi.fn();
const fetchBpmn = vi.fn();

vi.mock('../../../hooks/useToast', () => ({ useToast: () => ({ success: vi.fn(), error: toastError }) }));
vi.mock('../../../hooks/useModules', () => ({ useModuleEnabled: () => true }));
vi.mock('../../../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/client')>()),
  fetchWorkflowBpmn: (id: string) => fetchBpmn(id),
}));

import { WorkflowDetailHeader } from '../WorkflowDetailHeader';

// Side queries of the component (seal status, runs, suggestions …) get the shared default handlers, never the network.
beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

beforeEach(() => {
  toastError.mockReset();
  fetchBpmn.mockReset();
  useI18nStore.setState({ locale: 'en' });
});

function workflow(): Workflow {
  return {
    id: 'wf-1', projectId: 'p1', projectName: 'P', name: 'Deploy', description: '', icon: '🚀', blocks: [],
    activatedSkillIds: [], activatedSkillNames: [], version: '1', runCount: 0, instructions: null, model: null,
    provider: null, effort: null, createdAt: '2026-09-14T10:00:00Z', updatedAt: '2026-09-14T10:00:00Z',
  };
}

function renderHeader() {
  const noop = () => {};
  return renderWithProviders(
    <WorkflowDetailHeader workflow={workflow()} t={en} onNavigateBack={noop} onEdit={noop} onExecute={noop}
      onOptimize={noop} onRepair={noop} onClone={noop} onDelete={noop} executeLoading={false}
      optimizeLoading={false} repairLoading={false} hasFailedRuns={false} />,
  );
}

describe('WorkflowDetailHeader BPMN export', () => {
  it('shows the server reason in the toast when the export fails (e00123d4)', async () => {
    fetchBpmn.mockRejectedValue({ code: 'INVALID_ARGUMENT', error: 'Workflow has no blocks' });

    renderHeader();
    fireEvent.click(screen.getByRole('button', { name: en.workflows.exportBpmn }));

    await waitFor(() => expect(toastError).toHaveBeenCalled());
    expect(toastError.mock.calls[0][0]).toBe(`${en.workflows.bpmnExportFailed}: Workflow has no blocks`);
  });

  it('falls back to the generic message when the error carries none', async () => {
    fetchBpmn.mockRejectedValue(new Error('network down'));

    renderHeader();
    fireEvent.click(screen.getByRole('button', { name: en.workflows.exportBpmn }));

    await waitFor(() => expect(toastError).toHaveBeenCalled());
    expect(toastError.mock.calls[0][0]).toBe(en.workflows.bpmnExportFailed);
  });
});
