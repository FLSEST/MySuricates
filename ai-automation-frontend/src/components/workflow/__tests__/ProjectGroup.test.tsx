import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { act, screen } from '@testing-library/react';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { ProjectGroup } from '../ProjectGroup';
import type { Workflow } from '../../../types';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => useI18nStore.setState({ locale: 'en' }));

function workflow(id: string): Workflow {
  return {
    id, projectId: 'p1', projectName: 'Project One', projectSubtitle: null, projectDescription: null,
    name: `Job ${id}`, description: null, icon: '', blocks: [], version: '1', metadata: {},
    activatedSkillIds: [], activatedSkillNames: [], runCount: 0, instructions: null, model: null,
    templateId: null, createdAt: '2025-01-01T00:00:00Z', updatedAt: '2025-01-01T00:00:00Z',
  } as unknown as Workflow;
}

function renderGroup(selectedIds: Set<string>) {
  const workflows = [workflow('w1'), workflow('w2')];
  return renderWithProviders(
    <ProjectGroup
      projectId="p1"
      projectName="Project One"
      workflows={workflows}
      collapsed={false}
      onToggle={vi.fn()}
      selectedId={null}
      onSelect={vi.fn()}
      onExecute={vi.fn()}
      onDelete={vi.fn()}
      navigate={vi.fn()}
      selectMode
      selectedIds={selectedIds}
      onToggleSelect={vi.fn()}
      onToggleProjectSelect={vi.fn()}
    />,
  );
}

// 78d7be99: the project-wide select toggle was titled in German regardless of the app language
describe('ProjectGroup select-all toggle', () => {
  it('uses the English titles', () => {
    renderGroup(new Set());
    expect(screen.getByTitle('Select all')).toBeInTheDocument();
  });

  it('offers to deselect once every workflow of the project is selected', () => {
    renderGroup(new Set(['w1', 'w2']));
    expect(screen.getByTitle('Deselect all')).toBeInTheDocument();
  });

  it('follows the app language', () => {
    renderGroup(new Set(['w1', 'w2']));
    act(() => useI18nStore.setState({ locale: 'de' }));
    expect(screen.getByTitle('Alle abwählen')).toBeInTheDocument();
    expect(screen.queryByTitle('Deselect all')).not.toBeInTheDocument();
  });
});
