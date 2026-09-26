import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { useBackOr } from '../useBackNavigation';

function RunPage() {
  const back = useBackOr('/workflows/w1');
  return <button onClick={back}>back</button>;
}

function renderAt(entries: string[]) {
  render(
    <MemoryRouter initialEntries={entries} initialIndex={entries.length - 1}>
      <Routes>
        <Route path="/workflows/w1/run/r1" element={<RunPage />} />
        <Route path="/workflows/w1" element={<p>workflow detail</p>} />
        <Route path="/runs" element={<p>run list</p>} />
      </Routes>
    </MemoryRouter>,
  );
  fireEvent.click(screen.getByRole('button', { name: 'back' }));
}

describe('useBackOr', () => {
  it('goes back to the previous page of the app', () => {
    renderAt(['/runs', '/workflows/w1/run/r1']);
    expect(screen.getByText('run list')).toBeInTheDocument();
  });

  it('goes to the fallback when the page was opened directly', () => {
    renderAt(['/workflows/w1/run/r1']);
    expect(screen.getByText('workflow detail')).toBeInTheDocument();
  });

  // Regression: the run page checked window.history.length > 1 and left the app when the tab had
  // visited other pages before the direct link (seen in Cypress, where each test starts after about:blank).
  it('stays in the app when the tab has foreign history before the direct link', () => {
    window.history.pushState({}, '', '/somewhere-else');
    expect(window.history.length).toBeGreaterThan(1);
    renderAt(['/workflows/w1/run/r1']);
    expect(screen.getByText('workflow detail')).toBeInTheDocument();
  });
});
