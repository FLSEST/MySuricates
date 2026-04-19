import { useCallback, useState } from 'react';
import { Trash2, ArrowLeft, Plus, Loader2 } from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';
import { useAssistantStore, type ChatSession } from '../../stores/assistantStore';
import {
  useAssistantSessions,
  useDeleteAssistantSession,
  type AssistantSessionDetail,
  type ChatMessage,
} from '../../hooks/useAssistant';
import { api } from '../../api/client';
import { generateId } from '../../utils/uuid';

interface MergedSession {
  id: string;
  preview: string;
  startedAt: number;
  inStore: boolean;
}

export function AssistantHistory({
  onBack,
  onNewSession,
}: {
  onBack: () => void;
  onNewSession: () => void;
}) {
  const { sessions: storeSessions, loadSession, deleteSession, hydrateSession } = useAssistantStore();
  const { data: backendSessions, isLoading } = useAssistantSessions();
  const { mutate: deleteBackend } = useDeleteAssistantSession();
  const queryClient = useQueryClient();
  const [loadingId, setLoadingId] = useState<string | null>(null);

  // Merge: store sessions take priority, add backend-only sessions
  const merged: MergedSession[] = (() => {
    const storeIds = new Set(storeSessions.map((s) => s.id));
    const result: MergedSession[] = storeSessions.map((s) => ({
      id: s.id,
      preview: s.preview || 'Leere Sitzung',
      startedAt: s.startedAt,
      inStore: true,
    }));

    if (backendSessions) {
      for (const bs of backendSessions) {
        if (!storeIds.has(bs.id)) {
          result.push({
            id: bs.id,
            preview: bs.preview || 'Leere Sitzung',
            startedAt: new Date(bs.startedAt).getTime(),
            inStore: false,
          });
        }
      }
    }

    result.sort((a, b) => b.startedAt - a.startedAt);
    return result;
  })();

  const handleClick = useCallback(async (sess: MergedSession) => {
    if (sess.inStore) {
      loadSession(sess.id);
      return;
    }
    // Fetch full session from backend, hydrate into store, then load
    setLoadingId(sess.id);
    try {
      const detail = await queryClient.fetchQuery<AssistantSessionDetail>({
        queryKey: ['assistant-session', sess.id],
        queryFn: () => api.get<AssistantSessionDetail>(`/assistant/sessions/${sess.id}`),
        staleTime: 60_000,
      });
      const chatSession: ChatSession = {
        id: detail.id,
        startedAt: new Date(detail.startedAt).getTime(),
        preview: detail.preview || '',
        messages: detail.messages.map((m): ChatMessage => ({
          id: generateId(),
          role: m.role,
          content: m.content,
          timestamp: m.timestamp,
          responseTimeMs: m.role === 'assistant' ? m.responseTimeMs : undefined,
          provider: m.role === 'assistant' ? m.provider : undefined,
          model: m.role === 'assistant' ? m.model : undefined,
          status: 'sent',
        })),
      };
      hydrateSession(chatSession);
      loadSession(sess.id);
    } catch {
      // Silently fail — session might have been deleted
    } finally {
      setLoadingId(null);
    }
  }, [loadSession, hydrateSession, queryClient]);

  const handleDelete = useCallback((e: React.MouseEvent, id: string) => {
    e.stopPropagation();
    deleteSession(id);
    deleteBackend(id);
  }, [deleteSession, deleteBackend]);

  const formatDate = (ts: number) => {
    const d = new Date(ts);
    const today = new Date();
    const isToday = d.toDateString() === today.toDateString();
    if (isToday) return d.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' });
    return d.toLocaleDateString('de-DE', { day: '2-digit', month: '2-digit' }) +
      ' ' + d.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' });
  };

  return (
    <div className="flex flex-col flex-1 min-h-0">
      <div className="flex items-center gap-2 px-4 py-3 border-b border-[var(--color-assistant-border)]">
        <button
          onClick={onBack}
          className="p-1 rounded-lg text-text-tertiary hover:text-text hover:bg-[var(--color-assistant-raised)] transition-colors"
        >
          <ArrowLeft className="w-4 h-4" />
        </button>
        <span className="text-sm font-medium text-text">Bisherige Gespräche</span>
      </div>

      <div className="flex-1 overflow-y-auto px-3 py-3 space-y-1.5 min-h-0">
        {isLoading && merged.length === 0 ? (
          <div className="flex items-center justify-center py-8 text-text-tertiary text-sm gap-2">
            <Loader2 className="w-4 h-4 animate-spin" />
            Lade Gespräche…
          </div>
        ) : merged.length === 0 ? (
          <div className="text-center py-8 text-text-tertiary text-sm">Noch keine Gespräche</div>
        ) : (
          merged.map((sess) => (
            <div
              key={sess.id}
              className="group flex items-center gap-2 px-3 py-2.5 rounded-xl cursor-pointer
                         hover:bg-[var(--color-assistant-raised)] transition-colors border border-transparent
                         hover:border-[var(--color-assistant-border)]"
              onClick={() => handleClick(sess)}
            >
              <div className="flex-1 min-w-0">
                <p className="text-sm text-text truncate">
                  {loadingId === sess.id ? (
                    <span className="flex items-center gap-1.5">
                      <Loader2 className="w-3 h-3 animate-spin" />
                      {sess.preview}
                    </span>
                  ) : (
                    sess.preview
                  )}
                </p>
                <p className="text-xs text-text-tertiary mt-0.5">{formatDate(sess.startedAt)}</p>
              </div>
              <button
                onClick={(e) => handleDelete(e, sess.id)}
                className="shrink-0 opacity-0 group-hover:opacity-100 p-1 rounded-lg
                           text-text-tertiary hover:text-danger transition-all"
              >
                <Trash2 className="w-3.5 h-3.5" />
              </button>
            </div>
          ))
        )}
      </div>

      <div className="px-3 py-3 border-t border-[var(--color-assistant-border)]">
        <button
          onClick={onNewSession}
          className="w-full flex items-center justify-center gap-2 py-2 rounded-xl text-sm
                     text-text-secondary border border-[var(--color-assistant-border)]
                     hover:border-accent/40 hover:text-accent hover:bg-accent/5 transition-colors"
        >
          <Plus className="w-4 h-4" />
          Neues Gespräch
        </button>
      </div>
    </div>
  );
}
