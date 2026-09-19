import { useMutation } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useModuleEnabled } from './useModules';
import { useAiProviders, useExecutionConfig } from './useSystem';
import { useToast } from './useToast';
import { useT } from '../i18n';
import { parseWorkflowResult, type WorkflowResult } from '../components/dashboard/workflowWidgetContract';
import type { ApiError } from '../types';

/**
 * The editor's AI proposal for a dashboard-report block. The backend already validated the contract
 * — running it through `parseWorkflowResult` here costs nothing and keeps the ONE client-side
 * contract check the only way a result ever reaches a React tree.
 */
export interface DashboardReportProposal {
  runId: string;
  runCompletedAt: string | null;
  provider: string;
  proposal: WorkflowResult;
}

interface RawProposal {
  runId?: unknown;
  runCompletedAt?: unknown;
  provider?: unknown;
  proposal?: unknown;
}

/**
 * Whether the button may be shown at all: the module is on, the runtime setting is on, and an AI
 * provider the assist can use is connected. All three are also enforced by the backend.
 */
export function useDashboardReportAssistAvailable(): boolean {
  const moduleOn = useModuleEnabled('dashboard-report');
  const { data: config } = useExecutionConfig();
  const { data: providers } = useAiProviders();
  const settingOn = (config?.dashboardReportAssistEnabled ?? 1) === 1;
  const hasProvider = (providers?.providers ?? []).includes('CLAUDE');
  return moduleOn && settingOn && hasProvider;
}

export function useDashboardReportAssist() {
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: async (workflowId: string): Promise<DashboardReportProposal> => {
      const raw = await api.post<RawProposal>('/dashboard/report-assist', { workflowId });
      const proposal = parseWorkflowResult(raw.proposal);
      if (!proposal || typeof raw.runId !== 'string') {
        // Shaped like a real API error (not a bare Error), so apiErrorMessage's `'error' in err`
        // check picks up the specific text below — a plain `new Error(...)` has no `.error` field
        // and apiErrorMessage would silently fall back to the generic "failed" message instead.
        throw {
          error: t.workflows.dashboardReport.assist.invalid,
          timestamp: new Date().toISOString(),
        } satisfies ApiError;
      }
      return {
        runId: raw.runId,
        runCompletedAt: typeof raw.runCompletedAt === 'string' ? raw.runCompletedAt : null,
        provider: typeof raw.provider === 'string' ? raw.provider : '',
        proposal,
      };
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.workflows.dashboardReport.assist.failed)),
  });
}
