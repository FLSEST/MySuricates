import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type { ChainTemplate, ChainInstallResponse } from '../types';

export function useChainTemplates() {
  return useQuery<ChainTemplate[]>({
    queryKey: ['chain-templates'],
    queryFn: () => api.get('/chain-templates'),
  });
}

export function useInstallChainTemplate() {
  const queryClient = useQueryClient();
  const toast = useToast();
  const t = useT();

  return useMutation<ChainInstallResponse, Error, { templateId: string; projectId: string; chainName?: string }>({
    mutationFn: ({ templateId, projectId, chainName }) =>
      api.post(`/chain-templates/${templateId}/install`, { projectId, chainName }),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['chains'] });
      queryClient.invalidateQueries({ queryKey: ['workflows'] });
      toast.success(t.chains.templateInstalled?.replace('{name}', data.chainName) ?? `"${data.chainName}" installiert`);
    },
    onError: (err) => {
      toast.error(err.message);
    },
  });
}
