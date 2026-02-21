import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type { ScheduledWorkflow, CreateScheduleRequest, CalendarDataResponse, UpcomingScheduleData } from '../types';

export function useSchedules() {
  return useQuery({
    queryKey: ['schedules'],
    queryFn: () => api.get<ScheduledWorkflow[]>('/schedules'),
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
