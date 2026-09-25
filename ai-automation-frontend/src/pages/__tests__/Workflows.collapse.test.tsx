import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { act, fireEvent, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import { en } from '../../i18n/en';
import { Workflows } from '../Workflows';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  // No stored choice: every project starts collapsed ('__all__')
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
  // The detail panel of the selected workflow; answered here instead of going out to the network
  server.use(
    http.get('/api/v1/workflows/:id/runs', () => HttpResponse.json({ content: [], page: 0, size: 5, totalElements: 0, totalPages: 0 })),
    http.get('/api/v1/workflows/:id/similar', () => HttpResponse.json([])),
  );
});

function workflow(id: string, name: string, projectId: string, projectName: string) {
  return {
    id, projectId, projectName, projectSubtitle: null, projectDescription: null, name, description: null, icon: '',
    blocks: [], version: '1', metadata: {}, activatedSkillIds: [], activatedSkillNames: [], runCount: 0,
    instructions: null, model: null, templateId: null, createdAt: '2025-01-01T00:00:00Z', updatedAt: '2025-01-01T00:00:00Z',
  };
}

const alpha = workflow('wa', 'Alpha job', 'pa', 'Project Alpha');
const beta = workflow('wb', 'Beta job', 'pb', 'Project Beta');

function serveWorkflows(list: ReturnType<typeof workflow>[]) {
  server.use(http.get('/api/v1/workflows', () => HttpResponse.json(list)));
}

const projectButton = (name: string) => screen.findByRole('button', { name: new RegExp(`^${name}`) });

// Each test renders the whole page (list, toolbar, detail panel with lazy parts) up to three times:
// alone 1.5-4.5 s, under the load of the full suite beyond the 5 s default (b26bb8ff)
describe('Workflows — collapsed projects', { timeout: 15_000 }, () => {
  it('collapses the projects of the first load, but a project added later shows open', async () => {
    serveWorkflows([alpha]);
    const { queryClient } = renderWithProviders(<Workflows />, { route: '/workflows' });
    expect(await projectButton('Project Alpha')).toHaveAttribute('aria-expanded', 'false');

    // e.g. created, imported or installed from the pool, then refetched
    serveWorkflows([alpha, beta]);
    await act(() => queryClient.invalidateQueries({ queryKey: ['workflows'] }));

    expect(await projectButton('Project Beta')).toHaveAttribute('aria-expanded', 'true');
    expect(await projectButton('Project Alpha')).toHaveAttribute('aria-expanded', 'false');
  });

  it('stores the choice of "expand/collapse all" and restores it instead of collapsing everything', async () => {
    serveWorkflows([alpha, beta]);
    const { unmount } = renderWithProviders(<Workflows />, { route: '/workflows' });
    expect(await projectButton('Project Alpha')).toHaveAttribute('aria-expanded', 'false');

    fireEvent.click(screen.getByTitle(en.workflows.expandAll));
    expect(await projectButton('Project Alpha')).toHaveAttribute('aria-expanded', 'true');
    expect(await projectButton('Project Beta')).toHaveAttribute('aria-expanded', 'true');
    expect(JSON.parse(localStorage.getItem('workflows-collapsed-projects')!)).toEqual([]);
    unmount();

    // Reopened: the stored (empty) set wins over the default '__all__'
    renderWithProviders(<Workflows />, { route: '/workflows' });
    expect(await projectButton('Project Beta')).toHaveAttribute('aria-expanded', 'true');
    fireEvent.click(screen.getByTitle(en.workflows.collapseAll));
    expect(await projectButton('Project Alpha')).toHaveAttribute('aria-expanded', 'false');
    expect(await projectButton('Project Beta')).toHaveAttribute('aria-expanded', 'false');
    expect(JSON.parse(localStorage.getItem('workflows-collapsed-projects')!)).toEqual(['pa', 'pb']);
  });

  it('keeps projects hidden by the search collapsed when the first toggle happens during a search', async () => {
    serveWorkflows([alpha, beta]);
    renderWithProviders(<Workflows />, { route: '/workflows' });
    expect(await projectButton('Project Beta')).toHaveAttribute('aria-expanded', 'false');

    const searchField = screen.getByPlaceholderText(en.common.search);
    fireEvent.change(searchField, { target: { value: 'Alpha' } });
    expect(screen.queryByRole('button', { name: /^Project Beta/ })).toBeNull();
    fireEvent.click(await projectButton('Project Alpha'));
    expect(await projectButton('Project Alpha')).toHaveAttribute('aria-expanded', 'true');

    fireEvent.change(searchField, { target: { value: '' } });
    expect(await projectButton('Project Beta')).toHaveAttribute('aria-expanded', 'false');
    expect(await projectButton('Project Alpha')).toHaveAttribute('aria-expanded', 'true');
  });
});
