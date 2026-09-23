import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { BlockEditorModal } from '../BlockEditorModal';
import { useI18nStore } from '../../../i18n';
import type { Workflow } from '../../../types';

// The editor and the type-specific field groups have their own tests; here only the modal's
// own decisions matter (TODO 7473fdcb: add and edit block share one component).
vi.mock('../../shared/MonacoEditor', () => ({
  MonacoEditor: ({ value, revealText }: { value: string; revealText?: string }) => (
    <pre data-testid="config-editor" data-reveal={revealText ?? ''}>{value}</pre>
  ),
}));
vi.mock('../DashboardReportFields', () => ({ DashboardReportFields: () => null }));
vi.mock('../BlockAiOverrideFields', () => ({ BlockAiOverrideFields: () => null }));
vi.mock('../BlockSkillPicker', () => ({ BlockSkillPicker: () => null }));
vi.mock('../../../hooks/useBlockTypes', () => ({ useBlockTypes: () => ['shell', 'simple'] }));

const workflow = {
  id: 'wf-1',
  name: 'Nightly',
  blocks: [{ type: 'shell', config: { command: 'echo a' } }, { type: 'shell', config: { command: 'echo b' } }],
} as unknown as Workflow;

function renderModal(mode: 'add' | 'edit', setBlock = vi.fn(), extra: Partial<Parameters<typeof BlockEditorModal>[0]> = {}) {
  render(
    <BlockEditorModal
      mode={mode}
      open
      onClose={() => {}}
      workflow={workflow}
      workflowId="wf-1"
      position={mode === 'add' ? workflow.blocks.length : 1}
      block={{ type: 'shell', name: 'build', config: '{"command":"make"}', skillIds: [] }}
      setBlock={setBlock}
      onSave={() => {}}
      saving={false}
      allWorkflows={[]}
      allSkills={[]}
      {...extra}
    />,
  );
  return setBlock;
}

describe('BlockEditorModal', () => {
  beforeEach(() => {
    useI18nStore.setState({ locale: 'en' });
  });

  it('add: titles the dialog as a new block and starts a changed type from its defaults', () => {
    const setBlock = renderModal('add');

    expect(screen.getAllByText('Add Block').length).toBeGreaterThan(0);
    fireEvent.change(screen.getAllByRole('combobox')[0], { target: { value: 'simple' } });

    const next = setBlock.mock.calls[0][0];
    expect(next.type).toBe('simple');
    expect(next.config).not.toBe('{"command":"make"}');
    expect(JSON.parse(next.config)).toEqual(expect.any(Object));
  });

  it('edit: numbers the block from one and keeps its config when the type changes', () => {
    const setBlock = renderModal('edit', vi.fn(), { revealText: 'make' });

    expect(screen.getByText(/#2$/)).toBeInTheDocument();
    fireEvent.change(screen.getAllByRole('combobox')[0], { target: { value: 'simple' } });

    expect(setBlock.mock.calls[0][0]).toEqual(expect.objectContaining({ type: 'simple', config: '{"command":"make"}' }));
    expect(screen.getByTestId('config-editor')).toHaveAttribute('data-reveal', 'make');
  });
});
