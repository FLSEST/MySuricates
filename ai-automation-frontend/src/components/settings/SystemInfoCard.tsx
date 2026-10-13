import { Card, CardHeader } from '../ui';
import { StatusBadge } from '../shared/StatusBadge';
import { useI18nStore, useT } from '../../i18n';
import { formatTimeOfDay } from '../../utils/localeFormat';
import { useListenAddresses, useSystemHealth, useSystemInfo } from '../../hooks/useSystem';
import { Server } from 'lucide-react';

export function SystemInfoCard() {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const { data: health } = useSystemHealth();
  const { data: sysInfo } = useSystemInfo();
  // How the instance runs (host, container, tryout) — from the keyed /system/listen, not the public /info (1af11dcc)
  const { data: listen } = useListenAddresses();
  const s = t.settings;
  const runsAs = { host: s.systemRunsOnHost, container: s.systemRunsInContainer, tryout: s.systemRunsInTryout };
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
              // Primitives only: `SystemInfo` grows elsewhere, and a nested field as
              // React child crashes the whole page ("Objects are not valid as a React
              // child") — that happened when `rateLimit` was added as an object (0d1d27a1). The
              // rate-limit values are editable in the execution card anyway.
              .filter(([, value]) => value !== null && typeof value !== 'object')
              .map(([key, value]) => (
                <div key={key} className="flex justify-between">
                  {/* A field the UI does not know yet keeps its key as the label */}
                  <span className={`text-text-secondary ${fieldLabels[key] ? '' : 'capitalize'}`}>{fieldLabels[key] ?? key}</span>
                  <span className="text-text font-mono">{String(value)}</span>
                </div>
              ))}
            {listen && runsAs[listen.runtime] && (
              <div className="flex justify-between">
                <span className="text-text-secondary">{s.systemFieldRunsAs}</span>
                <span className="text-text">{runsAs[listen.runtime]}</span>
              </div>
            )}
          </div>
        )}
        {health && (
          <div className="space-y-2 text-sm pt-3 border-t border-border mt-3">
            <div className="flex justify-between">
              <span className="text-text-secondary">Docker</span>
              {/* Not set up is neutral, not an error (a0e85182) */}
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
