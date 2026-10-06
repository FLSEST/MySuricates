import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { WorkflowDetailSidebar } from '../WorkflowDetailSidebar';
import { useI18nStore } from '../../../i18n';
import { useUpdateWorkflow, useWorkflowRuns } from '../../../hooks/useWorkflows';
import { useAiProviders, useExecutionConfig, usePiCatalog, useProviderModels, useRefreshProviderModels } from '../../../hooks/useSystem';
import type { Workflow } from '../../../types';

const mockMutate = vi.fn();

vi.mock('../../../hooks/useWorkflows', () => ({
  useUpdateWorkflow: vi.fn(),
  useWorkflowRuns: vi.fn(),
}));

vi.mock('../../../hooks/useSystem', () => ({
  useAiProviders: vi.fn(),
  usePiCatalog: vi.fn(),
  useProviderModels: vi.fn(),
  useRefreshProviderModels: vi.fn(),
  useExecutionConfig: vi.fn(),
}));

const mockRefresh = vi.fn();

function catalog(providerId: string, models: Array<{ value: string; label: string }>) {
  return { providerId, available: true, source: 'test', fetchedAt: null, reason: null, models };
}

vi.mock('../WorkflowMemoryPanel', () => ({
  WorkflowMemoryPanel: () => null,
}));

// Rendered without a QueryClient: every optional module counts as enabled here.
vi.mock('../../../hooks/useModules', () => ({
  useModuleEnabled: () => true,
}));

function buildWorkflow(overrides: Partial<Workflow> = {}): Workflow {
  return {
    id: 'wf-1',
    projectId: 'proj-1',
    projectName: 'Test Project',
    name: 'Test Workflow',
    description: 'A test workflow',
    icon: '⚡',
    blocks: [],
    version: 'v3',
    runCount: 0,
    activatedSkillIds: [],
    activatedSkillNames: [],
    instructions: null,
    model: null,
    provider: null,
    createdAt: '2024-01-15T10:00:00Z',
    updatedAt: '2024-01-15T10:00:00Z',
    ...overrides,
  };
}

const defaultProps = {
  workflowVariables: [] as string[],
  onOpenEditInstructions: vi.fn(),
  onOpenAddSkill: vi.fn(),
  onDeactivateSkill: vi.fn(),
  onNavigateToRun: vi.fn(),
};

