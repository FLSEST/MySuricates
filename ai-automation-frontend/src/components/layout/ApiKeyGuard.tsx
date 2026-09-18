import { useEffect, useState } from 'react';
import { ArrowRight, AlertTriangle } from 'lucide-react';
import { useSettingsStore } from '../../stores/settingsStore';
import { useT } from '../../i18n';
import { MeerkatIcon } from './Sidebar';
import { api, fetchAuthStatus, validateApiKey } from '../../api/client';

export function ApiKeyGuard({ children }: { children: React.ReactNode }) {
  const t = useT();
  const { apiKey, setApiKey } = useSettingsStore();
  const [securityEnabled, setSecurityEnabled] = useState<boolean | null>(null);
  // Becomes true once the stored key has been checked (or there is nothing to check).
  // Deliberately never goes back to false: the children mount once and stay mounted —
  // a guard that re-hides the app makes every child query refetch (d89b2cc1).
  const [storedKeyChecked, setStoredKeyChecked] = useState(false);
  const [inputKey, setInputKey] = useState('');
  const [validating, setValidating] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;
    fetchAuthStatus()
      .then((data) => { if (!cancelled) setSecurityEnabled(data.securityEnabled === true); })
      .catch((err: unknown) => {
        if (cancelled) return;
        // A rate-limited answer says nothing about the security setting — assuming "off"
        // here would hand out the app without a key. Stay undecided (render nothing) and
        // let a reload ask again once the backoff window is over (d89b2cc1).
        if ((err as { status?: number } | null)?.status === 429) return;
        setSecurityEnabled(false);
      });
    return () => { cancelled = true; };
  }, []);

  // Validate stored API key on load
  useEffect(() => {
    if (securityEnabled === null) return;
    if (!securityEnabled || !apiKey) {
      setStoredKeyChecked(true);
      return;
    }
    let cancelled = false;
    api.get('/projects')
      .catch((err: unknown) => {
        const status = (err as { status?: number } | null)?.status;
        // Only an explicit rejection clears the key — a network error or a 429 must not.
        if (status === 401 || status === 403) setApiKey('');
      })
      .finally(() => {
        if (!cancelled) setStoredKeyChecked(true);
      });
    return () => { cancelled = true; };
  }, [securityEnabled, apiKey, setApiKey]);

  // Still loading auth status or validating the stored key
  if (securityEnabled === null || !storedKeyChecked) return null;

  // Security disabled or API key already set (and validated) — pass through
  if (!securityEnabled || apiKey) return <>{children}</>;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!inputKey.trim()) return;

    setValidating(true);
    setError('');

    try {
      // Über den Client, damit der 429-Backoff auch für die Key-Prüfung gilt (b6feccbc).
      await validateApiKey(inputKey.trim());
      setApiKey(inputKey.trim());
    } catch (err: unknown) {
      const status = (err as { status?: number } | null)?.status;
      if (status === 429) setError(t.errors.rateLimited);
      else if (typeof status === 'number') setError(t.security.invalidKey);
      else setError(t.security.connectionError);
    } finally {
      setValidating(false);
    }
  };

  return (
    <div className="min-h-screen bg-surface flex items-center justify-center p-4">
      <div className="w-full max-w-md">
        <div className="text-center mb-8">
          <div className="inline-flex items-center justify-center w-64 h-48 rounded-2xl bg-accent/10 mb-4">
            <MeerkatIcon className="text-accent" size={220} />
          </div>
          <h1 className="text-2xl font-bold text-text">MySuricates</h1>
          <p className="text-text-secondary mt-2">{t.security.authRequired}</p>
        </div>

        <div className="bg-surface-raised rounded-xl border border-border p-6">
          <div className="flex items-start gap-3 mb-5 p-3 rounded-lg bg-amber-500/10 border border-amber-500/20">
            <AlertTriangle className="w-5 h-5 text-amber-500 shrink-0 mt-0.5" />
            <p className="text-sm text-text-secondary">{t.security.authHint}</p>
          </div>

          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-text mb-1.5">
                {t.settings.apiKey}
              </label>
              <input
                type="password"
                value={inputKey}
                onChange={(e) => setInputKey(e.target.value)}
                placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                className="w-full px-3 py-2.5 rounded-lg bg-surface border border-border text-text placeholder:text-text-tertiary focus:outline-hidden focus:ring-2 focus:ring-accent/50 focus:border-accent font-mono text-sm"
                autoFocus
              />
            </div>

            {error && (
              <p className="text-sm text-danger">{error}</p>
            )}

            <button
              type="submit"
              disabled={!inputKey.trim() || validating}
              className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg bg-accent text-text-inverse font-medium text-sm hover:bg-accent-hover transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {validating ? (
                <span className="inline-block w-4 h-4 border-2 border-text-inverse/30 border-t-text-inverse rounded-full animate-spin" />
              ) : (
                <>
                  {t.security.connect}
                  <ArrowRight className="w-4 h-4" />
                </>
              )}
            </button>
          </form>
        </div>
      </div>
    </div>
  );
}
