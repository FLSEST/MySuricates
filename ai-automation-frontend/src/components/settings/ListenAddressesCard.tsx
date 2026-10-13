import type { ReactNode } from 'react';
import { Network } from 'lucide-react';
import { Card, CardHeader } from '../ui';
import { useT } from '../../i18n';
import { useListenAddresses } from '../../hooks/useSystem';
import { listenUrl, scopeOfAddress } from '../../utils/listenScope';
import type { ListenReport, ListenScope } from '../../types';

const SCOPE_CLASSES: Record<ListenScope, string> = {
  LOCAL: 'bg-success-subtle text-success',
  NETWORK: 'bg-warning-subtle text-warning',
  UNKNOWN: 'bg-surface-raised text-text-secondary',
};

/**
 * Who can reach this instance (TODO 1af11dcc): backend and frontend with address, port and whether that is
 * this machine only or the network, plus the switch that changes it. Secure by default means 127.0.0.1;
 * `localhost` is the same machine, which the last row makes visible for the address the page was opened under.
 */
export function ListenAddressesCard() {
  const t = useT();
  const s = t.settings;
  const { data: report, isError } = useListenAddresses();
  const servedByVite = import.meta.env.DEV && !report?.servesFrontend;
  const location = window.location;

  const scopeLabel: Record<ListenScope, string> = {
    LOCAL: s.listenScopeLocal, NETWORK: s.listenScopeNetwork, UNKNOWN: s.listenScopeUnknown,
  };
  const badge = (scope: ListenScope) => (
    <span className={`px-2 py-0.5 rounded-full text-[11px] whitespace-nowrap ${SCOPE_CLASSES[scope]}`} data-scope={scope}>
      {scopeLabel[scope]}
    </span>
  );

  return (
    <Card>
      <CardHeader title={s.listenTitle} />
      <div className="space-y-3 text-sm">
        <p className="text-text-secondary text-xs">{s.listenIntro}</p>
        {isError && <p className="text-danger text-xs">{s.listenLoadFailed}</p>}
        {report && (
          <Row label={s.listenBackend} url={backendUrl(report, s.listenScopeUnknown)} badge={badge(report.backend.scope)}>
            {report.backend.published && report.backend.scope !== 'UNKNOWN' && <p>{s.listenPublished}</p>}
            {report.backend.scope === 'UNKNOWN' && <p>{s.listenUnknownHint}</p>}
            <SwitchHint report={report} />
          </Row>
        )}
        {report && report.servesFrontend && (
          <Row label={s.listenFrontend} url={backendUrl(report, s.listenScopeUnknown)} badge={badge(report.backend.scope)}>
            <p>{s.listenSameAsBackend}</p>
          </Row>
        )}
        {report && servedByVite && (
          <Row
            label={s.listenFrontend}
            url={listenUrl(location.protocol.replace(':', ''), __DEV_BIND__, location.port)}
            badge={badge(scopeOfAddress(__DEV_BIND__))}
          >
            <p>{s.listenServedByVite}</p>
          </Row>
        )}
        {report && !report.servesFrontend && !servedByVite && (
          <Row label={s.listenFrontend} url={location.origin} badge={badge('UNKNOWN')}>
            <p>{s.listenOtherServer}</p>
          </Row>
        )}
        <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1 pt-3 border-t border-border">
          <span className="text-text-secondary flex items-center gap-2">
            <Network className="w-4 h-4" />
            {s.listenConnectedVia}
          </span>
          <span className="flex flex-wrap items-center gap-2 min-w-0">
            <span className="font-mono text-text break-all">{location.origin}</span>
            {scopeOfAddress(location.hostname) === 'LOCAL' && badge('LOCAL')}
          </span>
        </div>
      </div>
    </Card>
  );
}

function backendUrl(report: ListenReport, unknown: string): string {
  const b = report.backend;
  return b.scope === 'UNKNOWN' ? unknown : listenUrl(b.scheme, b.address, b.port);
}

function Row({ label, url, badge, children }: { label: string; url: string; badge: ReactNode; children?: ReactNode }) {
  return (
    <div className="space-y-1">
      {/* Wraps instead of truncating on a phone: the address is the information */}
      <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1">
        <span className="text-text-secondary">{label}</span>
        <span className="flex flex-wrap items-center gap-2 min-w-0">
          <span className="font-mono text-text break-all">{url}</span>
          {badge}
        </span>
      </div>
      <div className="text-xs text-text-tertiary space-y-0.5 break-words">{children}</div>
    </div>
  );
}

function SwitchHint({ report }: { report: ListenReport }) {
  const s = useT().settings;
  if (report.runtime === 'tryout') return <p>{s.listenTryoutFixed}</p>;
  if (!report.bindSwitch) return null;
  const text = report.switchSource
    ? s.listenSwitchIn.replace('{source}', report.switchSource)
    : s.listenSwitch;
  return <p>{text.replace('{switch}', `${report.bindSwitch}=…`)}</p>;
}
