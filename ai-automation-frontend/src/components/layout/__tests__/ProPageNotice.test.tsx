import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { useI18nStore } from '../../../i18n';
import { useSettingsStore } from '../../../stores/settingsStore';
import { ProPageNotice } from '../ProPageNotice';

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location">{location.pathname}</div>;
}

function renderAt(route: string) {
  return render(
    <MemoryRouter initialEntries={[route]}>
      <Routes>
        <Route path="*" element={<><ProPageNotice /><LocationProbe /></>} />
      </Routes>
    </MemoryRouter>,
  );
}

const NOTICE = 'This page belongs to Pro mode.';

beforeEach(() => {
  useI18nStore.setState({ locale: 'en' });
  useSettingsStore.setState({ simpleMode: true });
});

describe('ProPageNotice', () => {
  it('appears on a Pro page in Simple mode', () => {
    renderAt('/factory');
    expect(screen.getByText(NOTICE)).toBeInTheDocument();
  });

  it.each(['/', '/workflows/w1', '/runs', '/settings', '/approvals'])('stays away from %s', (route) => {
    renderAt(route);
    expect(screen.queryByText(NOTICE)).not.toBeInTheDocument();
  });

  it('stays away in Pro mode', () => {
    useSettingsStore.setState({ simpleMode: false });
    renderAt('/factory');
    expect(screen.queryByText(NOTICE)).not.toBeInTheDocument();
  });

  it('switches to Pro and stays on the page', () => {
    renderAt('/projects/p1');
    fireEvent.click(screen.getByRole('button', { name: 'Switch to Pro' }));

    expect(useSettingsStore.getState().simpleMode).toBe(false);
    expect(screen.getByTestId('location')).toHaveTextContent('/projects/p1');
    expect(screen.queryByText(NOTICE)).not.toBeInTheDocument();
  });

  it('goes back to the Simple home and keeps Simple mode', () => {
    renderAt('/integrations');
    fireEvent.click(screen.getByRole('button', { name: 'Back to Simple' }));

    expect(useSettingsStore.getState().simpleMode).toBe(true);
    expect(screen.getByTestId('location')).toHaveTextContent('/');
    expect(screen.queryByText(NOTICE)).not.toBeInTheDocument();
  });

  it('speaks German', () => {
    useI18nStore.setState({ locale: 'de' });
    renderAt('/factory');
    expect(screen.getByText('Diese Seite gehört zum Pro-Modus.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Zu Pro wechseln' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Zurück zu Einfach' })).toBeInTheDocument();
  });
});
