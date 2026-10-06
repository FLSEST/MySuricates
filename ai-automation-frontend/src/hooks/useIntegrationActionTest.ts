import { useState } from 'react';
import { useTestIntegrationAction } from './useIntegrations';
import type { IntegrationActionInfo } from '../types';

/**
 * Test run of an integration action in the detail area: which action is running right now (loading state
 * of its button) and the result as indented JSON. A new run clears the old result;
 * an error is reported by `useTestIntegrationAction` as a toast, the result then stays empty.
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
