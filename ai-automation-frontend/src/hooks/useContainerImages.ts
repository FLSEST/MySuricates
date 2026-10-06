import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';

export type ContainerImageStatus = 'CURRENT' | 'OUTDATED' | 'UNLABELED' | 'MISSING' | 'PRESENT' | 'UNKNOWN';

export interface ContainerTool {
  name: string;
  /** null for packages installed unpinned (apt, pip). */
  version: string | null;
  source: 'release' | 'base' | 'apt' | 'pip' | 'npm' | 'pip-hash';
  arg: string | null;
}

export interface ContainerWorkflowUse {
  workflowId: string;
  workflowName: string;
  /** Top-level block name or label; nested blocks follow after " › ". */
  block: string;
  mode: 'COMMAND' | 'TOOL';
}

export interface ContainerImageView {
  image: string;
  /** Shipped agent image (with catalog entry) vs. an image a workflow names. */
  shipped: boolean;
  purpose: Partial<Record<'de' | 'en', string>>;
  base: string | null;
  tools: ContainerTool[];
  status: ContainerImageStatus;
  builtAt: string | null;
  allowed: boolean;
  buildCommand: string | null;
  workflows: ContainerWorkflowUse[];
  projectTypes: string[];
}

export interface ContainerImagesOverview {
  docker: 'CONNECTED' | 'UNAVAILABLE' | 'NOT_CONFIGURED';
  allowlistActive: boolean;
  images: ContainerImageView[];
}

/** Container images workflows can run in, with build state and usage (TODO 244e5de5). */
export function useContainerImages() {
  return useQuery({
    queryKey: ['container-images'],
    queryFn: () => api.get<ContainerImagesOverview>('/container-images'),
    staleTime: 60 * 1000,
  });
}
