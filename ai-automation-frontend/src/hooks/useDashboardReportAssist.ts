import { useMutation } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useModuleEnabled } from './useModules';
import { useExecutionConfig, useProviderModels } from './useSystem';
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
 * Whether the button may be shown at all: the module is on, the runtime setting is on, and this
 * host can actually call Claude. All three are also enforced by the backend.
 *
 * The third switch reads the provider's model catalog, not the list of registered providers: that
 * list names Spring beans and always contains CLAUDE, so hiding the button on it would be a control
 * that never triggers. `available` is the host's answer (for CLAUDE: is the CLI installed), cached
 * by the backend per its TTL and by the query for a minute — the same answer the backend refuses
 * with. An installed but unauthenticated CLI still looks available here; that case fails on use.
 */
export function useDashboardReportAssistAvailable(): boolean {
  const moduleOn = useModuleEnabled('dashboard-report');
  const { data: config } = useExecutionConfig();
  const { data: claudeModels } = useProviderModels('CLAUDE');
  const settingOn = (config?.dashboardReportAssistEnabled ?? 1) === 1;
  return moduleOn && settingOn && claudeModels?.available === true;
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
