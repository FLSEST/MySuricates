import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import { downloadJSON } from '../utils/downloadFile';
import type { Workflow, WorkflowRun, CreateWorkflowRequest, UpdateWorkflowRequest, WorkflowExportData, PageResponse } from '../types';
import { TERMINAL_RUN_STATUSES } from '../types';

interface WorkflowApiResponse {
  id: string;
  projectId: string;
  projectName: string;
  projectSubtitle: string | null;
  projectDescription: string | null;
  name: string;
  description: string;
  icon: string;
  blocks: Workflow['blocks'];
  version: string;
  metadata: Record<string, unknown>;
  activatedSkillIds: string[];
  activatedSkillNames: string[];
  runCount: number;
  instructions: string | null;
  model: string | null;
  provider: string | null;
  templateId: string | null;
  createdAt: string;
  updatedAt: string;
}

function mapWorkflow(raw: WorkflowApiResponse): Workflow {
  return {
    ...raw,
    projectName: raw.projectName ?? '',
    projectSubtitle: raw.projectSubtitle ?? undefined,
    projectDescription: raw.projectDescription ?? undefined,
    activatedSkillIds: raw.activatedSkillIds ?? [],
    activatedSkillNames: raw.activatedSkillNames ?? [],
    runCount: raw.runCount ?? 0,
    instructions: raw.instructions ?? null,
    model: raw.model ?? null,
    provider: raw.provider ?? null,
    metadata: raw.metadata ?? {},
  };
}

export function useWorkflows() {
  return useQuery({
    queryKey: ['workflows'],
    queryFn: async () => {
      const raw = await api.get<WorkflowApiResponse[]>('/workflows');
      return raw.map(mapWorkflow);
    },
  });
}

export function useWorkflow(id: string) {
  return useQuery({
    queryKey: ['workflows', id],
    queryFn: async () => {
      const raw = await api.get<WorkflowApiResponse>(`/workflows/${id}`);
      return mapWorkflow(raw);
    },
    enabled: !!id,
  });
}

export function useWorkflowRuns(workflowId: string, page = 0, size = 50) {
  return useQuery({
    queryKey: ['workflows', workflowId, 'runs', page, size],
    queryFn: () => api.get<PageResponse<WorkflowRun>>(`/workflows/${workflowId}/runs?page=${page}&size=${size}`),
    enabled: !!workflowId,
    refetchInterval: (query) => {
      const data = query.state.data;
      if (!data?.content?.length) return false;
      const hasActiveRuns = data.content.some((run) => !TERMINAL_RUN_STATUSES.has(run.status));
      return hasActiveRuns ? 5_000 : false;
    },
  });
}

export function useAllRuns(params?: { status?: string; workflowId?: string; projectId?: string; search?: string; page?: number; size?: number }) {
  return useQuery({
    queryKey: ['runs', params],
    queryFn: () => {
      const searchParams = new URLSearchParams();
      searchParams.set('page', String(params?.page ?? 0));
      searchParams.set('size', String(params?.size ?? 50));
      if (params?.status) searchParams.set('status', params.status);
      if (params?.workflowId) searchParams.set('workflowId', params.workflowId);
      if (params?.projectId) searchParams.set('projectId', params.projectId);
      if (params?.search) searchParams.set('search', params.search);
      return api.get<PageResponse<WorkflowRun>>(`/executions?${searchParams.toString()}`);
    },
    refetchInterval: (query) => {
      const data = query.state.data;
      if (!data?.content?.length) return false;
      const hasActiveRuns = data.content.some((run) => !TERMINAL_RUN_STATUSES.has(run.status));
      return hasActiveRuns ? 5_000 : 30_000;
    },
  });
}

export function useCreateWorkflow() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (data: CreateWorkflowRequest) => api.post<Workflow>('/workflows', data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workflows'] });
      toast.success(t.toast.workflowCreated);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.workflowCreateFailed)),
  });
}

export function useUpdateWorkflow() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id: string; data: UpdateWorkflowRequest }) =>
      api.put<Workflow>(`/workflows/${id}`, data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['workflows'] }),
  });
}

export function useDeleteWorkflow() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/workflows/${id}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workflows'] });
      toast.success(t.toast.workflowDeleted);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.workflowDeleteFailed)),
  });
}

