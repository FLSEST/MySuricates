import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { modulesFixture } from '../../../test/modules';
import { useI18nStore } from '../../../i18n';
import { TemplateSelector } from '../TemplateSelector';
import type { ModuleName, ModulePreset } from '../../../types';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
});

const base = { icon: '🧪', instructions: null, model: null, builtIn: true, qualityStatus: 'UNTESTED', verifications: [],
  version: '1.0.0', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', kind: 'TEMPLATE' };
const templates = [
  { ...base, id: 'code-quality', category: 'CODE', sortOrder: 0, requiredModules: [], needsAi: false,
    name: { de: 'Code-Qualität', en: 'Code Quality' }, description: { de: 'Prüft', en: 'Checks' },
    blocks: [{ type: 'shell', config: { command: 'npm run lint' } }] },
  { ...base, id: 'data-analysis', category: 'DATA', sortOrder: 1, requiredModules: ['skills'], needsAi: true,
    name: { de: 'Datenanalyse', en: 'Data analysis' }, description: { de: 'Analysiert', en: 'Analyses' },
    blocks: [{ type: 'skill-capability', config: { capability: 'analyze' } }] },
  { ...base, id: 'summary', category: 'CONTENT', sortOrder: 2, requiredModules: [], needsAi: true,
    name: { de: 'Zusammenfassung', en: 'Summary' }, description: { de: 'Fasst zusammen', en: 'Summarises' },
    blocks: [{ type: 'simple', config: { command: 'Summarise' } }] },
  { ...base, id: 'blueprint-report', kind: 'BLUEPRINT', category: 'BASICS', sortOrder: 3,
    requiredModules: ['dashboard-report'], needsAi: false,
    name: { de: 'Bericht', en: 'Report' }, description: { de: 'Füllt das Dashboard', en: 'Fills the dashboard' },
    blocks: [{ type: 'dashboard-report', config: { status: 'GREEN' } }] },
];

function serve(preset: ModulePreset, off: ModuleName[], uiEditable = false) {
  server.use(
    http.get('/api/v1/workflow-templates', () => HttpResponse.json(templates)),
    http.get('/api/v1/system/capabilities', () =>
      HttpResponse.json({ blockTypes: [], ...modulesFixture(preset, off, uiEditable) })),
  );
}

async function openTemplatesTab() {
  renderWithProviders(<TemplateSelector onSelect={() => {}} />);
  fireEvent.click(await screen.findByRole('button', { name: 'Templates' }));
  await screen.findByText('Code Quality');
}

describe('TemplateSelector — what runs on this instance', () => {
  it('hides a template whose module is off, behind a counter that shows it with the module and how to switch it on', async () => {
    serve('core', ['skills', 'integrations']);
    await openTemplatesTab();

    const toggle = await screen.findByTestId('needs-module-toggle');
    expect(toggle).toHaveTextContent('1 more needs a module – show');
    expect(screen.queryByText('Data analysis')).not.toBeInTheDocument();

    fireEvent.click(toggle);
    expect(screen.getByText('Data analysis')).toBeInTheDocument();
    const note = screen.getByTestId('template-module-note');
    expect(note).toHaveTextContent('Needs a module that is off on this instance: Skills');
    expect(note).toHaveTextContent('then restart the backend');
    expect(note).toHaveTextContent('automation.modules.skills: true');
    expect(screen.getByTestId('needs-module-toggle')).toHaveTextContent('Show only what runs here');
  });

  it('points to the settings switch when modules are editable there', async () => {
    serve('core', ['skills'], true);
    await openTemplatesTab();
    fireEvent.click(await screen.findByTestId('needs-module-toggle'));

    expect(screen.getByTestId('template-module-note')).toHaveTextContent('Settings → Modules & hardening');
  });

  it('shows everything and no counter when every module is on', async () => {
    serve('full', []);
    await openTemplatesTab();

    expect(await screen.findByText('Data analysis')).toBeInTheDocument();
    expect(screen.queryByTestId('needs-module-toggle')).not.toBeInTheDocument();
    expect(screen.queryByTestId('template-module-note')).not.toBeInTheDocument();
  });

  it('filters to what runs without AI', async () => {
    serve('full', []);
    await openTemplatesTab();
    await screen.findByText('Data analysis');

    const filter = screen.getByRole('button', { name: 'Runs without AI' });
    expect(filter).toHaveAttribute('aria-pressed', 'false');
    fireEvent.click(filter);

    expect(filter).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText('Code Quality')).toBeInTheDocument();
    expect(screen.queryByText('Summary')).not.toBeInTheDocument();
    expect(screen.queryByText('Data analysis')).not.toBeInTheDocument();
  });

  it('names the categories in the UI language', async () => {
    useI18nStore.setState({ locale: 'de' });
    serve('full', []);
    renderWithProviders(<TemplateSelector onSelect={() => {}} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Vorlagen' }));
    await screen.findByText('Code-Qualität');

    expect(screen.getByRole('button', { name: 'Daten' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Inhalte' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'DATA' })).not.toBeInTheDocument();
  });

  it('hides a blueprint whose module is off the same way', async () => {
    serve('full', ['dashboard-report']);
    renderWithProviders(<TemplateSelector onSelect={() => {}} />);

    const toggle = await screen.findByTestId('needs-module-toggle');
    expect(screen.queryByText('Report')).not.toBeInTheDocument();
    fireEvent.click(toggle);
    expect(screen.getByText('Report')).toBeInTheDocument();
    expect(screen.getByTestId('template-module-note')).toHaveTextContent('Dashboard report');
  });
});
