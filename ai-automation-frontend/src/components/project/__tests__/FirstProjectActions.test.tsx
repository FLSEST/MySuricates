import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { de } from '../../../i18n/de';
import { FirstProjectActions } from '../FirstProjectActions';
import { FirstStepsCard } from '../../dashboard/FirstStepsCard';
import { useDashboardLayoutStore } from '../../../stores/dashboardLayoutStore';
import { createDefaultLayout } from '../../dashboard/dashboardLayout';
import { exampleProjectViewId } from '../../dashboard/exampleProjectView';

const example = {
  id: 'ex', name: 'Beispielprojekt', type: 'PERSONAL', path: '/data/examples/example-project', status: 'STOPPED',
  container: null, workflowCount: 1, createdAt: '2026-09-21T00:00:00Z', updatedAt: '2026-09-21T00:00:00Z',
};

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  useI18nStore.setState({ locale: 'de' });
  useDashboardLayoutStore.setState(createDefaultLayout());
});

describe('FirstProjectActions', () => {
  it('creates the example project in the UI language and hands it on', async () => {
    let requested = '';
    server.use(http.post('/api/v1/projects/example', ({ request }) => {
      requested = request.url;
      return HttpResponse.json(example, { status: 201 });
    }));
    const onProjectReady = vi.fn();
    renderWithProviders(<FirstProjectActions onProjectReady={onProjectReady} />);

    fireEvent.click(screen.getByRole('button', { name: de.projects.firstProject.createExample }));

    await waitFor(() => expect(onProjectReady).toHaveBeenCalledWith(expect.objectContaining({ id: 'ex' })));
    expect(new URL(requested).searchParams.get('locale')).toBe('de');
  });

  it('adds a dashboard view for the example project once, without switching to it', async () => {
    server.use(
      http.post('/api/v1/projects/example', () => HttpResponse.json(example, { status: 201 })),
      http.get('/api/v1/workflows', () => HttpResponse.json([
        { id: 'wf-other', projectId: 'other', templateId: 'blueprint-report' },
        { id: 'wf-approval', projectId: 'ex', templateId: 'blueprint-approval' },
        { id: 'wf-example', projectId: 'ex', templateId: 'blueprint-report' },
      ])),
    );
    renderWithProviders(<FirstProjectActions />);
    const button = screen.getByRole('button', { name: de.projects.firstProject.createExample });

    fireEvent.click(button);
    await waitFor(() => expect(useDashboardLayoutStore.getState().views).toHaveLength(2));

    const { activeViewId, views } = useDashboardLayoutStore.getState();
    expect(activeViewId).toBe('default');
    expect(views[1]).toMatchObject({ id: exampleProjectViewId('ex'), name: 'Beispielprojekt', filter: { projectId: 'ex' } });
    expect(views[1]?.widgets[0]).toMatchObject({ type: 'workflow-status', config: { workflowId: 'wf-example' } });

    fireEvent.click(button);
    await waitFor(() => expect(button.hasAttribute('disabled')).toBe(false));
    expect(useDashboardLayoutStore.getState().views).toHaveLength(2);
  });

  it('leaves the own project to the caller when it passes a form of its own', async () => {
    const onCreateOwn = vi.fn();
    renderWithProviders(<FirstProjectActions onCreateOwn={onCreateOwn} />);

    fireEvent.click(screen.getByRole('button', { name: de.projects.firstProject.createOwn }));

    expect(onCreateOwn).toHaveBeenCalledOnce();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('opens its own project dialog otherwise', async () => {
    renderWithProviders(<FirstProjectActions />);

    fireEvent.click(screen.getByRole('button', { name: de.projects.firstProject.createOwn }));

    expect(screen.getByRole('dialog')).toBeTruthy();
    expect(screen.getByText(de.projects.path)).toBeTruthy();
  });
});

describe('FirstStepsCard', () => {
  it('appears while there is no project', async () => {
    server.use(http.get('/api/v1/projects', () => HttpResponse.json([])));
    renderWithProviders(<FirstStepsCard />);

    expect(await screen.findByText(de.projects.firstProject.dashboardTitle)).toBeTruthy();
  });

  it('stays hidden once a project exists', async () => {
    const { queryClient } = renderWithProviders(<FirstStepsCard />);

    await waitFor(() => expect(queryClient.getQueryData(['projects'])).toBeDefined());
    expect(screen.queryByTestId('first-steps')).toBeNull();
  });
});
