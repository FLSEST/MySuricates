import { useEffect, useState } from 'react';
import { Shield, ArrowRight, AlertTriangle } from 'lucide-react';
import { useSettingsStore } from '../../stores/settingsStore';
import { useT } from '../../i18n';

export function ApiKeyGuard({ children }: { children: React.ReactNode }) {
  const t = useT();
  const { apiKey, setApiKey } = useSettingsStore();
  const [securityEnabled, setSecurityEnabled] = useState<boolean | null>(null);
  const [inputKey, setInputKey] = useState('');
  const [validating, setValidating] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    fetch('/api/v1/system/auth-status')
      .then((res) => res.json())
      .then((data) => setSecurityEnabled(data.securityEnabled === true))
      .catch(() => setSecurityEnabled(false));
  }, []);

  // Still loading auth status
  if (securityEnabled === null) return null;

  // Security disabled or API key already set — pass through
  if (!securityEnabled || apiKey) return <>{children}</>;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!inputKey.trim()) return;

    setValidating(true);
    setError('');

    try {
      const res = await fetch('/api/v1/projects', {
        headers: { 'X-API-Key': inputKey.trim() },
      });

      if (res.ok) {
        setApiKey(inputKey.trim());
      } else {
        setError(t.security.invalidKey);
      }
    } catch {
      setError(t.security.connectionError);
    } finally {
      setValidating(false);
    }
  };

  return (
    <div className="min-h-screen bg-surface flex items-center justify-center p-4">
      <div className="w-full max-w-md">
        <div className="text-center mb-8">
          <div className="inline-flex items-center justify-center w-16 h-16 rounded-2xl bg-accent/10 mb-4">
            <Shield className="w-8 h-8 text-accent" />
          </div>
          <h1 className="text-2xl font-bold text-text">AutoPilot</h1>
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
                className="w-full px-3 py-2.5 rounded-lg bg-surface border border-border text-text placeholder:text-text-tertiary focus:outline-none focus:ring-2 focus:ring-accent/50 focus:border-accent font-mono text-sm"
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