export function useExecuteWorkflow() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ id, context }: { id: string; context?: Record<string, unknown> }) =>
      api.post<WorkflowRun>(`/workflows/${id}/execute`, { context }),
    onSuccess: (_, variables) => {
      qc.invalidateQueries({ queryKey: ['workflows'] });
      qc.invalidateQueries({ queryKey: ['workflows', variables.id, 'runs'] });
      qc.invalidateQueries({ queryKey: ['learning', 'dashboard'] });
      toast.success(t.toast.workflowExecuted);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.workflowExecuteFailed)),
  });
}

export function usePauseExecution() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (runId: string) => api.post(`/executions/${runId}/pause`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workflows'] });
      qc.invalidateQueries({ queryKey: ['learning'] });
    },
  });
}

export function useResumeExecution() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (runId: string) => api.post(`/executions/${runId}/resume`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['runs'] });
      qc.invalidateQueries({ queryKey: ['workflows'] });
      qc.invalidateQueries({ queryKey: ['learning'] });
    },
  });
}

export function useResumeFromStep() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (runId: string) => api.post(`/executions/${runId}/resume-from-step`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['runs'] });
      qc.invalidateQueries({ queryKey: ['workflows'] });
      qc.invalidateQueries({ queryKey: ['learning'] });
      toast.success(t.toast.workflowExecuted);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.workflowExecuteFailed)),
  });
}

export function useCancelExecution() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (runId: string) => api.post(`/executions/${runId}/cancel`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workflows'] });
      qc.invalidateQueries({ queryKey: ['learning'] });
    },
  });
}

export function useRetryExecution() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (runId: string) => api.post(`/executions/${runId}/retry`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['runs'] });
      qc.invalidateQueries({ queryKey: ['workflows'] });
      qc.invalidateQueries({ queryKey: ['learning'] });
      toast.success(t.toast.workflowExecuted);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.workflowExecuteFailed)),
  });
}

export function useRunCommand() {
  return useMutation({
    mutationFn: ({ runId, command }: { runId: string; command: string }) =>
      api.post<{ output: string }>(`/executions/${runId}/command`, { command }),
  });
}

export function useSubmitApproval() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ runId, answer }: { runId: string; answer: string }) =>
      api.post(`/executions/${runId}/approve`, { answer }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workflows'] });
    },
  });
}

export function useApproveTodos() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ runId, approved }: { runId: string; approved: boolean }) =>
      api.post(`/executions/${runId}/todos/approve`, { approved }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workflows'] });
    },
  });
}

export async function exportWorkflow(id: string, filename: string) {
  const data = await api.get<WorkflowExportData>(`/workflows/export/${id}`);
  downloadJSON(data, filename);
}

export function useImportWorkflow() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (data: { projectId: string } & WorkflowExportData) =>
      api.post<Workflow>('/workflows/import', {
        projectId: data.projectId,
        name: data.name,
        description: data.description,
        icon: data.icon,
        blocks: data.blocks,
        instructions: data.instructions,
        model: data.model,
        defaultContext: data.defaultContext,
        metadata: data.metadata,
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workflows'] });
      toast.success(t.toast.workflowImported);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.workflowImportFailed)),
  });
}

export function useOptimizeWorkflow() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (workflowId: string) =>
      api.post<Record<string, unknown>>(`/workflows/${workflowId}/optimize`),
    onSuccess: (_, workflowId) => {
      qc.invalidateQueries({ queryKey: ['workflows', workflowId] });
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.workflows.optimizeFailed)),
  });
}

export function useRepairWorkflow() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (workflowId: string) =>
      api.post(`/workflows/${workflowId}/repair`),
    onSuccess: (_, workflowId) => {
      qc.invalidateQueries({ queryKey: ['workflows', workflowId] });
      qc.invalidateQueries({ queryKey: ['workflows', workflowId, 'runs'] });
      toast.success(t.toast.repairStarted);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.repairFailed)),
  });
}

export function useUpdateRunNotes() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ runId, notes }: { runId: string; notes: string }) =>
      api.patch<WorkflowRun>(`/executions/${runId}/notes`, { notes }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workflows'] });
      qc.invalidateQueries({ queryKey: ['runs'] });
    },
  });
}
