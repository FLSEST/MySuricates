import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { PendingApproval } from './useWebSocket';
import type { PendingHumanInput } from '../types';

/**
 * Stellt nach Laden/Neuladen der Laufseite eine wartende Freigabe bzw. Eingabe wieder her
 * (`GET /executions/{runId}/approval`) — die WebSocket-Meldung dazu ist dann schon vorbei.
 * Fehler werden ignoriert; ohne wartenden Schritt bleiben beide Werte `null`.
 */
export function useRunInputRecovery(runId: string | undefined) {
  const [recoveredApproval, setRecoveredApproval] = useState<PendingApproval | null>(null);
  const [recoveredHumanInput, setRecoveredHumanInput] = useState<PendingHumanInput | null>(null);

  useEffect(() => {
    if (!runId) return;
    api.get<{
      pending: boolean; blockId?: string; question?: string; options?: string[]; rejectOptions?: string[]; stepIndex?: number;
      type?: string; inputType?: string; defaultValue?: string; variableName?: string;
      required?: boolean; validationPattern?: string | null;
    }>(
      `/executions/${runId}/approval`
    ).then((data) => {
      if (data.pending && data.blockId) {
        if (data.type === 'human-input') {
          setRecoveredHumanInput({
            blockId: data.blockId,
            prompt: data.question!,
            inputType: (data.inputType as PendingHumanInput['inputType']) || 'text',
            options: data.options || [],
            defaultValue: data.defaultValue || '',
            required: data.required ?? true,
            validationPattern: data.validationPattern ?? null,
            variableName: data.variableName || 'user_input',
            stepIndex: data.stepIndex!,
          });
        } else {
          setRecoveredApproval({
            blockId: data.blockId,
            question: data.question!,
            options: data.options!,
            rejectOptions: data.rejectOptions,
            stepIndex: data.stepIndex!,
          });
        }
      }
    }).catch(() => { /* ignore */ });
  }, [runId]);

  return {
    recoveredApproval,
    recoveredHumanInput,
    clearRecoveredApproval: () => setRecoveredApproval(null),
    clearRecoveredHumanInput: () => setRecoveredHumanInput(null),
  };
}
