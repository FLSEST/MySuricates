import { useId, useState, type KeyboardEvent, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowUp, Loader2, Search, Sparkles } from 'lucide-react';
import { useT } from '../../../i18n';
import { Button } from '../../ui';
import { SectionLabel } from '../kit';
import { HomeCommandRow } from './HomeCommandRow';
import { useHomeCommand } from './useHomeCommand';
import { useHomeActions } from './useHomeActions';
import { flattenGroups, hasFoundHit } from '../../../utils/homeCommand/arrange';

/** Cursor moved above the first hit: Enter asks the assistant again. */
const NO_HIT = '\u0000none';

export interface HomeCommandProps {
  /**
   * Enter without a highlighted hit, or the arrow button: hand the text to the assistant.
   * Absent (assistant module off) ⇒ the first hit is highlighted and Enter activates it.
   */
  onAsk?: (text: string) => void;
  /**
   * The assistant's answer, shown at the top of the result block — above the hits for the same
   * text. The block also opens for an answer alone, without a query.
   */
  answer?: ReactNode;
  /** Esc: the field is cleared — the answer should go, too. */
  onClear?: () => void;
}

/**
 * The Simple home's field "What do you want to do?": while typing, instant hits WITHOUT AI from all
 * sources (own workflows, templates and pool, runs, waiting answers, tasks, projects, schedules,
 * actions and settings, explanations), grouped right below the field. ↑/↓ move a highlight across
 * all hits, Enter activates it, Esc clears. Sources and matching: `useHomeCommand`,
 * `utils/homeCommand`.
 */
