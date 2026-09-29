import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import { useModuleEnabled } from './useModules';
import { useI18nStore } from '../i18n';

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
  /** UI locale ('en' | 'de') — drives the assistant's reply language. */
  language?: string;
  sessionId?: string;
  requestId?: string;
}

export interface PendingTool {
  id: string;
  type: string;
  /** English fallback sentence — shown for unknown types or when `subject` is missing. */
  description: string;
  /** Human name the action is about (workflow name, run's workflow, task title, chain name);
   *  the shortened id when unresolved, then `params.ref === 'id'`. Absent on older backends. */
  subject?: string | null;
  /** Small parameters such as `priority`, `status`, `ref`. */
  params?: Record<string, string>;
  expiresAt: string;
}

export interface ToolExecutionResult {
  toolId: string;
  type: string;
  success: boolean;
  message: string;
}

export interface AssistantChatResponse {
  message: string;
  actions: AssistantAction[];
  model?: string | null;
  provider?: string | null;
  responseTimeMs?: number | null;
  pendingTools?: PendingTool[];
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
  pendingTools?: PendingTool[];
  toolResults?: ToolExecutionResult[];
  /** On an assistant message: id of the user message it answers. */
  replyTo?: string;
}

/** One chat turn: `POST /assistant/chat`. */
export function postAssistantChat(req: AssistantChatRequest, signal?: AbortSignal) {
  return api.post<AssistantChatResponse>('/assistant/chat', req, { signal });
}

export function useAssistantChat() {
  return useMutation({
    mutationFn: ({ req, signal }: { req: AssistantChatRequest; signal?: AbortSignal }) => postAssistantChat(req, signal),
  });
}

/**
 * Confirms a previously-proposed tool action. The backend only executes the tool after this
 * call fires — the assistant itself cannot bypass this gate regardless of model behavior.
 * `language` is the UI locale: the build check after a workflow tool answers in it (TODO 81773d7b).
 */
export function useConfirmPendingTool() {
  return useMutation({
    mutationFn: ({ id, sessionId }: { id: string; sessionId?: string }) =>
      api.post<ToolExecutionResult>(`/assistant/pending-tools/${id}/confirm`,
        { sessionId, language: useI18nStore.getState().locale }),
  });
}

export function useRejectPendingTool() {
  return useMutation({
    mutationFn: ({ id, sessionId }: { id: string; sessionId?: string }) =>
      api.post<ToolExecutionResult>(`/assistant/pending-tools/${id}/reject`,
        { sessionId, language: useI18nStore.getState().locale }),
  });
}

export function useAssistantSuggestions(page: string) {
  const moduleEnabled = useModuleEnabled('assistant');
  // Suggestions are kept per page and language – without it the English UI got the German set (5157fac2)
  const locale = useI18nStore((s) => s.locale);
  return useQuery({
    queryKey: ['assistant-suggestions', page, locale],
    queryFn: () => api.get<string[]>(`/assistant/suggestions?page=${encodeURIComponent(page)}&locale=${locale}`),
    staleTime: 5 * 60 * 1000,
    enabled: moduleEnabled,
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
  const moduleEnabled = useModuleEnabled('assistant');
  return useQuery({
    queryKey: ['assistant-sessions-api'],
    queryFn: () => api.get<AssistantSessionSummary[]>('/assistant/sessions'),
    staleTime: 30_000,
    enabled: moduleEnabled && enabled,
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
