import { useEffect, useState } from 'react';
import { X } from 'lucide-react';
import { useSettingsStore } from '../../stores/settingsStore';
import { useAssistantSuggestions } from '../../hooks/useAssistant';
import { useAssistantThinkingPhrases } from '../../hooks/useSystem';
import { MeerkatIcon } from '../layout/Sidebar';
import { assistantPhaseLabel } from '../assistantPanelUtils';
import type { AssistantStatus } from '../../hooks/useAssistantWebSocket';
import { useT } from '../../i18n';

export function StatusIndicator({ phase }: { phase: AssistantStatus | null }) {
  const t = useT();
  const { data: phrases = t.assistant.defaultPhrases } = useAssistantThinkingPhrases();
  const [idx, setIdx] = useState(() => Math.floor(Math.random() * Math.max(phrases.length, 1)));

  useEffect(() => {
    const timer = setInterval(() => setIdx((i) => (i + 1) % phrases.length), 2500);
    return () => clearInterval(timer);
  }, [phrases.length]);

  // Use WebSocket phase if available, otherwise fall back to rotating phrases
  const label = phase
    ? assistantPhaseLabel(phase.phase, t)
    : (phrases[idx % phrases.length] ?? '...');
  const detail = phase?.detail;
  const elapsed = phase ? Math.round(phase.elapsedMs / 1000) : null;

  return (
    <div className="flex gap-2 items-start">
      <div className="shrink-0 w-7 h-7 rounded-full bg-[var(--color-assistant-raised)]
                      border border-[var(--color-assistant-border)] flex items-center justify-center mt-0.5">
        <MeerkatIcon className="text-accent" size={18} />
      </div>
      <div className="px-3 py-2.5 rounded-2xl rounded-tl-sm bg-[var(--color-assistant-raised)]
                      border border-[var(--color-assistant-border)] flex items-center gap-2">
        <span className="text-sm text-text-secondary animate-pulse">{label}</span>
        {detail && <span className="text-[11px] text-text-tertiary">({detail})</span>}
        {elapsed !== null && elapsed > 0 && (
          <span className="text-[11px] text-text-tertiary tabular-nums">{elapsed}s</span>
        )}
      </div>
    </div>
  );
}

export function AssistantEmptyState({ page, onSuggestionClick, inputRef }: { page: string; onSuggestionClick: (text: string) => void; inputRef: React.RefObject<HTMLTextAreaElement | null> }) {
  const t = useT();
  const suggestionsEnabled = useSettingsStore((s) => s.suggestionsEnabled);
  const { data: suggestions } = useAssistantSuggestions(page);
  const fallback = t.assistant.fallbackSuggestions;
  const items = suggestionsEnabled && suggestions?.length ? suggestions : fallback;

  return (
    <div className="flex flex-col items-center justify-center h-full gap-4 text-center">
      <div className="animate-pulse-subtle">
        <MeerkatIcon className="text-accent" size={52} />
      </div>
      <div>
        <p className="text-sm font-semibold text-text">{t.assistant.greeting}</p>
        <p className="text-xs text-text-secondary mt-1">
          {t.assistant.greetingSubtitle}
        </p>
      </div>
      <div className="flex flex-col gap-2 w-full mt-1">
        {items.map((s) => (
          <button
            key={s}
            onClick={() => { onSuggestionClick(s); setTimeout(() => inputRef.current?.focus(), 0); }}
            className="text-left px-3 py-2 rounded-xl text-xs text-text-secondary
                       border border-[var(--color-assistant-border)]
                       hover:border-accent/40 hover:text-text
                       hover:bg-[var(--color-assistant)] transition-colors"
          >
            {s}
          </button>
        ))}
      </div>
    </div>
  );
}

export function ContextChips({ page, onChipClick, inputRef }: { page: string; onChipClick: (text: string) => void; inputRef: React.RefObject<HTMLTextAreaElement | null> }) {
  const t = useT();
  const suggestionsEnabled = useSettingsStore((s) => s.suggestionsEnabled);
  const { data: suggestions } = useAssistantSuggestions(page);
  const [dismissed, setDismissed] = useState<Set<string>>(new Set());

  if (!suggestionsEnabled) return null;
  const visible = suggestions?.filter((s) => !dismissed.has(s));
  if (!visible?.length) return null;

  return (
    <div
      className="flex gap-2 px-4 py-2 overflow-x-auto shrink-0 scrollbar-none"
      style={{ borderBottom: '1px solid var(--color-assistant-border)' }}
    >
      {visible.map((s) => (
        <span
          key={s}
          className="group/chip shrink-0 inline-flex items-center gap-1 px-3 py-1 rounded-full text-xs font-medium
                     whitespace-nowrap bg-accent/10 text-accent border border-accent/20 transition-colors"
        >
          <button
            onClick={() => { onChipClick(s); setTimeout(() => inputRef.current?.focus(), 0); }}
            className="hover:underline"
          >
            {s}
          </button>
          <button
            onClick={(e) => { e.stopPropagation(); setDismissed((prev) => new Set(prev).add(s)); }}
            className="opacity-0 group-hover/chip:opacity-100 hover:text-danger transition-all"
            aria-label={t.assistant.removeSuggestion}
          >
            <X className="w-3 h-3" />
          </button>
        </span>
      ))}
    </div>
  );
}
