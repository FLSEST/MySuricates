import { useNavigate } from 'react-router-dom';

/**
 * Back to the previous page of this app session, or to `fallback` when the page was the first one
 * opened (direct link, new tab, reload of a deep link).
 *
 * `window.history.length` is no substitute: it also counts pages of other sites in the same tab, so a
 * run page opened from a link left the app on "back". Neither is `location.key`: a redirect with
 * `replace` (e.g. `/runs/:runId` → run page) gives the first page a new key. The browser router keeps
 * its own position in `history.state.idx`, which stays 0 until the app itself pushes an entry.
 */
export function useBackOr(fallback: string): () => void {
  const navigate = useNavigate();
  return () => {
    const idx = (window.history.state as { idx?: number } | null)?.idx ?? 0;
    if (idx > 0) navigate(-1);
    else navigate(fallback);
  };
}
