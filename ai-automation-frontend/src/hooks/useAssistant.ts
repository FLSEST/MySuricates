import { useMutation, useQuery } from '@tanstack/react-query';
import { api } from '../api/client';

export interface AssistantHistoryEntry {
  role: 'user' | 'assistant';
  content: string;
}

export interface AssistantPageContext {
  page?: string;
  entityId?: string;
  projectId?: string;
}

export interface AssistantAction {
  type: 'run_workflow' | 'show_workflow' | 'show_run' | 'start_wish' | 'ask_choice';
  id: string | null;
  name: string;
  label: string;
  options?: string[];
}

export interface AssistantChatRequest {
  message: string;
  history: AssistantHistoryEntry[];
  context?: AssistantPageContext;
  provider?: string;
  sessionId?: string;
  requestId?: string;
}

export interface AssistantChatResponse {
  message: string;
  actions: AssistantAction[];
  model?: string | null;
  responseTimeMs?: number | null;
}

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  actions?: AssistantAction[];
  responseTimeMs?: number;
  timestamp?: number;
  isError?: boolean;
  status?: 'sending' | 'sent' | 'error';
}

export function useAssistantChat() {
  return useMutation({
    mutationFn: ({ req, signal }: { req: AssistantChatRequest; signal?: AbortSignal }) =>
      api.post<AssistantChatResponse>('/assistant/chat', req, { signal }),
  });
}

export function useAssistantSuggestions(page: string) {
  return useQuery({
    queryKey: ['assistant-suggestions', page],
    queryFn: () => api.get<string[]>(`/assistant/suggestions?page=${encodeURIComponent(page)}`),
    staleTime: 5 * 60 * 1000,
  });
}
