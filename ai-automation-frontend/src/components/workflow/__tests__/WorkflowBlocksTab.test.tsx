import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { useI18nStore } from '../../../i18n';
import type { Workflow } from '../../../types';

const moduleState = { bpmn: true };
vi.mock('../../../hooks/useModules', () => ({
  useModuleEnabled: (name: string) => (name === 'bpmn' ? moduleState.bpmn : true),
}));
// Stand-ins: the list reports hover and edit, the diagram shows the requested flash. The list
// also keeps the edit handler it got, so a test can call it where a click could not reach it.
const listProps: { onEditBlock?: (i: number) => void } = {};
vi.mock('../SortableBlockList', () => ({
  SortableBlockList: ({ onEditBlock, onHoverBlock, flashBlock }: {
    onEditBlock: (i: number) => void; onHoverBlock?: (i: number) => void; flashBlock?: { index: number } | null;
  }) => {
    listProps.onEditBlock = onEditBlock;
    return (
      <div data-testid="block-list" data-flash={flashBlock?.index ?? ''}>
        <button type="button" onClick={() => onEditBlock(1)}>edit block 1</button>
        <button type="button" onMouseEnter={() => onHoverBlock?.(0)}>hover block 0</button>
      </div>
    );
  },
}));
vi.mock('../WorkflowDiagramTab', () => ({
  default: ({ flashBlock, onHoverBlock }: { flashBlock?: { index: number } | null; onHoverBlock: (i: number) => void }) => (
    <div data-testid="diagram" data-flash={flashBlock?.index ?? ''}>
      <button type="button" onMouseEnter={() => onHoverBlock(1)}>hover box 1</button>
    </div>
  ),
}));

import { WorkflowBlocksTab } from '../WorkflowBlocksTab';

beforeEach(() => {
  moduleState.bpmn = true;
  listProps.onEditBlock = undefined;
  useI18nStore.setState({ locale: 'en' });
});

function workflow(overrides: Partial<Workflow> = {}): Workflow {
  return {
    id: 'wf-1', name: 'Deploy', blocks: [{ type: 'shell', config: { command: 'a' } }, { type: 'shell', config: { command: 'b' } }],
    ...overrides,
  } as Workflow;
}

function renderTab(wf: Workflow, props: Partial<Parameters<typeof WorkflowBlocksTab>[0]> = {}) {
  const handlers = { onAddBlock: vi.fn(), onEditBlock: vi.fn(), onRemoveBlock: vi.fn(), onSelectFromDiagram: vi.fn() };
  render(<WorkflowBlocksTab workflow={wf} {...handlers} {...props} />);
  return handlers;
}

describe('WorkflowBlocksTab', () => {
  it('lists the blocks, shows the diagram below and flashes across on hover', async () => {
    const diagramRef = vi.fn();
    const handlers = renderTab(workflow(), { diagramRef });

    fireEvent.click(screen.getByRole('button', { name: /Add Block/ }));
    expect(handlers.onAddBlock).toHaveBeenCalled();
    fireEvent.click(screen.getByText('edit block 1'));
    expect(handlers.onEditBlock).toHaveBeenCalledWith(1);

    const diagram = await screen.findByTestId('diagram');
    expect(diagramRef).toHaveBeenCalledWith(screen.getByRole('region', { name: 'Diagram' }));
    fireEvent.mouseEnter(screen.getByText('hover block 0'));
    expect(diagram.dataset.flash).toBe('0');
    fireEvent.mouseEnter(screen.getByText('hover box 1'));
    expect(screen.getByTestId('block-list').dataset.flash).toBe('1');
  });

  it('locks a sealed definition', () => {
    const handlers = renderTab(workflow({ sealed: true }));
    expect(screen.getByRole('button', { name: /Add Block/ })).toBeDisabled();
    expect(screen.getByTestId('block-list').closest('fieldset')).toBeDisabled();
    // The locked fieldset swallows every click, so call what the list got: a handler that does nothing
    listProps.onEditBlock!(1);
    expect(handlers.onEditBlock).not.toHaveBeenCalled();
  });

  it('shows no diagram without the bpmn module or without blocks', () => {
    moduleState.bpmn = false;
    renderTab(workflow());
    expect(screen.queryByTestId('diagram')).not.toBeInTheDocument();
    expect(screen.getByText('hover block 0')).toBeInTheDocument();

    moduleState.bpmn = true;
    renderTab(workflow({ blocks: [] }));
    expect(screen.getByText('No blocks yet. Add your first block to get started.')).toBeInTheDocument();
    expect(screen.queryByTestId('diagram')).not.toBeInTheDocument();
  });
});
