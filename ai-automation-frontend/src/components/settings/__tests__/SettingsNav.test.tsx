import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { SettingsNav } from '../SettingsNav';

class IntersectionObserverStub {
  observe = vi.fn();
  disconnect = vi.fn();
}

beforeEach(() => {
  vi.stubGlobal('IntersectionObserver', IntersectionObserverStub);
});

describe('SettingsNav', () => {
  it('renders tray as a navigable child of appearance', () => {
    const onNavigate = vi.fn();
    render(<SettingsNav
      sections={[
        { id: 'appearance', label: 'Appearance', visible: true },
        { id: 'tray', label: 'Tray & desktop', visible: true, parentId: 'appearance' },
      ]}
      onNavigate={onNavigate}
    />);

    const trayButtons = screen.getAllByRole('button', { name: 'Tray & desktop' });
    expect(trayButtons[0]).toHaveClass('pl-7');

    fireEvent.click(trayButtons[0]!);
    expect(onNavigate).toHaveBeenCalledWith('tray');
  });
});
