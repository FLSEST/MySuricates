import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { useI18nStore } from '../../../i18n';
import type { Workflow, WorkflowMetadata } from '../../../types';

const mutate = vi.fn();
vi.mock('../../../hooks/useWorkflows', () => ({
  useUpdateWorkflow: () => ({ mutate, isPending: false }),
}));

import { WorkflowAutomationSettings } from '../WorkflowAutomationSettings';

beforeEach(() => {
  mutate.mockClear();
  useI18nStore.setState({ locale: 'en' });
});

function workflow(metadata?: WorkflowMetadata): Workflow {
  return {
    id: 'wf-1', projectId: 'p1', projectName: 'P', name: 'Deploy', description: '', icon: '🚀', blocks: [],
    activatedSkillIds: [], activatedSkillNames: [], version: '1', runCount: 0, instructions: null, model: null,
    provider: null, effort: null, createdAt: '2026-09-14T10:00:00Z', updatedAt: '2026-09-14T10:00:00Z', metadata,
  };
}

const savedMetadata = () => mutate.mock.calls.map(([arg]) => arg.data.metadata);

describe('WorkflowAutomationSettings', () => {
  it('shows three switches, all off, and no options while they are off', () => {
    render(<WorkflowAutomationSettings workflow={workflow()} />);
    const switches = screen.getAllByRole('switch');
    expect(switches.map((s) => s.getAttribute('aria-label'))).toEqual(['Auto-Todo', 'Auto-Repair', 'Auto-Memory']);
    switches.forEach((s) => expect(s).toHaveAttribute('aria-checked', 'false'));
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
    expect(screen.queryByRole('spinbutton')).not.toBeInTheDocument();
  });

  it('switches each automation on and keeps its other settings', () => {
    render(<WorkflowAutomationSettings workflow={workflow({
      autoTodo: { enabled: false, mode: 'track' },
      autoRepair: { enabled: false, mode: 'local' },
      autoMemoryExtraction: { enabled: false, interval: 7 },
    })} />);
    for (const name of ['Auto-Todo', 'Auto-Repair', 'Auto-Memory']) {
      fireEvent.click(screen.getByRole('switch', { name }));
    }
    expect(mutate.mock.calls.every(([arg]) => arg.id === 'wf-1')).toBe(true);
    expect(savedMetadata()).toEqual([
      { autoTodo: { enabled: true, mode: 'track' } },
      { autoRepair: { enabled: true, mode: 'local' } },
      { autoMemoryExtraction: { enabled: true, interval: 7 } },
    ]);
  });

  it('switches an enabled automation off', () => {
    render(<WorkflowAutomationSettings workflow={workflow({ autoRepair: { enabled: true } })} />);
    const repair = screen.getByRole('switch', { name: 'Auto-Repair' });
    expect(repair).toHaveAttribute('aria-checked', 'true');
    fireEvent.click(repair);
    expect(savedMetadata()).toEqual([{ autoRepair: { enabled: false } }]);
  });

  it('offers the modes of enabled automations and saves the choice', () => {
    render(<WorkflowAutomationSettings workflow={workflow({
      autoTodo: { enabled: true },
      autoRepair: { enabled: true, mode: 'test' },
    })} />);
    const [todoMode, repairMode] = screen.getAllByRole('combobox');
    expect(todoMode).toHaveValue('auto');
    expect(repairMode).toHaveValue('test');

    fireEvent.change(todoMode!, { target: { value: 'approval' } });
    fireEvent.change(repairMode!, { target: { value: 'local' } });
    expect(savedMetadata()).toEqual([
      { autoTodo: { enabled: true, mode: 'approval' } },
      { autoRepair: { enabled: true, mode: 'local' } },
    ]);
  });

  it('keeps the memory interval between 1 and 50 and falls back to 5', () => {
    render(<WorkflowAutomationSettings workflow={workflow({ autoMemoryExtraction: { enabled: true } })} />);
    const interval = screen.getByRole('spinbutton');
    expect(interval).toHaveValue(5);

    fireEvent.change(interval, { target: { value: '80' } });
    fireEvent.change(interval, { target: { value: '12' } });
    fireEvent.change(interval, { target: { value: '' } });
    expect(savedMetadata().map((m) => m.autoMemoryExtraction.interval)).toEqual([50, 12, 5]);
  });
});
