import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { fireEvent, screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { TemplateSelector } from '../TemplateSelector';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => useI18nStore.setState({ locale: 'en' }));

const base = { icon: '🧪', instructions: null, model: null, builtIn: true, qualityStatus: 'UNTESTED', verifications: [],
  version: '1.0.0', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' };
const templates = [
  { ...base, id: 'code-quality', kind: 'TEMPLATE', category: 'CODE', sortOrder: 0,
    name: { de: 'Code-Qualität', en: 'Code Quality' }, description: { de: 'Prüft', en: 'Checks' },
    blocks: [{ type: 'shell', config: { command: 'npm run lint' } }] },
  { ...base, id: 'blueprint-approval', kind: 'BLUEPRINT', category: 'BASICS', sortOrder: 1,
    name: { de: 'Freigabe vor dem Handeln', en: 'Approval before acting' }, description: { de: 'Zeigt Freigabe', en: 'Shows approval' },
    blocks: [{ type: 'shell', config: { command: 'date' } }, { type: 'approval', config: { question: 'Go?' } }] },
  { ...base, id: 'blueprint-intake', kind: 'BLUEPRINT', category: 'COMBINATIONS', sortOrder: 2,
    name: { de: 'Meldung aufnehmen', en: 'Taking in a report' }, description: { de: 'Formular', en: 'Form' },
    blocks: [{ type: 'human-input', config: { prompt: '?' } }] },
];

describe('TemplateSelector — blueprints', () => {
  it('opens on blueprints, the first tab, grouped; templates keep their own tab', async () => {
    server.use(http.get('/api/v1/workflow-templates', () => HttpResponse.json(templates)));
    const onSelect = vi.fn();

    renderWithProviders(<TemplateSelector onSelect={onSelect} />);

    const tabs = screen.getAllByRole('button').slice(0, 3).map((b) => b.textContent);
    expect(tabs).toEqual(['Blueprints', 'Templates', 'Pool']);
    const basics = await screen.findByRole('region', { name: 'Basic connections' });
    expect(screen.getByText(/Empty scaffolds that show how block types connect/)).toBeInTheDocument();
    const combinations = screen.getByRole('region', { name: 'Combinations' });
    expect(within(basics).getByText('Approval before acting')).toBeInTheDocument();
    expect(within(combinations).getByText('Taking in a report')).toBeInTheDocument();
    expect(screen.queryByText('Code Quality')).not.toBeInTheDocument();

    fireEvent.click(within(basics).getByText('Approval before acting'));
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ id: 'blueprint-approval' }));

    fireEvent.click(screen.getByRole('button', { name: 'Templates' }));
    expect(screen.getByText('Code Quality')).toBeInTheDocument();
    expect(screen.queryByText('Approval before acting')).not.toBeInTheDocument();
  });

  it('offers the blank workflow on top of the blueprints', async () => {
    server.use(http.get('/api/v1/workflow-templates', () => HttpResponse.json(templates)));
    const onSelect = vi.fn();

    renderWithProviders(<TemplateSelector onSelect={onSelect} />);
    await screen.findByRole('region', { name: 'Basic connections' });
    fireEvent.click(screen.getByText('Blank Workflow'));

    expect(onSelect).toHaveBeenCalledWith(null);
  });

  it('shows the German names and groups in German', async () => {
    useI18nStore.setState({ locale: 'de' });
    server.use(http.get('/api/v1/workflow-templates', () => HttpResponse.json(templates)));

    renderWithProviders(<TemplateSelector onSelect={() => {}} />);

    expect(await screen.findByRole('region', { name: 'Grundverknüpfungen' })).toBeInTheDocument();
    expect(screen.getByText('Freigabe vor dem Handeln')).toBeInTheDocument();
    expect(screen.getAllByRole('button').slice(0, 3).map((b) => b.textContent)).toEqual(['Blaupausen', 'Vorlagen', 'Pool']);
  });
});
