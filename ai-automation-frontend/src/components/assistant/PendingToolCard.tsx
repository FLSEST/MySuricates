import { Check, X, Loader2, ShieldAlert } from 'lucide-react';
import { useT } from '../../i18n';
import type { PendingTool, ToolExecutionResult } from '../../hooks/useAssistant';
import { usePendingToolDecision } from '../../hooks/usePendingToolDecision';
import { isDestructivePendingTool, pendingToolText } from './pendingToolText';

/**
 * A tool proposal awaiting user confirmation. The backend only executes the tool after the
 * user clicks "Bestätigen" — the assistant has no path to auto-trigger it regardless of the
 * model's prompt compliance.
 */
export function PendingToolCard({ tool, sessionId, existingResult }: {
  tool: PendingTool;
  sessionId?: string;
  existingResult?: ToolExecutionResult;
}) {
  const t = useT();
  const decision = usePendingToolDecision(tool, sessionId, existingResult);
  const destructive = isDestructivePendingTool(tool);

  if (decision.result) {
    return (
      <div className={`flex items-start gap-1.5 text-xs rounded-lg px-2.5 py-1.5 border ${
        decision.result.success
          ? 'bg-accent/10 border-accent/30 text-accent'
          : 'bg-danger/10 border-danger/30 text-danger'
      }`}>
        {decision.result.success ? <Check className="w-3.5 h-3.5 mt-0.5 shrink-0" /> : <X className="w-3.5 h-3.5 mt-0.5 shrink-0" />}
        <span className="leading-snug whitespace-pre-line break-words min-w-0">{decision.result.message}</span>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-1.5 text-xs rounded-lg px-2.5 py-1.5 border border-[var(--color-assistant-border)] bg-[var(--color-assistant-raised)]">
      <div className="flex items-start gap-1.5">
        <ShieldAlert className={`w-3.5 h-3.5 mt-0.5 shrink-0 ${destructive ? 'text-danger' : 'text-accent'}`} />
        <span className="leading-snug text-text">{pendingToolText(tool, t)}</span>
      </div>
      {decision.expired ? (
        <div className="text-text-tertiary italic">{t.assistant.pendingToolExpired}</div>
      ) : (
        <div className="flex items-center gap-1.5">
          <button
            onClick={decision.confirm}
            disabled={decision.pending}
            className="flex items-center gap-1 px-2.5 py-1 rounded-full text-xs font-medium
                       bg-accent text-text-inverse hover:bg-accent-hover transition-colors disabled:opacity-60"
          >
            {decision.confirming ? <Loader2 className="w-3 h-3 animate-spin" /> : <Check className="w-3 h-3" />}
            {decision.confirming ? t.assistant.pendingToolExecuting : t.assistant.pendingToolConfirm}
          </button>
          <button
            onClick={decision.reject}
            disabled={decision.pending}
            className="flex items-center gap-1 px-2.5 py-1 rounded-full text-xs font-medium
                       bg-transparent text-text-secondary border border-[var(--color-assistant-border)]
                       hover:bg-[var(--color-assistant-hover)] transition-colors disabled:opacity-60"
          >
            <X className="w-3 h-3" />
            {t.assistant.pendingToolReject}
          </button>
        </div>
      )}
      {decision.error && <div className="text-danger text-[11px]">{decision.error}</div>}
    </div>
  );
}
