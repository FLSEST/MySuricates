import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type { ScheduledWorkflow, CreateScheduleRequest } from '../types';

export function useSchedules() {
  return useQuery({
    queryKey: ['schedules'],
    queryFn: () => api.get<ScheduledWorkflow[]>('/schedules'),
  });
}

export function useCreateSchedule() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (data: CreateScheduleRequest) =>
      api.post<ScheduledWorkflow>('/schedules', data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['schedules'] });
      toast.success(t.toast.scheduleCreated);
    },
    onError: () => toast.error(t.toast.scheduleCreateFailed),
  });
}

export function useToggleSchedule() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ id, enable }: { id: string; enable: boolean }) =>
      api.post<ScheduledWorkflow>(`/schedules/${id}/${enable ? 'enable' : 'disable'}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['schedules'] });
      toast.success(t.toast.scheduleToggled);
    },
    onError: () => toast.error(t.toast.scheduleToggleFailed),
  });
}

export function useDeleteSchedule() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/schedules/${id}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['schedules'] });
      toast.success(t.toast.scheduleDeleted);
    },
    onError: () => toast.error(t.toast.scheduleDeleteFailed),
  });
}
