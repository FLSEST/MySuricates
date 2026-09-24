import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { de } from '../../../i18n/de';
import type { CreateWorkflowRequest, Project } from '../../../types';
import { WorkflowCreateFlow } from '../WorkflowCreateFlow';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => useI18nStore.setState({ locale: 'de' }));

const project = { id: 'p1', name: 'Test Project', type: 'CODE', path: '/tmp/test', status: 'STOPPED' } as Project;

function renderDetails(projects: Project[], onFormChange = vi.fn(), variant?: 'pro' | 'simple') {
  const form: CreateWorkflowRequest = { projectId: '', name: 'Mein Workflow' };
  renderWithProviders(
    <WorkflowCreateFlow
      t={de}
      locale="de"
      createStep="details"
      selectedTemplate={null}
      form={form}
      projects={projects}
      createLoading={false}
      onFormChange={onFormChange}
      onTemplateSelect={vi.fn()}
      onPoolSelect={vi.fn()}
      onCreate={vi.fn()}
      onClose={vi.fn()}
      onBackToTemplates={vi.fn()}
      variant={variant}
    />,
  );
  return onFormChange;
}

describe('WorkflowCreateFlow without any project', () => {
  it('says why it cannot create yet and offers both ways to a project', () => {
    renderDetails([]);

    expect(screen.getByText(de.workflows.needsProject)).toBeTruthy();
    expect(screen.getByRole('button', { name: de.projects.firstProject.createExample })).toBeTruthy();
    expect(screen.getByRole('button', { name: de.projects.firstProject.createOwn })).toBeTruthy();
    expect(screen.getByRole('button', { name: de.common.create })).toHaveProperty('disabled', true);
  });

  it('shows the project form in the same dialog, and back returns to the workflow', async () => {
    renderDetails([]);

    fireEvent.click(screen.getByRole('button', { name: de.projects.firstProject.createOwn }));
    expect(screen.getAllByRole('dialog')).toHaveLength(1);
    expect(screen.getByText(de.projects.path)).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: de.common.back }));
    expect(screen.getByText(de.workflows.needsProject)).toBeTruthy();
  });
});

describe('WorkflowCreateFlow with projects', () => {
  it('offers a new project next to the selection and selects it once created', async () => {
    const onFormChange = renderDetails([project]);

    fireEvent.click(screen.getByRole('button', { name: de.workflows.newProject }));
    fireEvent.change(screen.getByPlaceholderText('My Project'), { target: { value: 'Neu' } });
    fireEvent.change(screen.getByPlaceholderText('/home/user/projects/my-project'), { target: { value: '/tmp/new' } });
    fireEvent.click(screen.getByRole('button', { name: de.common.create }));

    // the default handler answers POST /projects with id p2
    await waitFor(() => expect(onFormChange).toHaveBeenCalledWith(expect.objectContaining({ projectId: 'p2', name: 'Mein Workflow' })));
  });
});

describe('WorkflowCreateFlow in Simple mode', () => {
  it('asks a new project only for a name and a folder, and creates it as a personal project', async () => {
    let body: Record<string, unknown> | null = null;
    server.use(http.post('/api/v1/projects', async ({ request }) => {
      body = (await request.json()) as Record<string, unknown>;
      return HttpResponse.json({ ...project, id: 'p3', name: 'Neu', type: 'PERSONAL' });
    }));
    const onFormChange = renderDetails([project], vi.fn(), 'simple');

    fireEvent.click(screen.getByRole('button', { name: de.workflows.newProject }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).queryByRole('combobox')).toBeNull();
    expect(within(dialog).queryByText(de.projects.type)).toBeNull();
    expect(within(dialog).queryByText(de.projects.subtitleField)).toBeNull();
    expect(within(dialog).getByText(de.projects.folder)).toBeTruthy();
    expect(within(dialog).getByRole('button', { name: de.projects.browse })).toBeTruthy();

    const [name, folder] = within(dialog).getAllByRole('textbox');
    fireEvent.change(name!, { target: { value: 'Neu' } });
    fireEvent.change(folder!, { target: { value: '/tmp/new' } });
    fireEvent.click(within(dialog).getByRole('button', { name: de.common.create }));

    await waitFor(() => expect(onFormChange).toHaveBeenCalledWith(expect.objectContaining({ projectId: 'p3' })));
    expect(body).toEqual(expect.objectContaining({ name: 'Neu', path: '/tmp/new', type: 'PERSONAL' }));
  });

  it('still offers the example project first when there is none', () => {
    renderDetails([], vi.fn(), 'simple');

    expect(screen.getByRole('button', { name: de.projects.firstProject.createExample })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: de.projects.firstProject.createOwn }));
    expect(screen.getByText(de.projects.folder)).toBeTruthy();
    expect(screen.queryByText(de.projects.path)).toBeNull();
  });
});
