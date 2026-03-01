import { Card, CardHeader } from '../ui';
import { StatusBadge } from '../shared/StatusBadge';
import { useT } from '../../i18n';
import { useSystemHealth, useSystemInfo } from '../../hooks/useSystem';
import { Server } from 'lucide-react';

export function SystemInfoCard() {
  const t = useT();
  const { data: health } = useSystemHealth();
  const { data: sysInfo } = useSystemInfo();

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
            {Object.entries(sysInfo).map(([key, value]) => (
              <div key={key} className="flex justify-between">
                <span className="text-text-secondary capitalize">{key}</span>
                <span className="text-text font-mono">{value}</span>
              </div>
            ))}
          </div>
        )}
        {health && (
          <div className="space-y-2 text-sm pt-3 border-t border-border mt-3">
            <div className="flex justify-between">
              <span className="text-text-secondary">Docker</span>
              <StatusBadge status={health.docker === 'CONNECTED' ? 'RUNNING' : 'ERROR'} label={health.docker} />
            </div>
            <div className="flex justify-between">
              <span className="text-text-secondary">Last Check</span>
              <span className="text-text-tertiary text-xs">{new Date(health.timestamp).toLocaleTimeString()}</span>
            </div>
          </div>
        )}
      </div>
    </Card>
  );
}
