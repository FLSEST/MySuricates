import { useLocation, useNavigate } from 'react-router-dom';
import { Sparkles } from 'lucide-react';
import { useT } from '../../i18n';
import { useSettingsStore } from '../../stores/settingsStore';
import { simpleRouteKind } from '../../utils/simpleRoutes';
import { Button } from '../ui';

/**
 * Slim bar below the header when Simple mode lands on a Pro page — so the mode change is
 * visible instead of silent. Offers switching to Pro (stay here) or going back to the Simple home.
 */
export function ProPageNotice() {
  const t = useT();
  const location = useLocation();
  const navigate = useNavigate();
  const simpleMode = useSettingsStore((s) => s.simpleMode);
  const setSimpleMode = useSettingsStore((s) => s.setSimpleMode);

  if (!simpleMode || simpleRouteKind(location.pathname) !== 'pro') return null;

  return (
    <div
      role="status"
      className="flex flex-wrap items-center justify-between gap-2 border-b border-border bg-info-subtle px-4 py-2 text-sm text-text"
    >
      <span className="flex items-center gap-2 min-w-0">
        <Sparkles className="w-4 h-4 shrink-0 text-info" aria-hidden />
        {t.simple.handoff.pageNotice}
      </span>
      <div className="flex items-center gap-2">
        <Button variant="secondary" size="sm" onClick={() => setSimpleMode(false)}>
          {t.simple.handoff.switchToPro}
        </Button>
        <Button variant="ghost" size="sm" onClick={() => navigate('/')}>
          {t.simple.handoff.backToSimple}
        </Button>
      </div>
    </div>
  );
}
