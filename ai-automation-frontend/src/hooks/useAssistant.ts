import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';

export interface AssistantHistoryEntry {
  role: 'user' | 'assistant';
  content: string;
}

export interface AssistantPageContext {
  page?: string;
  entityId?: string;
  projectId?: string;
  workflowId?: string;
  runId?: string;
  pathname?: string;
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
  provider?: string | null;
  responseTimeMs?: number | null;
}

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  actions?: AssistantAction[];
  responseTimeMs?: number;
  provider?: string;
  model?: string;
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

// ── Session History ────────────────────────────────────────────────────

export interface AssistantSessionSummary {
  id: string;
  preview: string | null;
  page: string;
  messageCount: number;
  startedAt: string;
}

export interface AssistantSessionDetail {
  id: string;
  preview: string | null;
  page: string;
  entityId: string | null;
  messages: Array<{
    role: 'user' | 'assistant';
    content: string;
    timestamp: number;
    responseTimeMs?: number;
    provider?: string;
    model?: string;
  }>;
  messageCount: number;
  startedAt: string;
  endedAt: string | null;
}

export function useAssistantSessions(enabled = true) {
  return useQuery({
    queryKey: ['assistant-sessions-api'],
    queryFn: () => api.get<AssistantSessionSummary[]>('/assistant/sessions'),
    staleTime: 30_000,
    enabled,
  });
}

export function useAssistantSessionDetail(sessionId: string | null) {
  return useQuery({
    queryKey: ['assistant-session', sessionId],
    queryFn: () => api.get<AssistantSessionDetail>(`/assistant/sessions/${sessionId}`),
    enabled: !!sessionId,
    staleTime: 60_000,
  });
}

export function useDeleteAssistantSession() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/assistant/sessions/${id}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['assistant-sessions-api'] });
    },
  });
}
