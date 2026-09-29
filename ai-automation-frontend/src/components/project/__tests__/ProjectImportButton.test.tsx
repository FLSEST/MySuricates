import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { en } from '../../../i18n/en';
import type { ProjectBundle, ProjectBundlePreview, ProjectBundleImportResult } from '../../../types';
import { ProjectImportButton } from '../ProjectImportButton';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  useI18nStore.setState({ locale: 'en' });
});

const bundle: ProjectBundle = {
  format: 'mysuricates-project-bundle',
  formatVersion: 1,
  exportedAt: '2026-09-29T00:00:00Z',
  appVersion: '0.7.8',
  project: { name: 'widgetco', type: 'CODE' },
  workflows: [{ key: 'wf1', definition: { name: 'WIDGETCO Test Generation', blocks: [] } }],
  chains: [],
  schedules: [{ target: { workflowKey: 'wf1' }, scheduleType: 'CRON', cronExpression: '0 0 * * *' }],
  paths: [{ token: '@@path:1@@', original: '/tmp/widgetco', occurrences: 4, hint: 'Shell command in "WIDGETCO Test Generation"' }],
  skillNames: [],
};

const preview: ProjectBundlePreview = {
  project: { name: 'widgetco', nameTaken: false },
  workflows: [{
    key: 'wf1', name: 'WIDGETCO Test Generation', blockCount: 3, blockTypes: ['shell'],
    providers: [], models: [], shellCommands: ['echo hello world'], nameTakenInTarget: false,
  }],
  chains: [],
  schedules: [{ target: 'workflow:wf1', cronExpression: '0 0 * * *', willBeDisabled: true }],
  paths: bundle.paths,
  warnings: ['sub-workflow block references a workflow outside the bundle'],
};

const importResult: ProjectBundleImportResult = {
  projectId: 'proj-99',
  created: { workflows: 1, chains: 0, schedules: 1 },
  skipped: [{ kind: 'workflow', key: 'wf2', reason: 'name exists' }],
  renamed: [],
  warnings: [],
};

function bundleFile() {
  return new File([JSON.stringify(bundle)], 'widgetco.mysuricates-bundle.json', { type: 'application/json' });
}

function openDialogWithFile(container: HTMLElement) {
  fireEvent.click(screen.getByRole('button', { name: en.projects.bundle.importProject }));
  const input = container.querySelector('input[type="file"]') as HTMLInputElement;
  fireEvent.change(input, { target: { files: [bundleFile()] } });
}

describe('ProjectImportButton', () => {
  it('walks file → preview (collapsible shell commands, warnings) → import → result', async () => {
    let importBody: unknown;
    server.use(
      http.post('/api/v1/projects/import/preview', async ({ request }) => {
        expect(await request.json()).toEqual({ bundle });
        return HttpResponse.json(preview);
      }),
      http.post('/api/v1/projects/import', async ({ request }) => {
        importBody = await request.json();
        return HttpResponse.json(importResult, { status: 201 });
      }),
    );
    const { container } = renderWithProviders(<ProjectImportButton />);

    openDialogWithFile(container);

    // Preview screen (the workflow name also appears in the schedule row below)
    expect(await screen.findAllByText('WIDGETCO Test Generation')).toHaveLength(2);
    expect(screen.getByText(en.projects.bundle.schedulesDisabledHint)).toBeInTheDocument();
    expect(screen.getByText(preview.warnings[0]!)).toBeInTheDocument();
    expect(screen.queryByText('echo hello world')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: `${en.projects.bundle.shellCommands} (1)` }));
    expect(screen.getByText('echo hello world')).toBeInTheDocument();
    expect(screen.getByText(en.projects.bundle.shellTrustHint)).toBeInTheDocument();

    // Path field, pre-filled with the original path
    const pathField = screen.getByLabelText('@@path:1@@') as HTMLInputElement;
    expect(pathField).toHaveValue('/tmp/widgetco');
    fireEvent.change(pathField, { target: { value: '/home/x/code/widgetco' } });

    const importButton = screen.getByRole('button', { name: en.common.import });
    expect(importButton).toBeEnabled();
    fireEvent.click(importButton);

    await waitFor(() => expect(importBody).toEqual({
      bundle,
      project: { mode: 'new', name: 'widgetco', path: '' },
      pathMappings: { '@@path:1@@': '/home/x/code/widgetco' },
      onConflict: 'skip',
    }));

    // Result screen
    expect(await screen.findByText(en.projects.bundle.resultTitle)).toBeInTheDocument();
    const link = screen.getByRole('link', { name: new RegExp(en.projects.bundle.openProject) });
    expect(link).toHaveAttribute('href', '/projects/proj-99');
    expect(screen.getByText(en.projects.bundle.resultSkipped)).toBeInTheDocument();
    expect(screen.getByText(/wf2/)).toBeInTheDocument();
  });

  it('shows the server message when the preview request fails (400)', async () => {
    server.use(http.post('/api/v1/projects/import/preview', () =>
      HttpResponse.json({ error: 'Unresolved token @@path:1@@' }, { status: 400 }),
    ));
    const { container } = renderWithProviders(<ProjectImportButton />);

    openDialogWithFile(container);

    expect(await screen.findByText(`${en.projects.bundle.previewFailed}: Unresolved token @@path:1@@`)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: en.common.import })).not.toBeInTheDocument();
  });

  it('imports into an existing project with the chosen conflict behavior', async () => {
    let importBody: unknown;
    server.use(
      http.post('/api/v1/projects/import/preview', () => HttpResponse.json(preview)),
      http.post('/api/v1/projects/import', async ({ request }) => {
        importBody = await request.json();
        return HttpResponse.json(importResult, { status: 201 });
      }),
    );
    const { container } = renderWithProviders(<ProjectImportButton />);

    openDialogWithFile(container);
    expect(await screen.findAllByText('WIDGETCO Test Generation')).toHaveLength(2);

    fireEvent.click(screen.getByRole('button', { name: en.projects.bundle.targetExisting }));
    // Select has no htmlFor/id pairing (project convention) — pick by document order instead.
    const [projectSelect, conflictSelect] = screen.getAllByRole('combobox') as HTMLSelectElement[];
    fireEvent.change(projectSelect!, { target: { value: 'p1' } });
    fireEvent.change(conflictSelect!, { target: { value: 'rename' } });

    fireEvent.click(screen.getByRole('button', { name: en.common.import }));

    await waitFor(() => expect(importBody).toMatchObject({
      project: { mode: 'existing', projectId: 'p1' },
      onConflict: 'rename',
    }));
  });

  it('the preview stays open until the file screen produces a successful answer', async () => {
    renderWithProviders(<ProjectImportButton />);
    fireEvent.click(screen.getByRole('button', { name: en.projects.bundle.importProject }));

    expect(within(screen.getByRole('dialog')).getByRole('button', { name: en.projects.bundle.selectFile })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: en.common.import })).not.toBeInTheDocument();
  });
});
