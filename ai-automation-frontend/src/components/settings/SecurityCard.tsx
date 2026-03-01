import { Card, CardHeader, Input } from '../ui';
import { useSettingsStore } from '../../stores/settingsStore';
import { useT } from '../../i18n';
import { Shield } from 'lucide-react';

export function SecurityCard() {
  const t = useT();
  const { apiKey, setApiKey } = useSettingsStore();

  return (
    <Card>
      <CardHeader title={t.settings.security} />
      <div className="space-y-4">
        <div className="flex items-center gap-3">
          <Shield className="w-5 h-5 text-text-secondary" />
          <div className="flex-1">
            <Input
              label={t.settings.apiKey}
              hint={t.settings.apiKeyHint}
              type="password"
              value={apiKey}
              onChange={(e) => setApiKey(e.target.value)}
              placeholder="sk-..."
            />
          </div>
        </div>
      </div>
    </Card>
  );
}
