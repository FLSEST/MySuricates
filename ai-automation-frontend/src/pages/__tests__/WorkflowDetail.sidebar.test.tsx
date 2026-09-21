import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import { WorkflowDetail } from '../WorkflowDetail';

// The page layout is under test, not its sections
vi.mock('../../components/workflow/WorkflowDetailHeader', () => ({ WorkflowDetailHeader: () => null }));
vi.mock('../../components/workflow/WorkflowDetailModals', () => ({ WorkflowDetailModals: () => null }));
vi.mock('../../components/workflow/CompletionTriggerSection', () => ({ CompletionTriggerSection: () => null }));
vi.mock('../../components/workflow/SortableBlockList', () => ({ SortableBlockList: () => null }));
vi.mock('../../components/workflow/WorkflowDetailSidebar', () => ({
  WorkflowDetailSidebar: () => <div>Sidebar content</div>,
}));

const WORKFLOW = {
  id: 'w1', projectId: 'p1', projectName: 'Test Project', name: 'Test Workflow', description: 'A test', icon: '🔧',
  blocks: [{ type: 'shell', name: 'step', config: { command: 'true' } }],
  activatedSkillIds: [], activatedSkillNames: [], version: '1', runCount: 0,
  instructions: null, model: null, provider: null, effort: null,
  createdAt: '2025-01-01T00:00:00Z', updatedAt: '2025-01-01T00:00:00Z',
};

function renderPage() {
  return renderWithProviders(
    <Routes><Route path="/workflows/:id" element={<WorkflowDetail />} /></Routes>,
    { route: '/workflows/w1' },
  );
}

describe('WorkflowDetail sidebar', () => {
  beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
  afterEach(() => server.resetHandlers());
  afterAll(() => server.close());
  beforeEach(() => {
    localStorage.clear();
    useI18nStore.setState({ locale: 'en' });
    server.use(
      http.get('/api/v1/workflows/w1', () => HttpResponse.json(WORKFLOW)),
      http.get('/api/v1/workflows/w1/runs', () =>
        HttpResponse.json({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })),
    );
  });

  it('collapses the sidebar to give the blocks the full width and remembers it', async () => {
    renderPage();
    expect(await screen.findByText('Sidebar content')).toBeVisible();
    expect(screen.getByRole('separator')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Hide sidebar' }));

    expect(screen.getByText('Sidebar content')).not.toBeVisible();
    expect(screen.queryByRole('separator')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Show sidebar' })).toHaveAttribute('aria-expanded', 'false');
    expect(JSON.parse(localStorage.getItem('workflow-detail-sidebar-collapsed')!)).toBe(true);
  });

  it('starts collapsed when the viewer collapsed it before', async () => {
    localStorage.setItem('workflow-detail-sidebar-collapsed', 'true');
    renderPage();
    const toggle = await screen.findByRole('button', { name: 'Show sidebar' });
    expect(screen.getByText('Sidebar content')).not.toBeVisible();

    fireEvent.click(toggle);
    expect(screen.getByText('Sidebar content')).toBeVisible();
  });
});
