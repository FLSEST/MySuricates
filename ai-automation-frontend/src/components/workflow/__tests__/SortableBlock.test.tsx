import { describe, it, expect, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import type { BlockDefinition, BlockType } from '../../../types';
import { SortableBlock } from '../SortableBlock';

beforeEach(() => useI18nStore.setState({ locale: 'en' }));

function renderBlock(block: BlockDefinition) {
  return renderWithProviders(
    <SortableBlock id="b1" index={0} block={block} onEdit={() => {}} onRemove={() => {}} />,
  );
}

describe('SortableBlock', () => {
  it('labels a known block type from the translations', () => {
    renderBlock({ type: 'shell', name: 'backend_tests', config: { command: 'mvn test' } });

    expect(screen.getByText('Shell Command')).toBeInTheDocument();
  });

  // Since 64966be0 the editor offers the block types the SERVER registers, so a type this build
  // has no label for can reach the block list — it must show its raw name, not an empty badge.
  it('falls back to the raw name for a block type this build does not know', () => {
    renderBlock({ type: 'quantum-block' as BlockType, name: 'spooky', config: {} });

    expect(screen.getByText('quantum-block')).toBeInTheDocument();
  });
});
