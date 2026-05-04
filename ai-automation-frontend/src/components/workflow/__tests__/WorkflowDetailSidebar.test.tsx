import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { WorkflowDetailSidebar } from '../WorkflowDetailSidebar';
import { useI18nStore } from '../../../i18n';
import { useUpdateWorkflow, useWorkflowRuns } from '../../../hooks/useWorkflows';
import { useAiProviders, usePiCatalog } from '../../../hooks/useSystem';
import type { Workflow } from '../../../types';

const mockMutate = vi.fn();

vi.mock('../../../hooks/useWorkflows', () => ({
  useUpdateWorkflow: vi.fn(),
  useWorkflowRuns: vi.fn(),
}));

vi.mock('../../../hooks/useSystem', () => ({
  useAiProviders: vi.fn(),
  usePiCatalog: vi.fn(),
}));

vi.mock('../WorkflowMemoryPanel', () => ({
  WorkflowMemoryPanel: () => null,
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
    // datalist for pi suggestions is rendered
    expect(document.getElementById('pi-model-suggestions')).not.toBeNull();
    // placeholder text confirms it is a free-text input, not a select
    expect(screen.getByPlaceholderText(/anthropic\/claude/)).toBeInTheDocument();
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
});
