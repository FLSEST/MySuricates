import { Trash2, RefreshCw, KeyRound, ShieldCheck, ShieldOff } from 'lucide-react';
import { Badge, Toggle } from '../ui';
import type { TranslationKeys } from '../../i18n';
import type { WebhookTrigger } from '../../types';

/** Actions on a webhook that ask for confirmation first. */
export type WebhookConfirmKind = 'regenerate' | 'delete' | 'rotateSecret' | 'removeSecret';

/**
 * Columns of the webhook table. The key button sets a first signing secret right away; rotating
 * it, removing it, a new token and deleting go through `onConfirm`. The secret actions exist only
 * with the `webhook-signatures` module.
 */
export function webhookColumns({ t, signaturesModule, onToggle, onSetSecret, onConfirm }: {
  t: TranslationKeys;
  signaturesModule: boolean;
  onToggle: (webhook: WebhookTrigger, enabled: boolean) => void;
  onSetSecret: (webhook: WebhookTrigger) => void;
  onConfirm: (kind: WebhookConfirmKind, webhook: WebhookTrigger) => void;
}) {
  return [
    {
      key: 'workflow',
      header: t.common.workflow,
      render: (w: WebhookTrigger) => (
        <span className="font-medium text-text">{w.workflowName}</span>
      ),
    },
    {
      key: 'url',
      header: t.webhooks.triggerUrl,
      render: (w: WebhookTrigger) => (
        <span className="font-mono text-xs text-text-secondary truncate max-w-[300px] block">
          {w.webhookUrl}
        </span>
      ),
    },
    {
      key: 'token',
      header: t.webhooks.token,
      render: (w: WebhookTrigger) => (
        <span
          className="font-mono text-xs text-text-tertiary"
          title={w.tokenVisible ? undefined : t.webhooks.tokenMaskedHint}
        >
          {w.secretToken}
        </span>
      ),
    },
    {
      key: 'signature',
      header: t.webhooks.signature,
      render: (w: WebhookTrigger) => (w.signatureRequired ? (
        <span title={t.webhooks.signedHint} data-testid="webhook-signed">
          <Badge variant="success"><ShieldCheck className="w-3 h-3" />{t.webhooks.signed}</Badge>
        </span>
      ) : (
        <span className="text-xs text-text-tertiary">{t.webhooks.unsigned}</span>
      )),
      width: '120px',
    },
    {
      key: 'triggers',
      header: t.webhooks.triggerCount,
      render: (w: WebhookTrigger) => (
        <Badge>{String(w.triggerCount)}</Badge>
      ),
      width: '100px',
    },
    {
      key: 'enabled',
      header: t.common.status,
      render: (w: WebhookTrigger) => (
        <Toggle
          enabled={w.enabled}
          onChange={(enabled) => onToggle(w, enabled)}
        />
      ),
      width: '80px',
    },
    {
      key: 'actions',
      header: '',
      render: (w: WebhookTrigger) => (
        <div className="flex items-center gap-1">
          {signaturesModule && (
            <button
              onClick={(e) => {
                e.stopPropagation();
                if (w.signatureRequired) {
                  onConfirm('rotateSecret', w);
                } else {
                  onSetSecret(w);
                }
              }}
              className="p-1.5 rounded-lg text-text-tertiary hover:text-accent transition-colors"
              aria-label={w.signatureRequired ? t.webhooks.rotateSecret : t.webhooks.setSecret}
              title={w.signatureRequired ? t.webhooks.rotateSecret : t.webhooks.setSecret}
            >
              <KeyRound className="w-4 h-4" />
            </button>
          )}
          {signaturesModule && w.signatureRequired && (
            <button
              onClick={(e) => {
                e.stopPropagation();
                onConfirm('removeSecret', w);
              }}
              className="p-1.5 rounded-lg text-text-tertiary hover:text-danger transition-colors"
              aria-label={t.webhooks.removeSecret}
              title={t.webhooks.removeSecret}
            >
              <ShieldOff className="w-4 h-4" />
            </button>
          )}
          <button
            onClick={(e) => {
              e.stopPropagation();
              onConfirm('regenerate', w);
            }}
            className="p-1.5 rounded-lg text-text-tertiary hover:text-accent transition-colors"
            aria-label={t.webhooks.regenerate}
            title={t.webhooks.regenerate}
          >
            <RefreshCw className="w-4 h-4" />
          </button>
          <button
            onClick={(e) => {
              e.stopPropagation();
              onConfirm('delete', w);
            }}
            className="p-1.5 rounded-lg text-text-tertiary hover:text-danger transition-colors"
            aria-label={t.common.delete}
          >
            <Trash2 className="w-4 h-4" />
          </button>
        </div>
      ),
      width: signaturesModule ? '140px' : '80px',
    },
  ];
}
