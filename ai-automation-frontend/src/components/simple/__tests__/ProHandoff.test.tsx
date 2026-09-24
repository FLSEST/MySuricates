import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { useI18nStore } from '../../../i18n';
import { useSettingsStore } from '../../../stores/settingsStore';
import { ProBadge, ProHandoff } from '../ProHandoff';

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location">{location.pathname}</div>;
}

function renderAt(route: string, ui: React.ReactElement) {
  return render(
    <MemoryRouter initialEntries={[route]}>
      <Routes>
        <Route path="*" element={<>{ui}<LocationProbe /></>} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  useI18nStore.setState({ locale: 'en' });
  useSettingsStore.setState({ simpleMode: true });
});

describe('ProHandoff', () => {
  it('shows the reason and switches to Pro while opening the target', () => {
    renderAt('/workflows/w1', <ProHandoff reason="Change the steps in Pro." to="/projects/p1" />);

    expect(screen.getByText('Change the steps in Pro.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Open in Pro mode' }));

    expect(useSettingsStore.getState().simpleMode).toBe(false);
    expect(screen.getByTestId('location')).toHaveTextContent('/projects/p1');
  });

  it('stays on the page when no target is given', () => {
    renderAt('/settings', <ProHandoff reason="Hidden here." />);

    fireEvent.click(screen.getByRole('button', { name: 'Open in Pro mode' }));

    expect(useSettingsStore.getState().simpleMode).toBe(false);
    expect(screen.getByTestId('location')).toHaveTextContent('/settings');
  });

  it('labels the button in German', () => {
    useI18nStore.setState({ locale: 'de' });
    renderAt('/', <ProHandoff reason="x" />);
    expect(screen.getByRole('button', { name: 'Im Pro-Modus öffnen' })).toBeInTheDocument();
  });
});

describe('ProBadge', () => {
  it('reads "Pro" and explains itself on hover', () => {
    renderAt('/', <ProBadge />);
    expect(screen.getByText('Pro')).toHaveAttribute('title', 'Part of Pro mode');
  });
});
