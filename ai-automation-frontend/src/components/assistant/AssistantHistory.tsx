import { Trash2, ArrowLeft, Plus } from 'lucide-react';
import { useAssistantStore } from '../../stores/assistantStore';

export function AssistantHistory({
  onBack,
  onNewSession,
}: {
  onBack: () => void;
  onNewSession: () => void;
}) {
  const { sessions, loadSession, deleteSession } = useAssistantStore();

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
        {sessions.length === 0 ? (
          <div className="text-center py-8 text-text-tertiary text-sm">Noch keine Gespräche</div>
        ) : (
          sessions.map((sess) => (
            <div
              key={sess.id}
              className="group flex items-center gap-2 px-3 py-2.5 rounded-xl cursor-pointer
                         hover:bg-[var(--color-assistant-raised)] transition-colors border border-transparent
                         hover:border-[var(--color-assistant-border)]"
              onClick={() => loadSession(sess.id)}
            >
              <div className="flex-1 min-w-0">
                <p className="text-sm text-text truncate">
                  {sess.preview || 'Leere Sitzung'}
                </p>
                <p className="text-xs text-text-tertiary mt-0.5">{formatDate(sess.startedAt)}</p>
              </div>
              <button
                onClick={(e) => { e.stopPropagation(); deleteSession(sess.id); }}
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
