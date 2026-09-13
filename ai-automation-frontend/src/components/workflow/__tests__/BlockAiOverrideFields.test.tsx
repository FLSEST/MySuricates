import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { BlockAiOverrideFields } from '../BlockAiOverrideFields';
import { useI18nStore } from '../../../i18n';
import { useAiProviders, usePiCatalog, useProviderModels, useRefreshProviderModels } from '../../../hooks/useSystem';
import { AI_BLOCK_TYPES, BLOCK_TYPES, type BlockType } from '../../../types';

vi.mock('../../../hooks/useSystem', () => ({
  useAiProviders: vi.fn(),
  usePiCatalog: vi.fn(),
  useProviderModels: vi.fn(),
  useRefreshProviderModels: vi.fn(),
}));

function catalog(providerId: string, models: Array<{ value: string; label: string }>) {
  return { providerId, available: true, source: 'test', fetchedAt: null, reason: null, models };
}

describe('BlockAiOverrideFields', () => {
  beforeEach(() => {
    useI18nStore.setState({ locale: 'en' });
    vi.mocked(useAiProviders).mockReturnValue({
      data: { providers: ['CLAUDE', 'CODEX', 'PI'], default: 'CLAUDE' },
    } as any);
    vi.mocked(usePiCatalog).mockReturnValue({ data: undefined } as any);
    vi.mocked(useProviderModels).mockImplementation(((providerId: string) => ({
      data: catalog(providerId, [{ value: 'opus', label: 'Opus' }, { value: 'sonnet', label: 'Sonnet' }]),
      isLoading: false,
    })) as any);
    vi.mocked(useRefreshProviderModels).mockReturnValue({ mutate: vi.fn(), isPending: false } as any);
  });

  it('renders provider and model fields for every AI-capable block type', () => {
    for (const type of AI_BLOCK_TYPES) {
      const { unmount } = render(
        <BlockAiOverrideFields blockType={type} configJson="{}" onConfigChange={() => {}} />,
      );
      expect(screen.getByTestId('block-ai-override'), `fields for ${type}`).toBeInTheDocument();
      unmount();
    }
  });

  it('renders nothing for block types without an AI call', () => {
    const nonAi = BLOCK_TYPES.filter((t) => !AI_BLOCK_TYPES.includes(t));
    expect(nonAi).toContain('shell');
    expect(nonAi).toContain('integration');
    for (const type of nonAi) {
      const { container, unmount } = render(
        <BlockAiOverrideFields blockType={type as BlockType} configJson='{"provider":"google-calendar"}' onConfigChange={() => {}} />,
      );
      expect(container.firstChild, `no fields for ${type}`).toBeNull();
      unmount();
    }
  });

  it('writes the chosen provider into the config JSON and offers the workflow inheritance option', () => {
    const onConfigChange = vi.fn();
    render(
      <BlockAiOverrideFields
        blockType="simple"
        configJson={JSON.stringify({ command: 'summarize' })}
        onConfigChange={onConfigChange}
        workflowProvider="CLAUDE"
      />,
    );

    const select = screen.getByTestId('block-ai-provider') as HTMLSelectElement;
    expect(select.value).toBe('');
    expect(screen.getByText('Same as workflow')).toBeInTheDocument();

    fireEvent.change(select, { target: { value: 'PI' } });

    expect(onConfigChange).toHaveBeenCalledTimes(1);
    expect(JSON.parse(onConfigChange.mock.calls[0][0])).toEqual({ command: 'summarize', provider: 'PI' });
  });

  it('switching the provider drops a block model set for the previous provider', () => {
    const onConfigChange = vi.fn();
    render(
      <BlockAiOverrideFields
        blockType="agentic"
        configJson={JSON.stringify({ goal: 'x', provider: 'CLAUDE', model: 'opus' })}
        onConfigChange={onConfigChange}
      />,
    );

    fireEvent.change(screen.getByTestId('block-ai-provider'), { target: { value: 'CODEX' } });

    expect(JSON.parse(onConfigChange.mock.calls[0][0])).toEqual({ goal: 'x', provider: 'CODEX' });
  });

  it('commits a free-text model on blur and removes the key when cleared', () => {
    const onConfigChange = vi.fn();
    render(
      <BlockAiOverrideFields
        blockType="simple"
        configJson={JSON.stringify({ command: 'x', model: 'sonnet' })}
        onConfigChange={onConfigChange}
      />,
    );

    const input = screen.getByDisplayValue('sonnet') as HTMLInputElement;
    fireEvent.change(input, { target: { value: 'claude-opus-5' } });
    fireEvent.blur(input);
    expect(JSON.parse(onConfigChange.mock.calls[0][0])).toEqual({ command: 'x', model: 'claude-opus-5' });

    fireEvent.change(input, { target: { value: '' } });
    fireEvent.blur(input);
    expect(JSON.parse(onConfigChange.mock.calls[1][0])).toEqual({ command: 'x' });
  });

  it('uses the model catalog of the block provider, falling back to the workflow provider', () => {
    const { rerender } = render(
      <BlockAiOverrideFields blockType="simple" configJson='{"command":"x"}' onConfigChange={() => {}} workflowProvider="CODEX" />,
    );
    expect(vi.mocked(useProviderModels)).toHaveBeenLastCalledWith('CODEX');

    rerender(
      <BlockAiOverrideFields blockType="simple" configJson='{"command":"x","provider":"PI"}' onConfigChange={() => {}} workflowProvider="CODEX" />,
    );
    expect(vi.mocked(useProviderModels)).toHaveBeenLastCalledWith('PI');
  });

  it('shows a hint instead of the fields while the config JSON is invalid', () => {
    render(<BlockAiOverrideFields blockType="simple" configJson="{not json" onConfigChange={() => {}} />);

    expect(screen.getByTestId('block-ai-override')).toBeInTheDocument();
    expect(screen.queryByTestId('block-ai-provider')).toBeNull();
    expect(screen.getByText(/not valid JSON/)).toBeInTheDocument();
  });
});
