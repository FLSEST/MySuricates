import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { DashboardMenu } from '../DashboardMenu';

const items = [
  { id: 'small', label: 'Small', checked: false },
  { id: 'medium', label: 'Medium', checked: true },
  { id: 'large', label: 'Large', checked: false },
];

function renderMenu(props: Partial<Parameters<typeof DashboardMenu>[0]> = {}) {
  const onSelect = vi.fn();
  render(
    <>
      <DashboardMenu label="Size: Chart" icon={<span>icon</span>} items={items} onSelect={onSelect} {...props} />
      <p>Outside</p>
    </>,
  );
  return { onSelect, trigger: screen.getByRole('button', { name: 'Size: Chart' }) };
}

describe('DashboardMenu', () => {
  it('opens a menu with the checked item focused and selects with a click', () => {
    const { onSelect, trigger } = renderMenu();
    expect(trigger).toHaveAttribute('aria-haspopup', 'menu');
    expect(trigger).toHaveAttribute('aria-expanded', 'false');

    fireEvent.click(trigger);

    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('menu', { name: 'Size: Chart' })).toBeInTheDocument();
    expect(screen.getByRole('menuitemradio', { name: 'Medium' })).toHaveFocus();
    expect(screen.getByRole('menuitemradio', { name: 'Medium' })).toHaveAttribute('aria-checked', 'true');

    fireEvent.click(screen.getByRole('menuitemradio', { name: 'Large' }));
    expect(onSelect).toHaveBeenCalledWith('large');
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it('moves with the arrow keys, Home and End, and closes with Escape', () => {
    const { trigger } = renderMenu();
    fireEvent.click(trigger);
    const menu = screen.getByRole('menu');

    fireEvent.keyDown(menu, { key: 'ArrowDown' });
    expect(screen.getByRole('menuitemradio', { name: 'Large' })).toHaveFocus();
    fireEvent.keyDown(menu, { key: 'ArrowDown' });
    expect(screen.getByRole('menuitemradio', { name: 'Small' })).toHaveFocus();
    fireEvent.keyDown(menu, { key: 'ArrowUp' });
    expect(screen.getByRole('menuitemradio', { name: 'Large' })).toHaveFocus();
    fireEvent.keyDown(menu, { key: 'Home' });
    expect(screen.getByRole('menuitemradio', { name: 'Small' })).toHaveFocus();
    fireEvent.keyDown(menu, { key: 'End' });
    expect(screen.getByRole('menuitemradio', { name: 'Large' })).toHaveFocus();

    fireEvent.keyDown(menu, { key: 'Escape' });
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it('closes on a click outside without selecting', () => {
    const { onSelect, trigger } = renderMenu();
    fireEvent.click(trigger);

    fireEvent.mouseDown(screen.getByText('Outside'));

    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('uses plain menu items for actions and stays closed while disabled', () => {
    const { trigger } = renderMenu({ items: [{ id: 'ops', label: 'Operations' }], disabled: true });
    expect(trigger).toBeDisabled();
    fireEvent.click(trigger);
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('renders plain action items as menuitem without a checked state', () => {
    const { trigger } = renderMenu({ items: [{ id: 'ops', label: 'Operations' }] });
    fireEvent.click(trigger);
    expect(screen.getByRole('menuitem', { name: 'Operations' })).not.toHaveAttribute('aria-checked');
  });

  it('wraps radio items in a labelled group', () => {
    const { trigger } = renderMenu();
    fireEvent.click(trigger);
    const menu = screen.getByRole('menu', { name: 'Size: Chart' });
    const group = within(menu).getByRole('group', { name: 'Size: Chart' });
    expect(within(group).getAllByRole('menuitemradio')).toHaveLength(3);
  });

  it('has no group around plain action items', () => {
    const { trigger } = renderMenu({ items: [{ id: 'ops', label: 'Operations' }] });
    fireEvent.click(trigger);
    expect(screen.queryByRole('group')).not.toBeInTheDocument();
  });
});
