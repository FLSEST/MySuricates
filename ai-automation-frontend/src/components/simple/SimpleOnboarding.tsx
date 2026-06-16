import { X } from 'lucide-react';
import { useT } from '../../i18n';
import { useSettingsStore } from '../../stores/settingsStore';

/** One-time intro banner shown on the Simple home until dismissed. */
export function SimpleOnboarding() {
  const t = useT();
  const { simpleOnboardingSeen, setSimpleOnboardingSeen } = useSettingsStore();
  if (simpleOnboardingSeen) return null;

  return (
    <div className="relative bg-surface border border-accent/30 rounded-xl px-4 py-3 mb-6">
      <button
        aria-label="dismiss"
        className="absolute top-3 right-3 text-text-tertiary hover:text-text"
        onClick={() => setSimpleOnboardingSeen(true)}
      >
        <X className="w-4 h-4" />
      </button>
      <p className="text-sm font-medium text-text">{t.simple.onboarding.title}</p>
      <p className="text-xs text-text-secondary mt-1 pr-6">{t.simple.onboarding.body}</p>
      <button
        className="text-xs text-accent hover:underline mt-2"
        onClick={() => setSimpleOnboardingSeen(true)}
      >
        {t.simple.onboarding.gotIt}
      </button>
    </div>
  );
}
