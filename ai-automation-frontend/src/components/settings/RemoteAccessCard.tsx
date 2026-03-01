import { Card, CardHeader, Button } from '../ui';
import { StatusBadge } from '../shared/StatusBadge';
import { useT } from '../../i18n';
import { useRemoteAccessStatus, useEnableRemoteAccess, useDisableRemoteAccess } from '../../hooks/useSystem';
import { Wifi } from 'lucide-react';

export function RemoteAccessCard() {
  const t = useT();
  const { data: remoteAccess } = useRemoteAccessStatus();
  const enableRemote = useEnableRemoteAccess();
  const disableRemote = useDisableRemoteAccess();

  return (
    <Card>
      <CardHeader title={t.settings.remoteAccess} />
      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <Wifi className="w-5 h-5 text-text-secondary" />
            <div>
              <p className="text-sm font-medium text-text">{t.settings.tailscale}</p>
              <p className="text-xs text-text-tertiary">
                {remoteAccess?.enabled ? `IP: ${remoteAccess.ip}` : t.settings.tailscaleNotConnected}
              </p>
            </div>
          </div>
          <div className="flex items-center gap-3">
            <StatusBadge status={remoteAccess?.enabled ? 'RUNNING' : 'STOPPED'} />
            {remoteAccess?.enabled ? (
              <Button
                variant="danger"
                size="sm"
                onClick={() => disableRemote.mutate()}
                loading={disableRemote.isPending}
              >
                {t.settings.tailscaleDisable}
              </Button>
            ) : (
              <Button
                size="sm"
                onClick={() => enableRemote.mutate()}
                loading={enableRemote.isPending}
              >
                {t.settings.tailscaleEnable}
              </Button>
            )}
          </div>
        </div>
        {remoteAccess?.hostname && (
          <p className="text-xs text-text-tertiary font-mono pl-8">
            Hostname: {remoteAccess.hostname}
          </p>
        )}
      </div>
    </Card>
  );
}
