import { useEffect, useState } from 'react';
import { api } from '../api/client';
import { formFields, type PendingApproval } from './useWebSocket';
import type { PendingHumanInput } from '../types';

interface PendingInputResponse {
  pending: boolean; blockId?: string; question?: string; options?: string[]; rejectOptions?: string[]; stepIndex?: number;
  type?: string; inputType?: string; defaultValue?: string; variableName?: string;
  required?: boolean; validationPattern?: string | null;
  /** Multi-field form; older backends do not send it (ec913801). */
  fields?: unknown;
}

interface Recovered {
  runId: string;
  approval: PendingApproval | null;
  humanInput: PendingHumanInput | null;
}

function toRecovered(runId: string, data: PendingInputResponse): Recovered {
  if (!data.pending || !data.blockId) return { runId, approval: null, humanInput: null };
  if (data.type === 'human-input') {
    return {
      runId,
      approval: null,
      humanInput: {
        blockId: data.blockId,
        prompt: data.question!,
        inputType: (data.inputType as PendingHumanInput['inputType']) || 'text',
        options: data.options || [],
        defaultValue: data.defaultValue || '',
        required: data.required ?? true,
        validationPattern: data.validationPattern ?? null,
        variableName: data.variableName || 'user_input',
        stepIndex: data.stepIndex!,
        ...formFields(data.fields),
      },
    };
  }
  return {
    runId,
    approval: {
      blockId: data.blockId,
      question: data.question!,
      options: data.options!,
      rejectOptions: data.rejectOptions,
      stepIndex: data.stepIndex!,
    },
    humanInput: null,
  };
}

/**
 * Stellt eine wartende Freigabe bzw. Eingabe wieder her (`GET /executions/{runId}/approval`) — nach Laden/Neuladen
 * der Laufseite ist die WebSocket-Meldung dazu schon vorbei. Ändert sich `refreshKey`, fragt der Hook erneut
 * (78af069c: beginnt die Freigabe nach der ersten Abfrage, aber bevor der WebSocket abonniert ist, verpasst die
 * Seite sonst beide Wege). Es zählt nur die Antwort der jüngsten Abfrage; `pending: false` räumt auf, Fehler
 * ändern nichts. Ohne wartenden Schritt bleiben beide Werte `null`, ein anderer Lauf erbt nichts.
 */
export function useRunInputRecovery(runId: string | undefined, refreshKey?: string) {
  const [recovered, setRecovered] = useState<Recovered | null>(null);

  useEffect(() => {
    if (!runId) return;
    let superseded = false;
    api.get<PendingInputResponse>(`/executions/${runId}/approval`)
      .then((data) => {
        if (!superseded) setRecovered(toRecovered(runId, data));
      })
      .catch(() => { /* ignore */ });
    return () => { superseded = true; };
  }, [runId, refreshKey]);

  const current = recovered?.runId === runId ? recovered : null;
  return {
    recoveredApproval: current?.approval ?? null,
    recoveredHumanInput: current?.humanInput ?? null,
  };
}
