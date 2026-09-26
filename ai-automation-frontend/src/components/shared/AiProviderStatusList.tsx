import { Info, RefreshCw } from 'lucide-react';
import { Badge } from '../ui';
import { CopyButton } from './CopyButton';
import { useI18nStore, useT } from '../../i18n';
import { formatTimeOfDay } from '../../utils/localeFormat';
import type { AiProviderStatus, AiProviderStatusReport } from '../../hooks/useSystem';
import { orderProviders } from './aiProviderStatus';

const PROVIDER_NAMES: Record<string, string> = {
  CLAUDE: 'Claude',
  CODEX: 'Codex',
  PI: 'Pi',
};

interface AiProviderStatusListProps {
  report: AiProviderStatusReport;
  onRefresh: () => void;
  refreshing: boolean;
}

/**
 * Per AI provider: program found, login found, and otherwise the command that helps (f5a273ae).
 * The commands are the ones the installer shows (`ProviderSetup` in the backend). In a container they
 * would point to the wrong machine, so there the list names the state only and says how logins get in.
 */
export function AiProviderStatusList({ report, onRefresh, refreshing }: AiProviderStatusListProps) {
  const t = useT().aiProviderStatus;
  const locale = useI18nStore((s) => s.locale);
  const checkedAt = formatTimeOfDay(report.checkedAt, locale, { hour: '2-digit', minute: '2-digit' });

  return (
    <div className="space-y-3" data-testid="ai-provider-status">
      <ul className="space-y-3">
        {orderProviders(report.providers).map((provider) => (
          <ProviderRow key={provider.id} provider={provider} showCommands={!report.container} />
        ))}
      </ul>
      {report.container && (
        <p className="flex items-start gap-2 text-xs text-text-tertiary" data-testid="ai-provider-status-container">
          <Info className="w-3.5 h-3.5 mt-0.5 shrink-0" aria-hidden="true" />
          {t.containerNote}
        </p>
      )}
      <div className="flex flex-wrap items-center gap-2 text-xs text-text-tertiary">
        <span>{t.checkedAt.replace('{time}', checkedAt)}</span>
        <button
          type="button"
          onClick={onRefresh}
          disabled={refreshing}
          className="inline-flex items-center gap-1 rounded-sm hover:text-text disabled:opacity-50 transition-colors"
        >
          <RefreshCw className={`w-3.5 h-3.5 ${refreshing ? 'animate-spin' : ''}`} aria-hidden="true" />
          {t.recheck}
        </button>
      </div>
    </div>
  );
}

function ProviderRow({ provider, showCommands }: { provider: AiProviderStatus; showCommands: boolean }) {
  const t = useT().aiProviderStatus;
  const ready = provider.state === 'READY';
  // Only the default provider's gap is a problem for sure; the others are choices, not errors.
  const dot = ready ? 'bg-success' : provider.defaultProvider ? 'bg-warning' : 'bg-text-tertiary';
  const commands: { label: string; text: string; note?: string }[] = [];
  if (showCommands && provider.state === 'NOT_INSTALLED') commands.push({ label: t.install, text: provider.install });
  if (showCommands && !provider.loggedIn) {
    commands.push({ label: t.login, text: provider.login, note: loginNoteText(t.loginNotes, provider.loginNote) });
  }
  const [sourceBefore, sourceAfter = ''] =
    (provider.loginVia === 'ENVIRONMENT' ? t.loginViaEnvironment : t.loginViaFile).split('{source}');

  return (
    <li data-testid={`ai-provider-${provider.id}`} data-state={provider.state} className="text-sm">
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
        <span className={`w-2 h-2 rounded-full shrink-0 ${dot}`} aria-hidden="true" />
        <span className="font-medium text-text">{PROVIDER_NAMES[provider.id] ?? provider.id}</span>
        {provider.defaultProvider && <Badge variant="accent">{t.defaultBadge}</Badge>}
        <span className={ready ? 'text-success' : 'text-text-secondary'}>{t.states[provider.state]}</span>
        {ready && provider.loginSource && (
          <span className="text-xs text-text-tertiary">
            {sourceBefore}
            <code className="font-mono">{provider.loginSource}</code>
            {sourceAfter}
          </span>
        )}
      </div>
      {commands.length > 0 && (
        <div className="mt-1.5 ml-4 space-y-1">
          {commands.map((command) => (
            <CommandLine key={command.label} label={command.label} text={command.text} note={command.note} />
          ))}
        </div>
      )}
    </li>
  );
}

/**
 * The note after the login command in the interface language (eab43a8b). The catalog sends a key; an
 * unknown one (a newer backend) shows nothing rather than the bare key — the command stays usable.
 */
function loginNoteText(notes: Record<string, string>, key: string | null | undefined): string | undefined {
  return key ? notes[key] : undefined;
}

function CommandLine({ label, text, note }: { label: string; text: string; note?: string }) {
  return (
    <div className="flex items-start gap-2 text-xs">
      <span className="w-20 shrink-0 pt-1 text-text-tertiary">{label}</span>
      <div className="min-w-0 flex-1 space-y-1">
        <div className="flex items-start gap-2 rounded-md border border-border bg-surface px-2 py-1 font-mono text-text">
          {/* pre-wrap keeps the catalog's spacing; break-words wraps at spaces and splits only a long URL */}
          <code className="min-w-0 flex-1 whitespace-pre-wrap break-words">{text}</code>
          <CopyButton text={text} className="shrink-0 p-0.5 rounded-sm text-text-tertiary hover:text-accent transition-colors" />
        </div>
        {note && <p className="text-text-tertiary" data-testid="ai-provider-login-note">{note}</p>}
      </div>
    </div>
  );
}
