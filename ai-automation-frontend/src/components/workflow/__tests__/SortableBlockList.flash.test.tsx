import { describe, it, expect, vi } from 'vitest';
import { screen, fireEvent } from '@testing-library/react';
import { renderWithProviders } from '../../../test/utils';
import { SortableBlockList } from '../SortableBlockList';
import type { BlockDefinition } from '../../../types';

const blocks: BlockDefinition[] = [
  { type: 'shell', config: { command: 'git status' } },
  { type: 'shell', config: { command: 'mvn test' } },
];

function renderList(props: Partial<Parameters<typeof SortableBlockList>[0]> = {}) {
  return renderWithProviders(
    <SortableBlockList workflowId="wf-1" blocks={blocks} onEditBlock={vi.fn()} onRemoveBlock={vi.fn()} {...props} />,
  );
}

describe('SortableBlockList cross-highlight', () => {
  it('reports the hovered block', () => {
    const onHoverBlock = vi.fn();
    renderList({ onHoverBlock });
    fireEvent.mouseEnter(screen.getByText('mvn test').closest('.group')!);
    expect(onHoverBlock).toHaveBeenCalledWith(1);
  });

  it('flashes only the requested block', () => {
    renderList({ flashBlock: { index: 1, nonce: 1 } });
    const flashes = screen.getAllByTestId('block-list-flash');
    expect(flashes).toHaveLength(1);
    expect(flashes[0]!.closest('.group')).toHaveTextContent('mvn test');
  });
});
