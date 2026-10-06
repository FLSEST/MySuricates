import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { server } from '../../../test/server';
import { failOnUnhandledRequest } from '../../../test/msw';
import { screen, fireEvent } from '@testing-library/react';
import { renderWithProviders } from '../../../test/utils';
import { en } from '../../../i18n/en';
import { useI18nStore } from '../../../i18n';
import type { Workflow } from '../../../types';

const moduleState = { bpmn: true };
const fetchBpmn = vi.fn();

vi.mock('../../../hooks/useModules', () => ({
  useModuleEnabled: (name: string) => (name === 'bpmn' ? moduleState.bpmn : true),
}));
vi.mock('../../../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/client')>()),
  fetchWorkflowBpmn: (id: string) => fetchBpmn(id),
}));
// Stand-in for the diagram: shows the requested flash and reports clicks like the real one
vi.mock('../BpmnDiagramView', () => ({
  default: ({ flashBlock, onSelectBlock }: {
    flashBlock?: { index: number } | null;
    onSelectBlock?: (sel: { blockIndex: number }) => void;
  }) => (
    <div data-testid="bpmn-view" data-flash={flashBlock?.index ?? ''}>
      <button type="button" onClick={() => onSelectBlock?.({ blockIndex: 1 })}>diagram box 1</button>
    </div>
  ),
}));

import { WorkflowDetailPanel } from '../WorkflowDetailPanel';

// Side queries of the component (seal status, runs, suggestions …) get the shared default handlers, never the network.
beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

beforeEach(() => {
  moduleState.bpmn = true;
  fetchBpmn.mockReset().mockResolvedValue('<bpmn/>');
  useI18nStore.setState({ locale: 'en' });
});

function workflow(): Workflow {
  return {
    id: 'wf-1', projectId: 'p1', projectName: 'P', name: 'Deploy', description: '', icon: '🚀',
    blocks: [
      { type: 'shell', config: { command: 'git status' } },
      { type: 'simple', config: { prompt: 'summarize' } },
    ],
    activatedSkillIds: [], activatedSkillNames: [], version: '1', runCount: 0, instructions: null, model: null,
    provider: null, effort: null, createdAt: '2026-09-14T10:00:00Z', updatedAt: '2026-09-14T10:00:00Z',
  } as Workflow;
}

function renderPanel() {
  const noop = () => {};
  return renderWithProviders(
    <WorkflowDetailPanel workflow={workflow()} t={en} onExecute={noop} onEdit={noop} onSaveAsTemplate={noop}
      saveAsTemplateLoading={false} navigate={vi.fn()} />,
  );
}

describe('WorkflowDetailPanel blocks and diagram', () => {
  it('shows the block list and the diagram below each other', async () => {
    renderPanel();
    expect(screen.getByText('git status')).toBeInTheDocument();
    expect(await screen.findByTestId('bpmn-view')).toBeInTheDocument();
    expect(fetchBpmn).toHaveBeenCalledWith('wf-1');
  });

  it('flashes the diagram box when a block in the list is clicked', async () => {
    renderPanel();
    const view = await screen.findByTestId('bpmn-view');
    expect(view.dataset.flash).toBe('');
    fireEvent.click(screen.getByText('summarize'));
    expect(view.dataset.flash).toBe('1');
  });

  it('flashes the list entry when a diagram box is clicked', async () => {
    renderPanel();
    fireEvent.click(await screen.findByText('diagram box 1'));
    const flash = screen.getByTestId('block-list-flash');
    expect(flash.closest('button')).toHaveTextContent('summarize');
    expect(flash.className).toContain('block-flash');
  });

  it('shows only the list when the bpmn module is off', () => {
    moduleState.bpmn = false;
    renderPanel();
    expect(screen.getByText('git status')).toBeInTheDocument();
    expect(screen.queryByTestId('bpmn-view')).not.toBeInTheDocument();
    expect(fetchBpmn).not.toHaveBeenCalled();
  });
});
