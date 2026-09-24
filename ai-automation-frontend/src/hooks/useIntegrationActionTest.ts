import { useState } from 'react';
import { useTestIntegrationAction } from './useIntegrations';
import type { IntegrationActionInfo } from '../types';

/**
 * Probelauf einer Integrations-Aktion im Detailbereich: welche Aktion gerade läuft (Ladezustand
 * ihres Knopfs) und das Ergebnis als eingerücktes JSON. Ein neuer Lauf leert das alte Ergebnis;
 * einen Fehler meldet `useTestIntegrationAction` als Toast, das Ergebnis bleibt dann leer.
 */
export function useIntegrationActionTest() {
  const testAction = useTestIntegrationAction();
  const [testResult, setTestResult] = useState<string | null>(null);
  const [testingAction, setTestingAction] = useState<string | null>(null);

  const runTest = (providerId: string, action: IntegrationActionInfo) => {
    setTestingAction(action.name);
    setTestResult(null);
    testAction.mutate(
      { providerId, action: action.name },
      {
        onSuccess: (data) => {
          setTestResult(JSON.stringify(data, null, 2));
          setTestingAction(null);
        },
        onError: () => setTestingAction(null),
      }
    );
  };

  return { testResult, testingAction, runTest };
}
