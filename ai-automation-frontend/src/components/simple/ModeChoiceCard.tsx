import type { ReactNode } from 'react';
import { Sparkles, Zap } from 'lucide-react';
import { Button, Card, CardHeader } from '../ui';
import { useProjects } from '../../hooks/useProjects';
import { useSettingsStore } from '../../stores/settingsStore';
import { useT } from '../../i18n';

function ModeOption({ icon, label, description, onChoose }: {
  icon: ReactNode;
  label: string;
  description: string;
  onChoose: () => void;
}) {
  return (
    <div className="flex flex-col gap-3 rounded-xl border border-border bg-surface p-4">
      <p className="text-sm text-text-secondary flex-1">{description}</p>
      <Button variant="secondary" icon={icon} onClick={onChoose} className="self-start">
        {label}
      </Button>
    </div>
  );
}

/**
 * The mode offer of a fresh installation: shown on the Pro start page only while there is no
 * project and nobody has picked a mode yet. Both modes are offered as equals; the default stays Pro,
 * and an existing installation (it has projects) never sees the card.
 */
export function ModeChoiceCard() {
  const t = useT();
  const { data: projects } = useProjects();
  const modeChoiceMade = useSettingsStore((s) => s.modeChoiceMade);
  const chooseMode = useSettingsStore((s) => s.chooseMode);

  if (modeChoiceMade || projects === undefined || projects.length > 0) return null;

  return (
    <Card data-testid="mode-choice">
      <CardHeader title={t.simple.modeChoice.title} subtitle={t.simple.modeChoice.subtitle} />
      <div className="grid gap-3 sm:grid-cols-2">
        <ModeOption
          icon={<Zap className="w-4 h-4" />}
          label={t.simple.modeChoice.useSimple}
          description={t.simple.modeChoice.simpleDesc}
          onChoose={() => chooseMode(true)}
        />
        <ModeOption
          icon={<Sparkles className="w-4 h-4" />}
          label={t.simple.modeChoice.usePro}
          description={t.simple.modeChoice.proDesc}
          onChoose={() => chooseMode(false)}
        />
      </div>
    </Card>
  );
}
