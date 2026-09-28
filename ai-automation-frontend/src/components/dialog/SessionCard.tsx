import { ChevronRight } from 'lucide-react';
import { Badge } from '../ui';
import { useI18nStore } from '../../i18n';
import type { DialogSessionInfo } from '../../types';
import { formatDuration, formatSessionStart } from './dialogSessionFormat';

/** Panel 2 der Session-Übernahme: eine Session mit erster Nachricht, Werkzeugen, Start und Dauer. */
export function SessionCard({
  session, selected, onClick,
}: {
  session: DialogSessionInfo;
  selected: boolean;
  onClick: () => void;
}) {
  const locale = useI18nStore((s) => s.locale);
  return (
    <button
      onClick={onClick}
      className={`w-full text-left p-3 rounded-lg border transition-colors ${
        selected
          ? 'border-accent bg-accent/10'
          : 'border-border bg-surface hover:border-accent/50 hover:bg-surface-hover'
      }`}
    >
      <div className="flex items-start justify-between gap-2">
        <div className="flex-1 min-w-0">
          {session.firstUserMessage && (
            <p className="text-xs text-text truncate leading-relaxed mb-1">
              {session.firstUserMessage}
            </p>
          )}
          <div className="flex items-center gap-1.5 flex-wrap">
            {session.dominantTools.map(tool => (
              <span key={tool} className="text-xs px-1.5 py-0.5 rounded bg-surface-elevated text-text-secondary font-mono">
                {tool}
              </span>
            ))}
            {!session.substantial && <Badge variant="default">small</Badge>}
          </div>
        </div>
        <div className="flex flex-col items-end gap-1 shrink-0 text-xs text-text-secondary">
          <span>{formatSessionStart(session.startedAt, locale)}</span>
          <span>{formatDuration(session.startedAt, session.endedAt)}</span>
          <ChevronRight className="w-3.5 h-3.5 text-text-secondary/50 mt-0.5" />
        </div>
      </div>
    </button>
  );
}
