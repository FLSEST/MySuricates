import { useState } from 'react';
import { apiErrorMessage } from '../api/client';
import { useT } from '../i18n';
import { useConfirmPendingTool, useRejectPendingTool } from './useAssistant';
import type { PendingTool, ToolExecutionResult } from './useAssistant';

/**
 * Decision state of one tool proposal of the assistant: confirm or reject it once, keep the
 * backend's answer as the card's result and a failed call as an error message.
 */
export function usePendingToolDecision(
  tool: PendingTool,
  sessionId?: string,
  existingResult?: ToolExecutionResult,
) {
  const t = useT();
  const confirm = useConfirmPendingTool();
  const reject = useRejectPendingTool();
  const [result, setResult] = useState<ToolExecutionResult | undefined>(existingResult);
  const [error, setError] = useState<string | null>(null);
  // Compute expiry once on mount — the pending-tool UI is short-lived; if the user stays on the
  // card past its 30-min TTL the backend's 410 Gone will surface via error state instead.
  const [expired] = useState<boolean>(() => {
    const expiresMs = tool.expiresAt ? new Date(tool.expiresAt).getTime() : 0;
    return expiresMs > 0 && expiresMs < Date.now();
  });

  const decide = async (mutation: typeof confirm | typeof reject) => {
    setError(null);
    try {
      setResult(await mutation.mutateAsync({ id: tool.id, sessionId }));
    } catch (e) {
      setError(apiErrorMessage(e, t.assistant.pendingToolError));
    }
  };

  return {
    result,
    error,
    expired,
    pending: confirm.isPending || reject.isPending,
    confirming: confirm.isPending,
    confirm: () => decide(confirm),
    reject: () => decide(reject),
  };
}
