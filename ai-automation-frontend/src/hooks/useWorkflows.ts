import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type { Workflow, WorkflowRun, CreateWorkflowRequest, UpdateWorkflowRequest, WorkflowExportData } from '../types';

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
  activatedSkills: Array<{ id: string; name: string }>;
  runCount: number;
  instructions: string | null;
  model: string | null;
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
    activatedSkillIds: raw.activatedSkills?.map((s) => s.id) ?? [],
    activatedSkillNames: raw.activatedSkills?.map((s) => s.name) ?? [],
    runCount: raw.runCount ?? 0,
    instructions: raw.instructions ?? null,
    model: raw.model ?? null,
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

export function useWorkflowRuns(workflowId: string) {
  return useQuery({
    queryKey: ['workflows', workflowId, 'runs'],
    queryFn: () => api.get<WorkflowRun[]>(`/workflows/${workflowId}/runs`),
    enabled: !!workflowId,
  });
}

export function useAllRuns(params?: { status?: string; workflowId?: string; projectId?: string }) {
  return useQuery({
    queryKey: ['runs', params],
    queryFn: () => {
      const searchParams = new URLSearchParams();
      if (params?.status) searchParams.set('status', params.status);
      if (params?.workflowId) searchParams.set('workflowId', params.workflowId);
      if (params?.projectId) searchParams.set('projectId', params.projectId);
      const qs = searchParams.toString();
      return api.get<WorkflowRun[]>(`/executions${qs ? `?${qs}` : ''}`);
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
    onError: () => toast.error(t.toast.workflowCreateFailed),
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
    onError: () => toast.error(t.toast.workflowDeleteFailed),
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
    onError: () => toast.error(t.toast.workflowExecuteFailed),
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
      qc.invalidateQueries({ queryKey: ['workflows'] });
      qc.invalidateQueries({ queryKey: ['learning'] });
    },
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

export async function exportWorkflow(id: string, filename: string) {
  const data = await api.get<WorkflowExportData>(`/workflows/export/${id}`);
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
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
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workflows'] });
      toast.success(t.toast.workflowImported);
    },
    onError: () => toast.error(t.toast.workflowImportFailed),
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
    onError: () => toast.error(t.workflows.optimizeFailed),
  });
}
