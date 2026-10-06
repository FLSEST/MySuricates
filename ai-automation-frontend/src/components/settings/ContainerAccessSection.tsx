import type { ReactNode } from 'react';
import { Info, ShieldAlert } from 'lucide-react';
import { Badge } from '../ui';
import { ConfigSnippet } from './ConfigSnippet';
import { useContainerAccess, type ContainerAccess } from '../../hooks/useSystemCapabilities';
import { useT } from '../../i18n';

const PROVIDER_NAMES: Record<ContainerAccess['providers'][number]['id'], string> = {
  claude: 'Claude',
  codex: 'Codex',
  pi: 'Pi',
};

/**
 * What this instance got from the host for the AI CLIs, and whether it holds the Docker socket —
 * read-only (262948a4). The switches live in the start file of the instance: scripts/lib/host-mounts.sh
 * turns them into mounts before the container starts, so neither a workflow nor anyone with the API
 * key can widen them from inside. This section shows them and names where to change them.
 */
export function ContainerAccessSection() {
  const t = useT();
  const { data: access } = useContainerAccess();
  if (!access) return null;
  const c = t.startupConfig.containerAccess;
  const container = access.runtime === 'container';
  const [changeInBefore, changeInAfter = ''] = c.changeIn.split('{source}');

  return (
    <div data-testid="container-access">
      <h4 className="text-sm font-semibold text-text mb-2">{c.title}</h4>
      <div className="space-y-2 text-sm">
        <Row label={c.runtimeLabel} testId="container-access-runtime">
          <Badge variant={container ? 'accent' : 'default'}>{container ? c.runtimeContainer : c.runtimeHost}</Badge>
        </Row>
        {container && access.aiFromHost && (
          <Row label={c.fromHostLabel} testId="container-access-mode">
            <span className="text-text">{c.modes[access.aiFromHost]}</span>
          </Row>
        )}
        <Row label={c.socketLabel} testId="container-access-socket">
          <span className="text-text">{access.dockerSocket ? t.modules.enabled : t.modules.disabled}</span>
        </Row>
        <ul className="space-y-1.5 pt-1">
          {access.providers.map((p) => (
            <ProviderRow key={p.id} provider={p} />
          ))}
        </ul>
      </div>

      <div className="mt-3 space-y-2">
        {container && access.dockerSocket && (
          <Note risk testId="container-access-socket-risk">{c.socketRisk}</Note>
        )}
        {container && access.aiFromHost === 'all' && (
          <Note risk testId="container-access-all-risk">{c.allRisk}</Note>
        )}
        {!container && <Note testId="container-access-host-note">{c.hostNote}</Note>}
        {container && access.switchPrefix && (
          <div className="max-w-md space-y-1.5">
            {/* The location is its own row: the snippet's label is set in capitals, and a
                path in capitals would be a different path */}
            <p className="text-xs text-text-secondary" data-testid="container-access-change-in">
              {changeInBefore}
              <code className="font-mono text-text break-words">{access.switchSource}</code>
              {changeInAfter}
            </p>
            <ConfigSnippet label={c.linesLabel} text={switchLines(access)} />
          </div>
        )}
      </div>
    </div>
  );
}

/** The three lines as they would stand in the start file, with the values this instance runs with. */
function switchLines(access: ContainerAccess): string {
  const prefix = access.switchPrefix ?? '';
  const selected = access.providers.filter((p) => p.selected).map((p) => p.id).join(',');
  return [
    `${prefix}AI_FROM_HOST=${access.aiFromHost ?? 'login'}`,
    `${prefix}AI_PROVIDERS=${selected}`,
    `${prefix}DOCKER_SOCKET=${access.dockerSocket ? 1 : 0}`,
  ].join('\n');
}

function ProviderRow({ provider }: { provider: ContainerAccess['providers'][number] }) {
  const c = useT().startupConfig.containerAccess;
  const [state, dot] = !provider.selected
    ? [c.notSelected, 'bg-text-tertiary']
    : !provider.program
      ? [c.noProgram, 'bg-warning']
      : provider.login === false
        ? [c.noLogin, 'bg-warning']
        : [provider.login === null ? c.programFound : c.ready, 'bg-success'];
  return (
    <li data-testid={`container-access-provider-${provider.id}`} className="flex items-center gap-2 text-sm">
      <span className={`w-2 h-2 rounded-full shrink-0 ${dot}`} aria-hidden="true" />
      <span className="text-text">{PROVIDER_NAMES[provider.id]}</span>
      <span className="text-xs text-text-tertiary">{state}</span>
    </li>
  );
}

function Row({ label, testId, children }: { label: string; testId: string; children: ReactNode }) {
  return (
    <div className="flex flex-wrap items-center gap-2" data-testid={testId}>
      <span className="text-text-secondary">{label}:</span>
      {children}
    </div>
  );
}

function Note({ risk = false, testId, children }: { risk?: boolean; testId: string; children: ReactNode }) {
  return risk ? (
    <p className="flex items-start gap-2 rounded-lg bg-warning/10 px-3 py-2 text-xs text-warning" data-testid={testId}>
      <ShieldAlert className="w-3.5 h-3.5 mt-0.5 shrink-0" />
      {children}
    </p>
  ) : (
    <p className="flex items-start gap-2 text-xs text-text-tertiary" data-testid={testId}>
      <Info className="w-3.5 h-3.5 mt-0.5 shrink-0" />
      {children}
    </p>
  );
}
