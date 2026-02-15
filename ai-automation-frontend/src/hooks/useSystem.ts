import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import type { SystemHealth, SystemInfo, RemoteAccessStatus } from '../types';

export function useSystemHealth() {
  return useQuery({
    queryKey: ['system', 'health'],
    queryFn: () => api.get<SystemHealth>('/system/health'),
    refetchInterval: 30_000,
  });
}

export function useSystemInfo() {
  return useQuery({
    queryKey: ['system', 'info'],
    queryFn: () => api.get<SystemInfo>('/system/info'),
  });
}

export function useRemoteAccessStatus() {
  return useQuery({
    queryKey: ['system', 'remote-access'],
    queryFn: () => api.get<RemoteAccessStatus>('/system/remote-access/status'),
  });
}

export function useEnableRemoteAccess() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<RemoteAccessStatus>('/system/remote-access/enable'),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['system', 'remote-access'] }),
  });
}

export function useDisableRemoteAccess() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.post('/system/remote-access/disable'),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['system', 'remote-access'] }),
  });
}
