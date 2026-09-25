import { Card, CardHeader } from '../ui';
import { StatusBadge } from '../shared/StatusBadge';
import { useI18nStore, useT } from '../../i18n';
import { formatTimeOfDay } from '../../utils/localeFormat';
import { useSystemHealth, useSystemInfo } from '../../hooks/useSystem';
import { Server } from 'lucide-react';

export function SystemInfoCard() {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const { data: health } = useSystemHealth();
  const { data: sysInfo } = useSystemInfo();
  const s = t.settings;
  const fieldLabels: Record<string, string> = {
    name: s.systemFieldName, version: s.systemFieldVersion, buildType: s.systemFieldBuildType,
    buildTimestamp: s.systemFieldBuildTimestamp, java: s.systemFieldJava, runtimeMode: s.systemFieldRuntimeMode,
    tailscale: s.systemFieldTailscale, environment: s.systemFieldEnvironment,
  };

  return (
    <Card>
      <CardHeader title={t.settings.system} />
      <div className="space-y-3">
        <div className="flex items-center gap-3 mb-4">
          <Server className="w-5 h-5 text-text-secondary" />
          {health && <StatusBadge status={health.status === 'UP' ? 'RUNNING' : 'ERROR'} label={health.status} />}
        </div>
        {sysInfo && (
          <div className="space-y-2 text-sm">
            {Object.entries(sysInfo)
              // Nur Primitive: `SystemInfo` waechst anderswo, und ein verschachteltes Feld als
              // React-Kind laesst die ganze Seite abstuerzen ("Objects are not valid as a React
              // child") — so geschehen, als `rateLimit` als Objekt dazukam (0d1d27a1). Die
              // Rate-Limit-Werte stehen ohnehin einstellbar in der Ausfuehrungs-Karte.
              .filter(([, value]) => value !== null && typeof value !== 'object')
              .map(([key, value]) => (
                <div key={key} className="flex justify-between">
                  {/* A field the UI does not know yet keeps its key as the label */}
                  <span className={`text-text-secondary ${fieldLabels[key] ? '' : 'capitalize'}`}>{fieldLabels[key] ?? key}</span>
                  <span className="text-text font-mono">{String(value)}</span>
                </div>
              ))}
          </div>
        )}
        {health && (
          <div className="space-y-2 text-sm pt-3 border-t border-border mt-3">
            <div className="flex justify-between">
              <span className="text-text-secondary">Docker</span>
              {/* Nicht eingerichtet ist neutral, kein Fehler (a0e85182) */}
              <StatusBadge
                status={health.docker === 'CONNECTED' ? 'RUNNING' : health.docker === 'NOT_CONFIGURED' ? 'STOPPED' : 'ERROR'}
                label={health.docker === 'NOT_CONFIGURED' ? t.health.dockerNotConfigured : health.docker}
              />
            </div>
            <div className="flex justify-between">
              <span className="text-text-secondary">{t.settings.systemLastCheck}</span>
              <span className="text-text-tertiary text-xs">{formatTimeOfDay(health.timestamp, locale)}</span>
            </div>
          </div>
        )}
      </div>
    </Card>
  );
}
