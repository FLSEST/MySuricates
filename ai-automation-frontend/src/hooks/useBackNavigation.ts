import { useLocation, useNavigate } from 'react-router-dom';

/**
 * Back to the previous page of this app session, or to `fallback` when the page was the first one
 * opened (direct link, new tab, reload of a deep link).
 *
 * `window.history.length` is no substitute: it also counts pages of other sites in the same tab, so a
 * run page opened from a link left the app on "back". React Router marks the first location of the
 * session with the key `default`.
 */
export function useBackOr(fallback: string): () => void {
  const navigate = useNavigate();
  const location = useLocation();
  return () => {
    if (location.key !== 'default') navigate(-1);
    else navigate(fallback);
  };
}
