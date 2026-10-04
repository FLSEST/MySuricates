import { describe, it, expect, beforeEach } from 'vitest';
import { readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { useI18nStore } from '../../../i18n';
import { de } from '../../../i18n/de';
import deHints from '../../../data/hints.de.json';
import { HINTS, HINT_IDS, hintBlueprint } from '../../../data/hints';
import { useSettingsStore } from '../../../stores/settingsStore';
import { useToastStore } from '../../../stores/toastStore';
import { Hint } from '../Hint';

const copy = deHints['dashboard-views'];

beforeEach(() => {
  useI18nStore.setState({ locale: 'de' });
  useSettingsStore.setState({ hintsEnabled: true, dismissedHints: {} });
  useToastStore.setState({ toasts: [] });
});

describe('Hint', () => {
  it('shows title and points as a note', () => {
    render(<Hint id="dashboard-views" />);

    const note = screen.getByRole('note', { name: copy.title });
    for (const point of copy.points) expect(note.textContent).toContain(point);
  });

  it('can be dismissed and brought back in the same spot', () => {
    render(<Hint id="dashboard-views" />);

    fireEvent.click(screen.getByRole('button', { name: de.hints.understood }));

    expect(screen.queryByRole('note')).toBeNull();
    expect(useSettingsStore.getState().dismissedHints['dashboard-views']).toBe(HINTS['dashboard-views'].version);

    fireEvent.click(screen.getByRole('button', { name: `${de.hints.show}: ${copy.title}` }));

    expect(screen.getByRole('note', { name: copy.title })).toBeTruthy();
    expect(useSettingsStore.getState().dismissedHints['dashboard-views']).toBeUndefined();
  });

  it('comes back when its version was raised after dismissing', () => {
    useSettingsStore.setState({ dismissedHints: { 'dashboard-views': HINTS['dashboard-views'].version - 1 } });

    render(<Hint id="dashboard-views" />);

    expect(screen.getByRole('note', { name: copy.title })).toBeTruthy();
  });

  it('renders nothing at all while hints are switched off', () => {
    useSettingsStore.setState({ hintsEnabled: false });

    const { container } = render(<Hint id="dashboard-views" />);

    expect(container.innerHTML).toBe('');
  });

  it('switches all hints off from any hint and says where to switch them on again', () => {
    render(<Hint id="dashboard-views" />);

    fireEvent.click(screen.getByRole('button', { name: de.hints.hideAll }));

    expect(useSettingsStore.getState().hintsEnabled).toBe(false);
    expect(screen.queryByRole('note')).toBeNull();
    expect(useToastStore.getState().toasts.map((toast) => toast.message)).toContain(de.hints.hiddenToast);
  });

  it('resetHints shows every hint again and switches hints on', () => {
    useSettingsStore.setState({ hintsEnabled: false, dismissedHints: { 'dashboard-views': 1, 'run-steps': 1 } });

    useSettingsStore.getState().resetHints();

    expect(useSettingsStore.getState()).toMatchObject({ hintsEnabled: true, dismissedHints: {} });
  });

  // 093d2f11: a hint with a blueprint links to the create dialog with it chosen
  it('links its blueprint, opening the create dialog with it chosen', () => {
    render(<MemoryRouter><Hint id="schedules-timing" /></MemoryRouter>);

    const link = screen.getByRole('link', { name: de.hints.tryBlueprint });
    expect(link.getAttribute('href')).toBe('/workflows?new=1&template=blueprint-schedule');
  });

  it('links only blueprints that ship', () => {
    const dir = resolve(__dirname, '../../../../../ai-automation-backend/src/main/resources/templates/blueprints');
    const ids = readdirSync(dir).filter((f) => f.endsWith('.json')).map((f) => f.replace(/\.json$/, ''));
    expect(ids.length).toBeGreaterThan(0);
    for (const id of HINT_IDS) {
      const blueprint = hintBlueprint(id);
      if (blueprint) expect(ids, id).toContain(blueprint);
    }
  });
});
