import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';

export type DeadLetterStatus = 'OPEN' | 'RETRIED' | 'DISCARDED';

export interface DeadLetterEntry {
  id: string;
  workflowRunId: string | null;
  workflowId: string | null;
  workflowName: string | null;
  errorMessage: string | null;
  failedStepIndex: number | null;
  retryCount: number;
  lastFailureAt: string;
  status: DeadLetterStatus;
  createdAt: string;
  updatedAt: string | null;
}

export interface DeadLetterPage {
  content: DeadLetterEntry[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export function useDeadLetters(status: DeadLetterStatus | 'ALL', page: number, size: number) {
  return useQuery<DeadLetterPage>({
    queryKey: ['dlq', status, page, size],
    queryFn: () => {
      const params = new URLSearchParams();
      if (status !== 'ALL') params.set('status', status);
      params.set('page', String(page));
      params.set('size', String(size));
      return api.get<DeadLetterPage>(`/dlq?${params.toString()}`);
    },
  });
}

export function useRetryDeadLetter() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();

  return useMutation({
    mutationFn: (id: string) => api.post<DeadLetterEntry>(`/dlq/${id}/retry`, {}),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['dlq'] });
      toast.success(t.toast.dlqRetried);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.dlqRetryFailed)),
  });
}

export function useDiscardDeadLetter() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();

  return useMutation({
    mutationFn: (id: string) => api.delete(`/dlq/${id}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['dlq'] });
      toast.success(t.toast.dlqDiscarded);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.dlqDiscardFailed)),
  });
}
