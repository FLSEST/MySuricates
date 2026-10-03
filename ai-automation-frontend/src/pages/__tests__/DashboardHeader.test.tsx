import { afterEach, describe, it, expect, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import { renderWithProviders } from '../../test/utils';
import { setupDashboardPageTests } from '../../test/dashboardPage';
import { useI18nStore } from '../../i18n';
import { useDashboardLayoutStore } from '../../stores/dashboardLayoutStore';
import { Dashboard } from '../Dashboard';

setupDashboardPageTests();

afterEach(() => {
  vi.useRealTimers();
});

/** Thursday, 24 September 2026, 14:05:33 local time; only Date is faked, timers keep running. */
function freezeClock() {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(2026, 8, 24, 14, 5, 33));
}

async function renderDashboard() {
  renderWithProviders(<Dashboard />);
  await screen.findByRole('heading', { level: 1 });
  return screen.getByTestId('dashboard-header');
}

// Renders the whole page; see Dashboard.test.tsx for the timeout.
describe('Dashboard header', { timeout: 20_000 }, () => {
  // The clock read „Donnerstag, 24.09.2026“ in the English UI of a German browser (e86f6544).
  it('shows time and date in the English UI in English', async () => {
    freezeClock();
    const header = await renderDashboard();

    const clock = within(header).getByTestId('live-clock');
    // 24 h in English too (571524a5)
    expect(clock).toHaveTextContent('14:05:33');
    expect(clock.textContent).not.toMatch(/[AP]M/);
    expect(clock).toHaveTextContent('Thursday, 09/24/2026');
  });

  it('shows time and date in the German UI in German', async () => {
    useI18nStore.setState({ locale: 'de' });
    freezeClock();
    const header = await renderDashboard();

    const clock = within(header).getByTestId('live-clock');
    expect(clock).toHaveTextContent('14:05:33');
    expect(clock).toHaveTextContent('Donnerstag, 24.09.2026');
  });

  // jsdom has no layout: the widths were measured in the browser (320–1920 px, DE/EN, one and three
  // views, edit mode, sidebar open and closed — no element beyond the page, the title never covered).
  // Here: the classes that measurement rests on. At 640 px the one-row layout used to start by the
  // window width (sm:) although title, buttons and clock did not fit in the page yet.
  it('puts everything in one row only when the page is wide enough, not the window', async () => {
    useDashboardLayoutStore.setState({
      activeViewId: 'default',
      views: [{ id: 'default', name: '', widgets: [] }, { id: 'week', name: 'Week', widgets: [] }],
    });
    const header = await renderDashboard();

    expect(header).toHaveClass('@container');
    const row = header.firstElementChild as HTMLElement;
    expect(row).toHaveClass('flex-wrap', '@4xl:flex-nowrap');
    expect(row.className).not.toMatch(/(^|\s)(sm|md|lg|xl):flex-nowrap/);

    const [title, tabs, actions] = Array.from(row.children) as HTMLElement[];
    // narrow: the title shrinks, but never below the width of "Dashboard"
    expect(title).toHaveClass('min-w-32', 'basis-0', 'flex-1');
    expect(title).toContainElement(screen.getByRole('heading', { level: 1 }));
    // the tabs take a line of their own until the one-row layout starts
    expect(tabs).toContainElement(screen.getByRole('tablist'));
    expect(tabs).toHaveClass('order-last', 'w-full', '@4xl:order-none', '@4xl:flex-1');
    // buttons and clock never shrink; on a line of their own they stay on the right
    expect(actions).toContainElement(within(header).getByTestId('live-clock'));
    expect(actions).toHaveClass('shrink-0', 'ml-auto');
  });
});
