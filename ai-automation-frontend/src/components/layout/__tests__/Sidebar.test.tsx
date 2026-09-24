import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, waitFor, fireEvent } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { modulesFixture } from '../../../test/modules';
import { useI18nStore } from '../../../i18n';
import { useSettingsStore } from '../../../stores/settingsStore';
import { Sidebar } from '../Sidebar';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
  useSettingsStore.setState({ simpleMode: false, sidebarItemOrder: [], sidebarHiddenItems: [], sidebarDividerLabels: {} });
});

describe('Sidebar modules', () => {
  it('hides pages of modules the backend reports disabled', async () => {
    const modules = modulesFixture('core', ['chains', 'webhooks', 'audit']);
    server.use(http.get('/api/v1/system/capabilities', () => HttpResponse.json(modules)));

    renderWithProviders(<Sidebar />);

    await waitFor(() => expect(screen.queryByRole('link', { name: 'Chains' })).not.toBeInTheDocument(),
      { timeout: 5000 });
    expect(screen.queryByRole('link', { name: 'Webhooks' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Audit Center' })).not.toBeInTheDocument();
    // core pages and still-enabled modules stay
    expect(screen.getByRole('link', { name: 'Workflows' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Todos' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Integrations' })).toBeInTheDocument();
  });

  it('uses the last known modules immediately on reload (no flicker)', () => {
    localStorage.setItem('mysuricates-modules', JSON.stringify(modulesFixture('core', ['chains'])));

    renderWithProviders(<Sidebar />);

    expect(screen.queryByRole('link', { name: 'Chains' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Workflows' })).toBeInTheDocument();
  });

  it('shows module pages when everything is enabled', async () => {
    renderWithProviders(<Sidebar />);

    expect(await screen.findByRole('link', { name: 'Chains' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Webhooks' })).toBeInTheDocument();
  });
});

describe('Sidebar dividers', () => {
  const withModules = (disabled: Parameters<typeof modulesFixture>[1]) =>
    localStorage.setItem('mysuricates-modules', JSON.stringify(modulesFixture('full', disabled)));

  it('shows the four default dividers when every module is enabled', () => {
    withModules([]);
    renderWithProviders(<Sidebar />);
    expect(screen.getAllByRole('separator')).toHaveLength(4);
  });

  it('drops the divider of a group whose only page belongs to a disabled module', () => {
    withModules(['onboarding-profiles']);
    renderWithProviders(<Sidebar />);
    expect(screen.getAllByRole('separator')).toHaveLength(3);
  });

  it('keeps the groups of an installation with several modules off apart', () => {
    // wishes gone, factory and dialogs still there: they keep a divider of their own
    withModules(['wishes']);
    renderWithProviders(<Sidebar />);
    expect(screen.getAllByRole('separator')).toHaveLength(4);
  });

  it('drops the divider of a group the user hid completely', () => {
    withModules([]);
    useSettingsStore.setState({ sidebarHiddenItems: ['/onboarding-profiles'] });
    renderWithProviders(<Sidebar />);
    expect(screen.getAllByRole('separator')).toHaveLength(3);
  });

  it('shows no dividers in simple mode', () => {
    withModules([]);
    useSettingsStore.setState({ simpleMode: true });
    renderWithProviders(<Sidebar />);
    expect(screen.queryAllByRole('separator')).toHaveLength(0);
  });

  it('adds, names and removes dividers in edit mode', () => {
    withModules([]);
    renderWithProviders(<Sidebar />);
    fireEvent.click(screen.getByRole('button', { name: 'Edit menu' }));

    expect(screen.getAllByRole('button', { name: 'Remove divider' })).toHaveLength(4);
    fireEvent.click(screen.getByRole('button', { name: 'Add divider' }));
    const inputs = screen.getAllByRole('textbox', { name: 'Name (optional)' });
    expect(inputs).toHaveLength(5);

    // name the first default divider, remove the second
    fireEvent.change(inputs[0]!, { target: { value: 'Planen' } });
    fireEvent.click(screen.getAllByRole('button', { name: 'Remove divider' })[1]!);

    const { sidebarItemOrder, sidebarDividerLabels } = useSettingsStore.getState();
    expect(sidebarItemOrder.filter((id) => id.startsWith('divider:'))).toHaveLength(4);
    expect(sidebarItemOrder).not.toContain('divider:default-onboarding');
    expect(sidebarDividerLabels).toEqual({ 'divider:default-triggers': 'Planen' });

    fireEvent.click(screen.getByRole('button', { name: 'Finish editing' }));
    // the new divider sits at the end and separates nothing; the named one is a heading
    expect(screen.getAllByRole('separator')).toHaveLength(3);
    expect(screen.getByRole('separator', { name: 'Planen' })).toHaveTextContent('Planen');
  });

  it('restores the default dividers on reset', () => {
    withModules([]);
    useSettingsStore.setState({ sidebarItemOrder: ['/', '/todos'], sidebarDividerLabels: { 'divider:x': 'X' } });
    renderWithProviders(<Sidebar />);
    fireEvent.click(screen.getByRole('button', { name: 'Edit menu' }));
    fireEvent.click(screen.getByRole('button', { name: 'Reset menu' }));
    fireEvent.click(screen.getByRole('button', { name: 'Finish editing' }));

    expect(useSettingsStore.getState().sidebarDividerLabels).toEqual({});
    expect(screen.getAllByRole('separator')).toHaveLength(4);
  });
});

describe('Sidebar edit controls', () => {
  beforeEach(() => localStorage.setItem('mysuricates-modules', JSON.stringify(modulesFixture('full', []))));

  it('labels the edit controls in English', () => {
    renderWithProviders(<Sidebar />);
    expect(screen.getByRole('button', { name: 'Edit menu' })).toHaveTextContent('Edit menu');

    fireEvent.click(screen.getByRole('button', { name: 'Edit menu' }));
    expect(screen.getByRole('button', { name: 'Finish editing' })).toHaveTextContent('Done');
    expect(screen.getByRole('button', { name: 'Reset menu' }))
      .toHaveAttribute('title', 'Restore the default order, hidden pages and dividers');
    expect(screen.getByRole('button', { name: 'Hide Workflows' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Move Workflows' })).toBeInTheDocument();
  });

  it('labels the edit controls in German', () => {
    useI18nStore.setState({ locale: 'de' });
    renderWithProviders(<Sidebar />);
    expect(screen.getByRole('button', { name: 'Menü bearbeiten' })).toHaveTextContent('Menü bearbeiten');

    fireEvent.click(screen.getByRole('button', { name: 'Menü bearbeiten' }));
    expect(screen.getByRole('button', { name: 'Bearbeitung beenden' })).toHaveTextContent('Fertig');
    expect(screen.getByRole('button', { name: 'Menü zurücksetzen' }))
      .toHaveAttribute('title', 'Standard-Reihenfolge, ausgeblendete Seiten und Unterteiler wiederherstellen');
    expect(screen.getByRole('button', { name: 'Workflows ausblenden' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Workflows verschieben' })).toBeInTheDocument();
  });
});