describe('WorkflowDetailSidebar', () => {
  beforeEach(() => {
    useI18nStore.setState({ locale: 'en' });
    mockMutate.mockClear();
    vi.mocked(useUpdateWorkflow).mockReturnValue({ mutate: mockMutate, isPending: false } as any);
    vi.mocked(useWorkflowRuns).mockReturnValue({
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 50 },
      isLoading: false,
    } as any);
    vi.mocked(useExecutionConfig).mockReturnValue({ data: { liveLogPersistDefault: 1 } } as any);
    vi.mocked(useAiProviders).mockReturnValue({
      data: { providers: ['CLAUDE', 'CODEX', 'PI'], default: 'CLAUDE' },
    } as any);
    vi.mocked(usePiCatalog).mockReturnValue({
      data: {
        installed: true,
        authenticatedProviders: [],
        customProviders: [],
        defaultProvider: null,
        defaultModel: null,
      },
    } as any);
    mockRefresh.mockClear();
    vi.mocked(useRefreshProviderModels).mockReturnValue({ mutate: mockRefresh, isPending: false } as any);
    vi.mocked(useProviderModels).mockImplementation(((providerId: string) => ({
      data:
        providerId === 'PI'
          ? catalog('PI', [{ value: 'openai-codex/gpt-5.5', label: 'openai-codex · gpt-5.5' }])
          : catalog(providerId, [{ value: 'opus', label: 'opus (latest Opus)' }, { value: 'sonnet', label: 'sonnet (latest Sonnet)' }]),
      isLoading: false,
    })) as any);
  });

  it('renders without crashing given a minimal workflow', () => {
    const { container } = render(
      <WorkflowDetailSidebar workflow={buildWorkflow()} {...defaultProps} />
    );
    expect(container.firstChild).not.toBeNull();
  });

  it('shows project name and version in the info card', () => {
    render(<WorkflowDetailSidebar workflow={buildWorkflow()} {...defaultProps} />);
    expect(screen.getByText('Test Project')).toBeInTheDocument();
    expect(screen.getByText('v3')).toBeInTheDocument();
  });

  it('fires mutate with model: "" when provider select changes', () => {
    render(<WorkflowDetailSidebar workflow={buildWorkflow()} {...defaultProps} />);
    // First combobox in DOM is the provider select (model select comes after)
    const providerSelect = screen.getAllByRole('combobox')[0];
    fireEvent.change(providerSelect, { target: { value: 'CODEX' } });
    expect(mockMutate).toHaveBeenCalledWith(
      expect.objectContaining({
        id: 'wf-1',
        data: expect.objectContaining({ model: '' }),
      })
    );
  });

  it('renders model as free-text input with datalist when provider is PI', () => {
    render(
      <WorkflowDetailSidebar workflow={buildWorkflow({ provider: 'PI' })} {...defaultProps} />
    );
    // datalist for pi suggestions is rendered with the host-discovered models
    const list = document.getElementById('pi-model-suggestions');
    expect(list).not.toBeNull();
    expect(list!.querySelectorAll('option')).toHaveLength(1);
    expect(list!.querySelector('option')!.getAttribute('value')).toBe('openai-codex/gpt-5.5');
    // placeholder text confirms it is a free-text input, not a select
    expect(screen.getByPlaceholderText(/<backend>\/<model-id>/)).toBeInTheDocument();
  });

  it('renders the model field as free-text input with a catalog datalist for CLAUDE too', () => {
    render(<WorkflowDetailSidebar workflow={buildWorkflow({ provider: 'CLAUDE' })} {...defaultProps} />);
    const list = document.getElementById('claude-model-suggestions');
    expect(list).not.toBeNull();
    expect(Array.from(list!.querySelectorAll('option')).map((o) => o.getAttribute('value'))).toEqual(['opus', 'sonnet']);
    expect(useProviderModels).toHaveBeenCalledWith('CLAUDE');
  });

  it('commits a typed model on blur and shows a hint when it is not in the catalog', () => {
    render(<WorkflowDetailSidebar workflow={buildWorkflow({ provider: 'CLAUDE', model: 'claude-opus-5' })} {...defaultProps} />);
    expect(screen.getByText(/Not in the suggestion list/)).toBeInTheDocument();

    const input = screen.getByPlaceholderText(/Empty = provider default/);
    fireEvent.change(input, { target: { value: 'claude-sonnet-5' } });
    fireEvent.blur(input);
    expect(mockMutate).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'wf-1', data: { model: 'claude-sonnet-5' } })
    );
  });

  it('commits immediately when a catalog entry is picked', () => {
    render(<WorkflowDetailSidebar workflow={buildWorkflow({ provider: 'CLAUDE' })} {...defaultProps} />);
    const input = screen.getByPlaceholderText(/Empty = provider default/);
    fireEvent.change(input, { target: { value: 'sonnet' } });
    expect(mockMutate).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'wf-1', data: { model: 'sonnet' } })
    );
  });

  it('shows the catalog reason and no suggestions when the catalog is unavailable', () => {
    vi.mocked(useProviderModels).mockReturnValue({
      data: { providerId: 'CODEX', available: false, source: 'codex-cli', fetchedAt: null, reason: 'codex not installed', models: [] },
      isLoading: false,
    } as any);
    render(<WorkflowDetailSidebar workflow={buildWorkflow({ provider: 'CODEX' })} {...defaultProps} />);
    expect(screen.getByText(/codex not installed/)).toBeInTheDocument();
    expect(document.getElementById('codex-model-suggestions')!.querySelectorAll('option')).toHaveLength(0);
  });

  it('refresh button triggers the catalog refresh mutation', () => {
    render(<WorkflowDetailSidebar workflow={buildWorkflow({ provider: 'CLAUDE' })} {...defaultProps} />);
    fireEvent.click(screen.getByRole('button', { name: /Reload model catalog/ }));
    expect(mockRefresh).toHaveBeenCalledWith('CLAUDE');
  });

  it('shows pi /login warning when pi catalog reports not installed', () => {
    vi.mocked(usePiCatalog).mockReturnValue({
      data: {
        installed: false,
        authenticatedProviders: [],
        customProviders: [],
        defaultProvider: null,
        defaultModel: null,
      },
    } as any);
    render(
      <WorkflowDetailSidebar workflow={buildWorkflow({ provider: 'PI' })} {...defaultProps} />
    );
    expect(screen.getByText(/pi \/login/)).toBeInTheDocument();
  });

  it('renders without throwing with all optional props empty', () => {
    expect(() =>
      render(
        <WorkflowDetailSidebar
          workflow={buildWorkflow({
            activatedSkillIds: [],
            activatedSkillNames: [],
            defaultContext: {},
            instructions: null,
          })}
          workflowVariables={[]}
          onOpenEditInstructions={vi.fn()}
          onOpenAddSkill={vi.fn()}
          onDeactivateSkill={vi.fn()}
          onNavigateToRun={vi.fn()}
        />
      )
    ).not.toThrow();
  });

  it('shows the effort card unless the active provider declares no effort support', () => {
    const { unmount } = render(<WorkflowDetailSidebar workflow={buildWorkflow({ provider: 'CLAUDE' })} {...defaultProps} />);
    expect(screen.getByText('Reasoning effort')).toBeInTheDocument();
    unmount();

    vi.mocked(useAiProviders).mockReturnValue({
      data: { providers: ['CLAUDE', 'CODEX'], default: 'CLAUDE', capabilities: { CODEX: { supportsEffort: false } } },
    } as any);
    render(<WorkflowDetailSidebar workflow={buildWorkflow({ provider: 'CODEX' })} {...defaultProps} />);
    expect(screen.queryByText('Reasoning effort')).not.toBeInTheDocument();
  });

  it('switches auto-repair and keeps its mode', () => {
    render(<WorkflowDetailSidebar {...defaultProps}
      workflow={buildWorkflow({ metadata: { autoRepair: { enabled: true, mode: 'test' } } })} />);
    const toggle = screen.getByRole('switch', { name: 'Auto-Repair' });
    expect(toggle).toHaveAttribute('aria-checked', 'true');

    fireEvent.click(toggle);
    expect(mockMutate).toHaveBeenCalledWith({
      id: 'wf-1', data: { metadata: { autoRepair: { enabled: false, mode: 'test' } } },
    });
  });

  describe('sealed workflow', () => {
    it('locks the definition editors but keeps recent runs usable', () => {
      render(<WorkflowDetailSidebar {...defaultProps}
        workflow={buildWorkflow({ sealed: true, activatedSkillIds: ['s1'], activatedSkillNames: ['Skill'] })} />);

      expect(screen.getByRole('button', { name: /edit/i })).toBeDisabled();
      // The live-log choice is an operating setting outside the definition and stays editable.
      expect(screen.getByRole('combobox', { name: 'Keep live log' })).toBeEnabled();
      screen.getAllByRole('combobox')
        .filter((select) => select.getAttribute('aria-label') !== 'Keep live log')
        .forEach((select) => expect(select).toBeDisabled());
      expect(screen.getByRole('button', { name: /deactivate/i })).toBeDisabled();
      expect(screen.getByRole('switch', { name: 'Auto-Repair' })).toBeDisabled();
    });

    it('lets the live-log choice follow the system default and sends the picked value', () => {
      vi.mocked(useExecutionConfig).mockReturnValue({ data: { liveLogPersistDefault: 0 } } as any);
      render(<WorkflowDetailSidebar {...defaultProps} workflow={buildWorkflow({ liveLogPersist: null })} />);

      const select = screen.getByRole('combobox', { name: 'Keep live log' });
      expect(select).toHaveValue('default');
      expect(screen.getByRole('option', { name: 'System default (off)' })).toBeInTheDocument();

      fireEvent.change(select, { target: { value: 'on' } });

      expect(mockMutate).toHaveBeenCalledWith({ id: 'wf-1', data: { liveLogPersist: 'on' } });
    });

    it('leaves the editors enabled for unsealed workflows', () => {
      render(<WorkflowDetailSidebar {...defaultProps} workflow={buildWorkflow()} />);

      expect(screen.getByRole('button', { name: /edit/i })).toBeEnabled();
    });
  });
});
