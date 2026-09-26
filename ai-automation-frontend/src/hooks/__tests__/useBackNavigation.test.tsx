import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { createBrowserRouter, Link, Navigate, RouterProvider } from 'react-router-dom';
import { useBackOr } from '../useBackNavigation';

function RunPage() {
  const back = useBackOr('/workflows/w1');
  return <button onClick={back}>back</button>;
}

/** The real browser router on jsdom's history, opened at `path` like a fresh tab or an outside link. */
function openAt(path: string) {
  // No router state on the entry: as if the URL came from outside the app
  window.history.replaceState(null, '', path);
  const router = createBrowserRouter([
    { path: '/workflows/w1/run/r1', element: <RunPage /> },
    { path: '/workflows/w1', element: <p>workflow detail</p> },
    // Like App.tsx RunRedirect: /runs/:runId forwards to the run page with replace
    { path: '/runs/r1', element: <Navigate to="/workflows/w1/run/r1" replace /> },
    { path: '/runs', element: <Link to="/workflows/w1/run/r1">open run</Link> },
  ]);
  render(<RouterProvider router={router} />);
}

describe('useBackOr', () => {
  it('goes back to the previous page of the app', async () => {
    openAt('/runs');
    fireEvent.click(screen.getByText('open run'));
    fireEvent.click(await screen.findByRole('button', { name: 'back' }));
    expect(await screen.findByText('open run')).toBeInTheDocument();
  });

  it('goes to the fallback when the page was opened directly', async () => {
    openAt('/workflows/w1/run/r1');
    fireEvent.click(await screen.findByRole('button', { name: 'back' }));
    expect(await screen.findByText('workflow detail')).toBeInTheDocument();
  });

  // Review of 0b552625: the redirect gives the first page a new location key, so a key check took
  // this for in-app navigation and went back out of the app.
  it('goes to the fallback when a redirect with replace led to the directly opened page', async () => {
    openAt('/runs/r1');
    fireEvent.click(await screen.findByRole('button', { name: 'back' }));
    expect(await screen.findByText('workflow detail')).toBeInTheDocument();
  });

  // Regression: the run page checked window.history.length > 1 and left the app when the tab had
  // visited other pages before the direct link (seen in Cypress, where each test starts after about:blank).
  it('stays in the app when the tab has foreign history before the direct link', async () => {
    window.history.pushState(null, '', '/somewhere-else');
    expect(window.history.length).toBeGreaterThan(1);
    openAt('/workflows/w1/run/r1');
    fireEvent.click(await screen.findByRole('button', { name: 'back' }));
    expect(await screen.findByText('workflow detail')).toBeInTheDocument();
  });
});
