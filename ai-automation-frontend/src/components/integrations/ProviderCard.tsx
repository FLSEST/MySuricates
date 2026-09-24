import { Plug, Calendar, KanbanSquare, TicketCheck, Mail, ExternalLink, Trash2, CheckCircle2, XCircle, AlertTriangle, Settings2, ListTodo, GitMerge, GitPullRequest, CalendarDays, Rss, Bell, CloudSun, SquareCheck, Hash, Send, Globe } from 'lucide-react';
import { Card, Button, Badge } from '../ui';
import { useT } from '../../i18n';
import { useProviderCopy } from '../../hooks/useProviderCopy';
import { VerificationBadge } from './IntegrationVerification';
import type { IntegrationProviderInfo } from '../../types';

const ICON_MAP: Record<string, typeof Calendar> = {
  Calendar,
  CalendarDays,
  Rss,
  KanbanSquare,
  TicketCheck,
  Mail,
  ListTodo,
  GitMerge,
  GitPullRequest,
  Bell,
  CloudSun,
  CheckSquare: SquareCheck,
  Hash,
  Send,
  Globe,
};

function ProviderIcon({ icon }: { icon: string }) {
  const Icon = ICON_MAP[icon] || Plug;
  return <Icon className="w-8 h-8" />;
}

function ProviderStatusBadge({ provider }: { provider: IntegrationProviderInfo }) {
  const t = useT();
  if (provider.status === 'CONNECTED') {
    return (
      <Badge variant="success">
        <CheckCircle2 className="w-3 h-3 mr-1" />
        {t.integrations.statusConnected}
      </Badge>
    );
  }
  if (provider.status === 'EXPIRED') {
    return (
      <Badge variant="warning">
        <AlertTriangle className="w-3 h-3 mr-1" />
        {t.integrations.statusExpired}
      </Badge>
    );
  }
  if (!provider.configured) {
    return (
      <Badge variant="warning">
        <Settings2 className="w-3 h-3 mr-1" />
        {t.integrations.setupRequired}
      </Badge>
    );
  }
  return (
    <Badge variant="default">
      <XCircle className="w-3 h-3 mr-1" />
      {t.integrations.statusNotConnected}
    </Badge>
  );
}

interface ProviderCardProps {
  provider: IntegrationProviderInfo;
  selected: boolean;
  /** Click on the card: select it, or deselect it when it is selected. */
  onToggle: () => void;
  /** "Setup required": select it to show the setup guide. */
  onSetup: () => void;
  selectedScopes: Record<string, boolean>;
  onScopeChange: (scope: string, checked: boolean) => void;
  onConnect: () => void;
  connecting: boolean;
  /** Disconnect a connected provider, or reset a configured one. */
  onDisconnect: () => void;
}

/**
 * One provider on the integrations page: icon in the status colour, name, badges, description and
 * the next step — disconnect, connect with optional permissions, or set up.
 */
export function ProviderCard({
  provider, selected, onToggle, onSetup, selectedScopes, onScopeChange, onConnect, connecting, onDisconnect,
}: ProviderCardProps) {
  const t = useT();
  const copy = useProviderCopy();
  return (
    <Card
      className={`cursor-pointer transition-all hover:border-primary/50 ${
        selected ? 'border-primary ring-1 ring-primary/30' : ''
      }`}
      onClick={onToggle}
    >
      <div className="flex items-start gap-4 p-4">
        <div className={`p-3 rounded-lg ${
          provider.status === 'CONNECTED'
            ? 'bg-success/10 text-success'
            : !provider.configured
              ? 'bg-warning/10 text-warning'
              : 'bg-surface-secondary text-text-secondary'
        }`}>
          <ProviderIcon icon={provider.icon} />
        </div>
        <div className="flex-1 min-w-0">
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1 mb-1">
            <h3 className="font-semibold text-text">{copy.name(provider)}</h3>
            <ProviderStatusBadge provider={provider} />
            <VerificationBadge provider={provider} />
          </div>
          <p className="text-sm text-text-secondary mb-3">{copy.description(provider)}</p>

          {provider.status === 'CONNECTED' && provider.accountEmail && (
            <p className="text-xs text-text-tertiary mb-3">
              {provider.accountName || provider.accountEmail}
            </p>
          )}

          <div className="flex gap-2">
            {provider.status === 'CONNECTED' ? (
              <Button
                size="sm"
                variant="danger"
                onClick={(e) => { e.stopPropagation(); onDisconnect(); }}
              >
                <Trash2 className="w-3 h-3 mr-1" />
                {t.integrations.disconnect}
              </Button>
            ) : provider.configured ? (
              <div className="space-y-2 w-full" onClick={(e) => e.stopPropagation()}>
                {provider.optionalScopes?.length > 0 && (
                  <div className="space-y-1">
                    <p className="text-xs text-text-tertiary">{t.integrations.optionalPermissions}</p>
                    {provider.optionalScopes.map((scope) => (
                      <label key={scope.scope} className="flex items-center gap-2 text-xs text-text-secondary cursor-pointer">
                        <input
                          type="checkbox"
                          checked={selectedScopes[scope.scope] ?? false}
                          onChange={(e) => onScopeChange(scope.scope, e.target.checked)}
                          className="rounded border-border"
                        />
                        {scope.label}
                      </label>
                    ))}
                  </div>
                )}
                <div className="flex gap-2">
                  <Button
                    size="sm"
                    variant="primary"
                    onClick={onConnect}
                    loading={connecting}
                  >
                    <ExternalLink className="w-3 h-3 mr-1" />
                    {t.integrations.connect}
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={onDisconnect}
                  >
                    <Trash2 className="w-3 h-3 mr-1" />
                    {t.integrations.reset}
                  </Button>
                </div>
              </div>
            ) : (
              <Button
                size="sm"
                variant="secondary"
                onClick={(e) => { e.stopPropagation(); onSetup(); }}
              >
                <Settings2 className="w-3 h-3 mr-1" />
                {t.integrations.setupRequired}
              </Button>
            )}
          </div>
        </div>
      </div>
    </Card>
  );
}
