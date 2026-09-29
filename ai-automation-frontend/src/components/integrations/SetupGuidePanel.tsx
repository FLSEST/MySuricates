import { useState } from 'react';
import { ExternalLink, CheckCircle2, ChevronRight, Copy, Settings2 } from 'lucide-react';
import { Card, Button, Input } from '../ui';
import { useSaveCredentials } from '../../hooks/useIntegrations';
import { useT } from '../../i18n';
import { useI18nStore } from '../../i18n';
import { SETUP_GUIDES } from '../../data/integrationSetupGuides';
import type { SetupStep } from '../../data/integrationSetupGuides';

/**
 * Setup guide + credential form for one integration provider. Extracted from
 * pages/Integrations.tsx (audit finding 9c464f6f) — behaviour unchanged.
 */
export function SetupGuidePanel({ providerId, displayName, requiresCredentials = true, connected = false, connection, onRefresh }: {
  providerId: string;
  displayName: string;
  requiresCredentials?: boolean;
  connected?: boolean;
  /** Named connection the credentials belong to; omitted = the provider's default connection. */
  connection?: string;
  onRefresh: () => void;
}) {
  const lang = useI18nStore.getState().locale;
  const t = useT();
  const guide = SETUP_GUIDES[providerId];
  const saveCredentials = useSaveCredentials();
  const [openStep, setOpenStep] = useState(0);
  const [copied, setCopied] = useState<number | null>(null);
  const [clientId, setClientId] = useState(guide?.credentialLabels?.clientIdDefault ?? '');
  const [clientSecret, setClientSecret] = useState('');

  if (!guide) return null;

  const copyToClipboard = (text: string, stepIndex: number) => {
    navigator.clipboard.writeText(text);
    setCopied(stepIndex);
    setTimeout(() => setCopied(null), 2000);
  };

  const handleSaveCredentials = () => {
    saveCredentials.mutate(
      { providerId, clientId, clientSecret, connection },
      { onSuccess: () => onRefresh() }
    );
  };

  const content = (
    <div className={connected ? '' : 'p-4'}>
      {!connected && (
        <>
          <div className="flex items-center gap-2 mb-1">
            <Settings2 className="w-5 h-5 text-warning" />
            <h3 className="font-semibold text-text">
              {t.integrations.setupTitle.replace('{provider}', displayName)}
            </h3>
          </div>
          <p className="text-sm text-text-secondary mb-4">{t.integrations.setupDescription}</p>
        </>
      )}

        <div className="space-y-2">
          {guide.steps.map((step: SetupStep, i: number) => {
            const isOpen = openStep === i;
            return (
              <div key={i} className="border border-border rounded-lg overflow-hidden">
                <button
                  onClick={() => setOpenStep(isOpen ? -1 : i)}
                  className="flex items-center gap-3 w-full p-3 text-left hover:bg-surface-secondary transition-colors"
                >
                  <span className="flex items-center justify-center w-6 h-6 rounded-full bg-primary/10 text-primary text-xs font-bold shrink-0">
                    {i + 1}
                  </span>
                  <ChevronRight className={`w-4 h-4 text-text-secondary transition-transform duration-200 shrink-0 ${isOpen ? 'rotate-90' : ''}`} />
                  <span className="font-medium text-sm text-text">{step.title[lang]}</span>
                </button>

                <div className={`grid transition-all duration-200 ease-in-out ${isOpen ? 'grid-rows-[1fr] opacity-100' : 'grid-rows-[0fr] opacity-0'}`}>
                  <div className="overflow-hidden">
                    <div className="px-3 pb-3 pl-12 space-y-2">
                      <ul className="list-disc list-inside space-y-1">
                        {step.instructions[lang].map((line, j) => (
                          <li key={j} className="text-sm text-text-secondary">{line}</li>
                        ))}
                      </ul>

                      {step.code && (
                        <div className="relative">
                          <pre className="p-2 rounded bg-surface text-xs text-text font-mono border border-border overflow-x-auto">
                            {step.code}
                          </pre>
                          <button
                            onClick={() => copyToClipboard(step.code!, i)}
                            className="absolute top-1 right-1 p-1 rounded hover:bg-surface-secondary text-text-tertiary hover:text-text transition-colors"
                            title={t.common.copy}
                          >
                            {copied === i ? <CheckCircle2 className="w-3.5 h-3.5 text-success" /> : <Copy className="w-3.5 h-3.5" />}
                          </button>
                        </div>
                      )}

                      {step.link && (
                        <a
                          href={step.link.url}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="inline-flex items-center gap-1 text-sm text-primary hover:underline"
                        >
                          <ExternalLink className="w-3 h-3" />
                          {step.link.label[lang]}
                        </a>
                      )}
                    </div>
                  </div>
                </div>
              </div>
            );
          })}
        </div>

        {/* Credential input fields */}
        <div className="mt-4 p-3 rounded-lg border border-border bg-surface-secondary space-y-3">
          <h4 className="text-sm font-medium text-text">
            {connected ? t.integrations.updateCredentials : t.integrations.enterCredentials}
          </h4>
          {connected && (
            <p className="text-xs text-text-tertiary">{t.integrations.credentialsSaved}</p>
          )}
          <Input
            placeholder={guide.credentialLabels?.clientIdPlaceholder[lang] ?? 'Client ID'}
            value={clientId}
            onChange={(e) => setClientId(e.target.value)}
          />
          {providerId === 'trello' && clientId.trim() && (
            <a
              href={`https://trello.com/1/authorize?expiration=never&scope=read,write&response_type=token&name=MySuricates&key=${encodeURIComponent(clientId.trim())}`}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 text-sm text-primary hover:underline"
            >
              <ExternalLink className="w-3 h-3" />
              {lang === 'de' ? 'Token generieren' : 'Generate Token'}
            </a>
          )}
          <Input
            type="password"
            placeholder={guide.credentialLabels?.clientSecretPlaceholder[lang] ?? 'Client Secret'}
            value={clientSecret}
            onChange={(e) => setClientSecret(e.target.value)}
          />
          <Button
            size="sm"
            variant="primary"
            onClick={handleSaveCredentials}
            loading={saveCredentials.isPending}
            disabled={requiresCredentials && (!clientId.trim() || !clientSecret.trim())}
          >
            <CheckCircle2 className="w-3 h-3 mr-1" />
            {connected ? t.integrations.updateCredentials : t.integrations.saveCredentials}
          </Button>
        </div>
      </div>
  );
  return connected ? content : <Card>{content}</Card>;
}
