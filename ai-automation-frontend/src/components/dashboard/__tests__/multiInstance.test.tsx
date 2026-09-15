import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { screen, within, fireEvent } from '@testing-library/react';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { useDashboardLayoutStore } from '../../../stores/dashboardLayoutStore';
import { useDashboardLayout } from '../../../hooks/useDashboardLayout';
import { createDefaultLayout } from '../dashboardLayout';
import { DashboardGrid } from '../DashboardGrid';
import type { DashboardWidgetDef, WidgetProps } from '../widgetRegistry';

vi.mock('../widgetRegistry', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../widgetRegistry')>();
  function SingletonWidget() {
    return <p>Singleton</p>;
  }
  function NoteWidget({ config }: WidgetProps) {
    return <p>Note: {typeof config.text === 'string' ? config.text : ''}</p>;
  }
  const widgets: DashboardWidgetDef[] = [
    { id: 'test-singleton', label: () => 'Singleton', size: 'chart', component: SingletonWidget },
    { id: 'test-note', label: () => 'Note', size: 'third', multiple: true, component: NoteWidget },
  ];
  return { ...actual, DASHBOARD_WIDGETS: widgets, DASHBOARD_WIDGET_IDS: widgets.map((w) => w.id) };
});

function Harness({ editMode = false }: { editMode?: boolean }) {
  const layout = useDashboardLayout();
  return (
    <DashboardGrid
      widgets={layout.visible}
      editMode={editMode}
      onMove={layout.move}
      onHide={(instanceId) => layout.setHidden(instanceId, true)}
    />
  );
}

const slots = () => Array.from(document.querySelectorAll<HTMLElement>('[data-widget-id]'));

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
  useDashboardLayoutStore.setState(createDefaultLayout());
});

describe('multi-instance widgets', () => {
  it('adds singletons by default but never multi widgets', () => {
    renderWithProviders(<Harness />);
    expect(screen.getByText('Singleton')).toBeInTheDocument();
    expect(screen.queryByText(/^Note:/)).not.toBeInTheDocument();
  });

  it('renders each instance with its own config in stored order', () => {
    useDashboardLayoutStore.setState({
      views: [{
        id: 'default',
        name: '',
        widgets: [
          { instanceId: 'test-note-a1', type: 'test-note', config: { text: 'first' } },
          { instanceId: 'test-singleton', type: 'test-singleton' },
          { instanceId: 'test-note-b2', type: 'test-note', config: { text: 'second' } },
        ],
      }],
    });
    renderWithProviders(<Harness />);

    expect(slots().map((s) => s.dataset.widgetId)).toEqual(['test-note-a1', 'test-singleton', 'test-note-b2']);
    expect(slots().map((s) => s.dataset.widgetType)).toEqual(['test-note', 'test-singleton', 'test-note']);
    expect(within(slots()[0]!).getByText('Note: first')).toBeInTheDocument();
    expect(within(slots()[2]!).getByText('Note: second')).toBeInTheDocument();
  });

  it('hides one instance without touching its siblings', () => {
    useDashboardLayoutStore.setState({
      views: [{
        id: 'default',
        name: '',
        widgets: [
          { instanceId: 'test-note-a1', type: 'test-note', config: { text: 'first' } },
          { instanceId: 'test-note-b2', type: 'test-note', config: { text: 'second' } },
        ],
      }],
    });
    renderWithProviders(<Harness editMode />);

    fireEvent.click(within(slots()[0]!).getByRole('button', { name: 'Hide: Note' }));

    expect(screen.queryByText('Note: first')).not.toBeInTheDocument();
    expect(screen.getByText('Note: second')).toBeInTheDocument();
    expect(useDashboardLayoutStore.getState().views[0]?.widgets).toContainEqual(
      { instanceId: 'test-note-a1', type: 'test-note', config: { text: 'first' }, hidden: true },
    );
  });
});
