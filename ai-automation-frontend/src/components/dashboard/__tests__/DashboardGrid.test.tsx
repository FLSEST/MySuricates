import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { useI18nStore } from '../../../i18n';
import type { ResolvedWidget } from '../../../hooks/useDashboardLayout';
import { DashboardGrid } from '../DashboardGrid';
import type { DashboardWidgetDef } from '../widgetDefinition';

vi.mock('../../../utils/errorReporter', () => ({ reportErrorToBackend: vi.fn() }));

function Boom(): never {
  throw new Error('boom');
}
function Fine() {
  return <p>Fine widget</p>;
}

const def = (extra: Partial<DashboardWidgetDef> & Pick<DashboardWidgetDef, 'id' | 'component'>): DashboardWidgetDef => ({
  label: () => extra.id, description: () => '', category: 'overview', size: 'chart', ...extra,
});

const widgets: ResolvedWidget[] = [
  { def: def({ id: 'boom', label: () => 'Broken widget', component: Boom }), instance: { instanceId: 'boom', type: 'boom' } },
  { def: def({ id: 'fine', label: () => 'Fine', component: Fine }), instance: { instanceId: 'fine', type: 'fine' } },
];
const catalogWidget: ResolvedWidget = {
  def: def({ id: 'extra', label: () => 'Extra', size: 'third', catalogOnly: true, component: Fine }),
  instance: { instanceId: 'extra', type: 'extra', size: 'full' },
};

const handlers = () => ({ onMove: vi.fn(), onHide: vi.fn(), onResize: vi.fn(), onRemove: vi.fn() });

beforeAll(() => {
  vi.spyOn(console, 'error').mockImplementation(() => {});
});
beforeEach(() => {
  useI18nStore.setState({ locale: 'en' });
});

describe('DashboardGrid', () => {
  it.each([false, true])('isolates a crashing widget (editMode=%s)', (editMode) => {
    render(<DashboardGrid widgets={widgets} editMode={editMode} {...handlers()} />);
    expect(screen.getByRole('alert')).toHaveTextContent('Broken widget');
    expect(screen.getByText('Fine widget')).toBeInTheDocument();
  });

  it('locks arranging, resizing and hiding while disabled', () => {
    render(<DashboardGrid widgets={[widgets[1]!, catalogWidget]} editMode disabled {...handlers()} />);

    for (const name of ['Move: Fine', 'Size: Fine', 'Hide: Fine', 'Remove: Extra']) {
      expect(screen.getByRole('button', { name })).toBeDisabled();
    }
  });

  it('changes the size from the menu, which marks the default and the current size', () => {
    const props = handlers();
    render(<DashboardGrid widgets={[catalogWidget]} editMode {...props} />);

    fireEvent.click(screen.getByRole('button', { name: 'Size: Extra' }));
    const menu = screen.getByRole('menu', { name: 'Size: Extra' });
    expect(within(menu).getAllByRole('menuitemradio').map((item) => item.textContent)).toEqual([
      'Tile', 'Chart', 'One third (default)', 'Two thirds', 'Full width',
    ]);
    expect(within(menu).getByRole('menuitemradio', { name: 'Full width' })).toHaveAttribute('aria-checked', 'true');

    fireEvent.click(within(menu).getByRole('menuitemradio', { name: 'Tile' }));
    expect(props.onResize).toHaveBeenCalledWith('extra', 'stat');
  });

  it('offers removing only for catalog and multi widgets', () => {
    const props = handlers();
    render(<DashboardGrid widgets={[widgets[1]!, catalogWidget]} editMode {...props} />);

    expect(screen.queryByRole('button', { name: 'Remove: Fine' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Remove: Extra' }));
    expect(props.onRemove).toHaveBeenCalledWith('extra');
  });
});
