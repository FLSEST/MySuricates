import { useState } from 'react';
import { Play, ChevronRight, Settings2 } from 'lucide-react';
import { Card, Button, Badge } from '../ui';
import { useI18nStore, useT } from '../../i18n';
import { formatDateTime } from '../../utils/formatTime';
import { useProviderCopy } from '../../hooks/useProviderCopy';
import { useIntegrationActionTest } from '../../hooks/useIntegrationActionTest';
import { SetupGuidePanel } from './SetupGuidePanel';
import { VerificationNotice } from './IntegrationVerification';
import type { IntegrationProviderInfo } from '../../types';

/**
 * Detail area of a connected provider: connection (account, since, granted and optional
 * permissions), the setup guide folded in, and its actions with a test run each. The page renders
 * it keyed by provider, so choosing another provider starts folded and without a test result.
 */
export function ProviderDetailPanel({ provider, onRefresh }: {
  provider: IntegrationProviderInfo;
  onRefresh: () => void;
}) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const copy = useProviderCopy();
  const { testResult, testingAction, runTest } = useIntegrationActionTest();
  const [showConfig, setShowConfig] = useState(false);

  return (
    <Card>
      <div className="p-4">
        {/* Connection details */}
        <div className="mb-6">
          <h3 className="font-semibold text-text mb-3">{t.integrations.connectionDetails}</h3>
          <div className="mb-3">
            <VerificationNotice provider={provider} />
          </div>
          <dl className="space-y-2 text-sm">
            {(provider.accountName || provider.accountEmail) && (
              <div className="flex gap-2">
                <dt className="text-text-tertiary w-40 shrink-0">{t.integrations.account}</dt>
                <dd className="text-text-secondary">{provider.accountName || provider.accountEmail}</dd>
              </div>
            )}
            {provider.connectedSince && (
              <div className="flex gap-2">
                <dt className="text-text-tertiary w-40 shrink-0">{t.integrations.connectedSince}</dt>
                <dd className="text-text-secondary">{formatDateTime(provider.connectedSince, locale)}</dd>
              </div>
            )}
            <div className="flex gap-2">
              <dt className="text-text-tertiary w-40 shrink-0">{t.integrations.grantedPermissions}</dt>
              <dd className="flex flex-wrap gap-1">
                {provider.scopes?.trim()
                  ? provider.scopes.trim().split(/\s+/).map((s) => (
                      <Badge key={s} variant="success">{s}</Badge>
                    ))
                  : <span className="text-text-secondary">—</span>}
              </dd>
            </div>
            {provider.optionalScopes?.length > 0 && (
              <div className="flex gap-2">
                <dt className="text-text-tertiary w-40 shrink-0">{t.integrations.optionalPermissions}</dt>
                <dd className="flex flex-wrap gap-1">
                  {provider.optionalScopes.map((s) => (
                    <Badge key={s.scope} variant={s.granted ? 'success' : 'default'}>{s.label}</Badge>
                  ))}
                </dd>
              </div>
            )}
          </dl>
        </div>

        {/* Configuration & setup guide (collapsible) */}
        <div className="mb-6 border border-border rounded-lg overflow-hidden">
          <button
            onClick={() => setShowConfig((v) => !v)}
            className="flex items-center gap-2 w-full p-3 text-left hover:bg-surface-secondary transition-colors"
          >
            <Settings2 className="w-4 h-4 text-text-secondary shrink-0" />
            <span className="font-medium text-sm text-text flex-1">{t.integrations.configuration}</span>
            <ChevronRight className={`w-4 h-4 text-text-secondary transition-transform duration-200 shrink-0 ${showConfig ? 'rotate-90' : ''}`} />
          </button>
          <div className={`grid transition-all duration-200 ease-in-out ${showConfig ? 'grid-rows-[1fr] opacity-100' : 'grid-rows-[0fr] opacity-0'}`}>
            <div className="overflow-hidden">
              <div className="px-3 pb-3">
                <SetupGuidePanel
                  key={provider.providerId}
                  providerId={provider.providerId}
                  displayName={copy.name(provider)}
                  requiresCredentials={provider.requiresCredentials !== false}
                  connected
                  onRefresh={onRefresh}
                />
              </div>
            </div>
          </div>
        </div>

        <h3 className="font-semibold text-text mb-4">
          {t.integrations.availableActions} — {copy.name(provider)}
        </h3>
        <div className="space-y-3">
          {provider.actions.map((action) => (
            <div key={action.name} className={`flex items-center justify-between p-3 rounded-lg bg-surface-secondary ${!action.available ? 'opacity-50' : ''}`}>
              <div>
                <p className={`font-medium text-sm ${action.available ? 'text-text' : 'text-text-tertiary'}`}>{action.name}</p>
                <p className="text-xs text-text-secondary">{copy.actionDescription(provider.providerId, action)}</p>
                {!action.available && (
                  <p className="text-xs text-warning mt-1">{t.integrations.scopeMissing}</p>
                )}
              </div>
              {action.available && (
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => runTest(provider.providerId, action)}
                  loading={testingAction === action.name}
                  title={t.integrations.testHint}
                >
                  <Play className="w-3 h-3 mr-1" />
                  {t.integrations.test}
                </Button>
              )}
            </div>
          ))}
        </div>

        {testResult && (
          <div className="mt-4">
            <h4 className="text-sm font-medium text-text mb-2">{t.integrations.testResult}</h4>
            <pre className="p-3 rounded-lg bg-surface text-xs text-text-secondary overflow-auto max-h-64 border border-border">
              {testResult}
            </pre>
          </div>
        )}
      </div>
    </Card>
  );
}
