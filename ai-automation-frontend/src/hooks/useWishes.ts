import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type { Wish, CreateWishRequest, WishRespondRequest } from '../types';

export function useWishes() {
  return useQuery({
    queryKey: ['wishes'],
    queryFn: () => api.get<Wish[]>('/wishes'),
  });
}

export function useWish(id: string | null) {
  return useQuery({
    queryKey: ['wishes', id],
    queryFn: () => api.get<Wish>(`/wishes/${id}`),
    enabled: !!id,
    refetchInterval: (query) => {
      const data = query.state.data;
      if (data && ['COMPLETED', 'FAILED', 'CANCELLED', 'AWAITING_APPROVAL', 'AWAITING_CLARIFICATION'].includes(data.status)) {
        return false;
      }
      return 2000;
    },
  });
}

export function useCreateWish() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (data: CreateWishRequest) =>
      api.post<Wish>('/wishes', data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['wishes'] });
      toast.success(t.toast.wishCreated);
    },
    onError: () => toast.error(t.toast.wishFailed),
  });
}

export function useCancelWish() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (id: string) => api.post(`/wishes/${id}/cancel`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['wishes'] });
      toast.success(t.toast.wishCancelled);
    },
  });
}

export function useDeleteWish() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/wishes/${id}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['wishes'] });
      toast.success(t.toast.wishDeleted);
    },
  });
}

export function useRespondToWish() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ id, data }: { id: string; data: WishRespondRequest }) =>
      api.post<Wish>(`/wishes/${id}/respond`, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['wishes'] });
      toast.success(t.toast.wishResponded);
    },
    onError: () => toast.error(t.toast.wishRespondFailed),
  });
}

export function useCrystallizeWish() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (id: string) =>
      api.post<{ templateId: string }>(`/wishes/${id}/crystallize`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['wishes'] });
      qc.invalidateQueries({ queryKey: ['templates'] });
      toast.success(t.toast.wishCrystallized);
    },
    onError: () => toast.error(t.toast.wishCrystallizeFailed),
  });
}
