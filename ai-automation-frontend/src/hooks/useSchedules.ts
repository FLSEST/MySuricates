import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type { ScheduledWorkflow, CreateScheduleRequest, UpdateScheduleRequest, CalendarDataResponse, UpcomingScheduleData, ScheduleRunsResponse, CatchUpQueueStatus } from '../types';

export function useSchedules() {
  return useQuery({
    queryKey: ['schedules'],
    queryFn: () => api.get<ScheduledWorkflow[]>('/schedules'),
  });
}

export function useMaintenanceMode() {
  return useQuery({
    queryKey: ['schedules', 'maintenance'],
    queryFn: () => api.get<{ enabled: boolean }>('/schedules/maintenance'),
    staleTime: 15_000,
  });
}

export function useSetMaintenanceMode() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (enabled: boolean) =>
      api.post<{ enabled: boolean }>('/schedules/maintenance', { enabled }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['schedules', 'maintenance'] });
    },
  });
}

/** Missed occurrences being caught up one after another (empty most of the time). */
export function useCatchUpQueue() {
  return useQuery({
    queryKey: ['schedules', 'catch-up'],
    queryFn: () => api.get<CatchUpQueueStatus>('/schedules/catch-up'),
    refetchInterval: 30_000,
  });
}

export function useCalendarData(from: string, to: string) {
  return useQuery({
    queryKey: ['schedules', 'calendar', from, to],
    queryFn: () => api.get<CalendarDataResponse>(`/schedules/calendar?from=${from}&to=${to}`),
    enabled: !!from && !!to,
  });
}

export function useRemainingToday() {
  return useQuery({
    queryKey: ['schedules', 'remaining-today'],
    queryFn: () => api.get<UpcomingScheduleData>('/schedules/remaining-today'),
    refetchInterval: 60_000,
  });
}

export function useRemainingThisWeek() {
  return useQuery({
    queryKey: ['schedules', 'remaining-this-week'],
    queryFn: () => api.get<UpcomingScheduleData>('/schedules/remaining-this-week'),
    refetchInterval: 60_000,
  });
}

export function useCronPreview(cronExpression: string) {
  return useQuery({
    queryKey: ['schedules', 'preview', cronExpression],
    queryFn: () => api.post<{ runs: string[] }>('/schedules/preview', { cronExpression }),
    enabled: !!cronExpression,
    staleTime: 30_000,
  });
}

export function useScheduleRuns(scheduleId: string | null) {
  return useQuery({
    queryKey: ['schedules', scheduleId, 'runs'],
    queryFn: () => api.get<ScheduleRunsResponse>(`/schedules/${scheduleId}/runs`),
    enabled: !!scheduleId,
    staleTime: 10_000,
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
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.scheduleCreateFailed)),
  });
}

export function useUpdateSchedule() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ id, data }: { id: string; data: UpdateScheduleRequest }) =>
      api.put<ScheduledWorkflow>(`/schedules/${id}`, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['schedules'] });
      toast.success(t.toast.scheduleUpdated);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.scheduleUpdateFailed)),
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
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.scheduleToggleFailed)),
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
    onError: (err) => toast.error(apiErrorMessage(err, t.toast.scheduleDeleteFailed)),
  });
}