export function HomeCommand({ onAsk, answer, onClear }: HomeCommandProps) {
  const t = useT();
  const c = t.simple.home.command;
  const navigate = useNavigate();
  const listId = useId();
  const [input, setInput] = useState('');
  // Hit chosen with ↑/↓ (by id, so async hits arriving later do not move it); null = default
  const [cursor, setCursor] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(() => new Set());

  const { query, groups, loading } = useHomeCommand(input);
  const items = flattenGroups(groups);
  const found = hasFoundHit(groups);

  const reset = () => { setInput(''); setCursor(null); setExpanded(new Set()); };
  const actions = useHomeActions({
    onDone: reset,
    onToggleExplanation: (hintId) => setExpanded((prev) => {
      const next = new Set(prev);
      if (!next.delete(hintId)) next.add(hintId);
      return next;
    }),
  });

  // Default highlight: an explicit "aufgabe: …" is its own command; without the assistant the first
  // found hit (never the generated "add as task" row); with it none — Enter asks.
  const defaultIndex = query.todoText
    ? items.findIndex((i) => i.id === 'todo:create')
    : onAsk ? -1 : items.findIndex((i) => !i.fallback);
  const chosen = cursor ? items.findIndex((i) => i.id === cursor) : -1;
  const activeIndex = cursor === NO_HIT ? -1 : chosen >= 0 ? chosen : defaultIndex;
  const optionId = (index: number) => `${listId}-option-${index}`;

  // Asking keeps the text: the answer appears above the hits for it
  const ask = () => {
    const text = input.trim();
    if (!text || !onAsk) return;
    onAsk(text);
    setCursor(null);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') {
      if (items.length === 0) return;
      e.preventDefault();
      setCursor(items[Math.min(activeIndex + 1, items.length - 1)]!.id);
    } else if (e.key === 'ArrowUp') {
      if (items.length === 0) return;
      e.preventDefault();
      if (activeIndex > 0) setCursor(items[activeIndex - 1]!.id);
      else setCursor(onAsk ? NO_HIT : items[0]!.id);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (activeIndex >= 0) actions.execute(items[activeIndex]!.primary.action);
      else if (onAsk) ask();
    } else if (e.key === 'Escape' && (input || answer)) {
      e.preventDefault();
      reset();
      onClear?.();
    }
  };

  const open = query.raw.length > 0 || !!answer;
  const field = (
    <input
      type="text"
      role="combobox"
      value={input}
      onChange={(e) => { setInput(e.target.value); setCursor(null); }}
      onKeyDown={onKeyDown}
      aria-label={c.label}
      aria-expanded={open}
      aria-controls={listId}
      aria-autocomplete="list"
      aria-activedescendant={activeIndex >= 0 ? optionId(activeIndex) : undefined}
      autoComplete="off"
      placeholder={c.placeholder}
      className="flex-1 min-w-0 bg-transparent text-sm text-text placeholder:text-text-tertiary focus:outline-none"
    />
  );
  const spinner = loading && <Loader2 className="w-4 h-4 text-text-tertiary animate-spin shrink-0" aria-label={c.loading} />;

  const indexOf = new Map(items.map((item, i) => [item.id, i]));
  return (
    <div className="space-y-2">
      <form role="search" onSubmit={(e) => { e.preventDefault(); ask(); }}>
        {onAsk ? (
          // With the assistant: the gradient frame marks the field as the way to ask, too
          <div
            className="relative rounded-2xl p-[1.5px] shadow-[0_18px_50px_-30px_rgba(129,140,248,0.7)]"
            style={{ background: 'linear-gradient(120deg, rgba(129,140,248,.55), rgba(56,189,248,.30) 40%, rgba(167,139,250,.45))' }}
          >
            <div className="flex items-center gap-3 bg-surface-raised rounded-[14px] px-4 py-3">
              <div
                className="w-9 h-9 rounded-xl flex items-center justify-center shrink-0"
                style={{ background: 'linear-gradient(135deg, rgba(129,140,248,.25), rgba(56,189,248,.18))' }}
              >
                <Sparkles className="w-[18px] h-[18px]" style={{ color: 'var(--color-ai)' }} aria-hidden />
              </div>
              {field}
              {spinner}
              <button
                type="submit"
                aria-label={t.simple.home.assistantLabel}
                className="w-8 h-8 rounded-lg bg-accent text-text-inverse flex items-center justify-center shrink-0 hover:opacity-90 transition-opacity"
              >
                <ArrowUp className="w-4 h-4" />
              </button>
            </div>
          </div>
        ) : (
          <div className="flex items-center gap-3 rounded-2xl border border-border bg-surface-raised px-4 py-3 focus-within:border-accent/50 transition-colors">
            <Search className="w-[18px] h-[18px] text-text-tertiary shrink-0" aria-hidden />
            {field}
            {spinner}
          </div>
        )}
      </form>

      {open && (
        <div className="rounded-2xl border border-border bg-surface p-2 sm:p-3 space-y-3">
          {/* The assistant's answer comes first, the instant hits follow */}
          {answer}

          <div role="listbox" id={listId} aria-label={c.resultsLabel} className="space-y-3">
            {groups.map((group) => (
              <div key={group.id} role="group" aria-labelledby={`${listId}-${group.id}`}>
                <div className="flex items-center justify-between gap-2 px-1 mb-1">
                  <SectionLabel id={`${listId}-${group.id}`} className="mb-0">{c.groups[group.id]}</SectionLabel>
                  {group.more && group.showAllTo && (
                    <button
                      type="button"
                      onClick={() => { reset(); navigate(group.showAllTo!); }}
                      className="text-xs font-medium text-accent hover:underline"
                    >
                      {c.showAll}
                    </button>
                  )}
                </div>
                <div className="space-y-1">
                  {group.items.map((item) => {
                    const i = indexOf.get(item.id)!;
                    const isExpanded = item.primary.action.kind === 'toggle-explanation'
                      && expanded.has(item.primary.action.hintId);
                    return (
                      <HomeCommandRow
                        key={item.id}
                        item={item}
                        optionId={optionId(i)}
                        active={i === activeIndex}
                        expanded={isExpanded}
                        primaryLabel={isExpanded ? c.hide : item.primary.label}
                        execute={actions.execute}
                        isPending={actions.isPending}
                      />
                    );
                  })}
                </div>
              </div>
            ))}
          </div>

          {query.raw && !found && !loading && (
            <div className="flex flex-wrap items-center gap-3 rounded-xl border border-dashed border-border px-4 py-3">
              <p className="text-sm text-text-secondary mr-auto">{t.simple.home.noMatch}</p>
              <Button size="sm" variant="secondary" onClick={() => navigate('/workflows')}>
                {t.simple.home.allWorkflows}
              </Button>
              <Button size="sm" onClick={() => navigate('/workflows?new=1')}>
                {t.simple.home.startFromTemplate}
              </Button>
            </div>
          )}

          {onAsk && query.raw && activeIndex < 0 && (
            <p className="px-1 text-xs text-text-tertiary">{c.askHint}</p>
          )}
        </div>
      )}

      {actions.dialog}
    </div>
  );
}
