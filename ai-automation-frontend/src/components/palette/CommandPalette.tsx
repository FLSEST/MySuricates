import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { Search, Play, ExternalLink, Star, ArrowLeft, RotateCw, X, Sparkles } from 'lucide-react';
import { useExecuteWorkflow } from '../../hooks/useWorkflows';
import { usePaletteHistory } from '../../hooks/usePaletteHistory';
import { useAssistantStore } from '../../stores/assistantStore';
import { useT } from '../../i18n';
import { WorkflowIcon } from '../../utils/workflowIcon';
import type { Workflow } from '../../types';
import { resolveContextId } from './resolveContext';
import { useWorkflowContext } from './contexts/workflowContext';
import { useRunContext } from './contexts/runContext';
import { useProjectContext } from './contexts/projectContext';
import type { PaletteContextHandle, PaletteItem, BadgeTone, PaletteMatch, HighlightKey } from './contexts/types';
import { parsePaletteQuery } from './queryParser';

interface CollectingState {
  workflow: Workflow;
  variables: string[];
  values: Record<string, string>;
}

interface CommandPaletteProps {
  open: boolean;
  onClose: () => void;
}

/**
 * Outer wrapper: resolves the current context by route and remounts PaletteBody
 * on context change via `key`. Remounting guarantees a stable hook order
 * inside the inner body.
 */
export function CommandPalette({ open, onClose }: CommandPaletteProps) {
  const location = useLocation();
  const contextId = resolveContextId(location.pathname);
  if (!open) return null;
  return <PaletteBody key={contextId} contextId={contextId} onClose={onClose} />;
}

function PaletteBody({ contextId, onClose }: { contextId: ReturnType<typeof resolveContextId>; onClose: () => void }) {
  // Exactly one context hook per variant — satisfies react-hooks/rules-of-hooks.
  if (contextId === 'run') return <RunBody onClose={onClose} />;
  if (contextId === 'project') return <ProjectBody onClose={onClose} />;
  return <WorkflowBody onClose={onClose} />;
}

function WorkflowBody({ onClose }: { onClose: () => void }) {
  const context = useWorkflowContext();
  return <InnerPalette context={context} onClose={onClose} />;
}

function RunBody({ onClose }: { onClose: () => void }) {
  const context = useRunContext();
  return <InnerPalette context={context} onClose={onClose} />;
}

function ProjectBody({ onClose }: { onClose: () => void }) {
  const context = useProjectContext();
  return <InnerPalette context={context} onClose={onClose} />;
}

function InnerPalette({ context, onClose }: { context: PaletteContextHandle; onClose: () => void }) {
  const t = useT();
  const navigate = useNavigate();
  const inputRef = useRef<HTMLInputElement>(null);
  const [query, setQuery] = useState('');
  const [selectedIdx, setSelectedIdx] = useState(0);
  const [collecting, setCollecting] = useState<CollectingState | null>(null);

  const executeWorkflow = useExecuteWorkflow();
  const { recents, pins, pushRecent, togglePin, isPinned } = usePaletteHistory(context.id);
  const setAssistantInput = useAssistantStore((s) => s.setPendingInput);
  const setAssistantOpen = useAssistantStore((s) => s.setOpen);

  const debouncedQuery = useDebounced(query, 250);

  // For run context we parse the query to show the status filter chip. Other contexts
  // ignore slash-tokens; the parser returns null status and the chip is hidden.
  const parsed = useMemo(() => parsePaletteQuery(query), [query]);

  const { items: results, loading } = context.useResults(debouncedQuery);
  const pinnedItems = context.useByIds(pins);
  const recentItems = context.useByIds(
    recents.filter((id) => !pins.includes(id)).slice(0, 5),
  );

  const isEmptyQuery = query.trim().length === 0;
  // Assistant escape-hatch row appears at the bottom once the query has substance;
  // threshold matches Fuse's minMatchCharLength so we don't offer it for stray keystrokes.
  const hasAssistantRow = query.trim().length >= 2;
  const assistantIdx = hasAssistantRow ? results.length : -1;
  const totalItems = isEmptyQuery
    ? pinnedItems.length + recentItems.length
    : results.length + (hasAssistantRow ? 1 : 0);

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  useEffect(() => {
    setSelectedIdx(0);
  }, [query]);

  const itemAt = (idx: number): PaletteItem | null => {
    if (isEmptyQuery) {
      if (idx < pinnedItems.length) return pinnedItems[idx] ?? null;
      return recentItems[idx - pinnedItems.length] ?? null;
    }
    return results[idx] ?? null;
  };

  const deps = { navigate, onClose };

  const openItem = (item: PaletteItem) => {
    if (context.onOpen) context.onOpen(item, deps);
    else context.onEnter(item, deps);
  };

  const openItemInNewTab = (item: PaletteItem) => {
    const path = context.getPath?.(item);
    if (path) {
      window.open(path, '_blank', 'noopener');
    } else {
      openItem(item);
    }
  };

  const runAltAction = (item: PaletteItem) => {
    context.altAction?.run(item, deps);
  };

  const askAssistant = () => {
    const text = query.trim();
    if (!text) return;
    setAssistantInput(text);
    setAssistantOpen(true);
    onClose();
  };

  const enterItem = (item: PaletteItem) => {
    pushRecent(item.id);
    if (context.onBeforeEnter) {
      const intercepted = context.onBeforeEnter(item, deps, (w, variables, values) => {
        setCollecting({ workflow: w as Workflow, variables, values });
      });
      if (intercepted) return;
    }
    context.onEnter(item, deps);
  };

  const submitCollected = () => {
    if (!collecting) return;
    const w = collecting.workflow;
    pushRecent(w.id);
    executeWorkflow.mutate(
      { id: w.id, context: collecting.values },
      {
        onSuccess: (run) => {
          setCollecting(null);
          onClose();
          navigate(`/workflows/${w.id}/run/${run.id}`);
        },
      },
    );
  };

  useEffect(() => {
    const handleKey = (e: KeyboardEvent) => {
      if (collecting) {
        if (e.key === 'Escape') {
          e.preventDefault();
          setCollecting(null);
        }
        return;
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        onClose();
        return;
      }
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setSelectedIdx((i) => Math.min(i + 1, Math.max(0, totalItems - 1)));
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        setSelectedIdx((i) => Math.max(0, i - 1));
      } else if (e.key === 'Enter') {
        e.preventDefault();
        if (!isEmptyQuery && selectedIdx === assistantIdx) {
          askAssistant();
          return;
        }
        const item = itemAt(selectedIdx);
        if (item) {
          if (e.metaKey || e.ctrlKey) openItemInNewTab(item);
          else if (e.shiftKey) openItem(item);
          else enterItem(item);
        }
      } else if (
        context.altAction &&
        e.altKey &&
        e.key.toLowerCase() === context.altAction.shortcut
      ) {
        // Alt+<shortcut> fires even when the search input is focused. For runContext: Alt+R = Re-Run.
        e.preventDefault();
        const item = itemAt(selectedIdx);
        if (item) runAltAction(item);
      }
    };
    window.addEventListener('keydown', handleKey);
    return () => window.removeEventListener('keydown', handleKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedIdx, totalItems, results, pinnedItems, recentItems, collecting, isEmptyQuery]);

  return (
    <div
      className="fixed inset-0 z-[100] flex items-start justify-center pt-[10vh] bg-black/40 backdrop-blur-sm animate-fade-in"
      onClick={onClose}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-xl bg-surface border border-border rounded-xl shadow-2xl overflow-hidden"
      >
        {collecting ? (
          <CollectForm
            state={collecting}
            onChange={(values) => setCollecting({ ...collecting, values })}
            onSubmit={submitCollected}
            onCancel={() => setCollecting(null)}
            loading={executeWorkflow.isPending}
            t={t}
          />
        ) : (
          <>
            <div className="flex items-center gap-2 px-4 py-3 border-b border-border">
              <Search className="w-4 h-4 text-text-tertiary shrink-0" />
              {context.id === 'run' && parsed.status && (
                <button
                  type="button"
                  onClick={() => setQuery(parsed.query)}
                  className="flex items-center gap-1 text-[10px] font-medium px-1.5 py-0.5 rounded-sm bg-accent/15 text-accent shrink-0"
                  title={t.commandPalette.filterChipRemove}
                >
                  {t.commandPalette.filterChipStatus}: {parsed.status.toLowerCase()}
                  <X className="w-3 h-3" />
                </button>
              )}
              <input
                ref={inputRef}
                type="text"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder={context.labels.placeholder}
                className="flex-1 bg-transparent text-sm text-text placeholder:text-text-tertiary focus:outline-none"
              />
              <span className="text-[10px] text-text-tertiary border border-border px-1.5 py-0.5 rounded-sm">
                ESC
              </span>
            </div>

            <div className="max-h-[60vh] overflow-y-auto">
              {!isEmptyQuery && results.length === 0 && !loading && (
                <div className="px-4 py-4 text-center text-sm text-text-tertiary">
                  {context.labels.empty}
                </div>
              )}

              {isEmptyQuery && pinnedItems.length > 0 && (
                <Section title={t.commandPalette.pinned} icon={<Star className="w-3 h-3 fill-current" />}>
                  {pinnedItems.map((item, idx) => (
                    <PaletteRow
                      key={item.id}
                      item={item}
                      selected={selectedIdx === idx}
                      pinned
                      onHover={() => setSelectedIdx(idx)}
                      onRun={() => enterItem(item)}
                      onOpen={() => openItem(item)}
                      onTogglePin={() => togglePin(item.id)}
                      t={t}
                      altAction={context.altAction ? { label: context.altAction.label, shortcut: context.altAction.shortcut, run: () => runAltAction(item) } : undefined}
                    />
                  ))}
                </Section>
              )}

              {isEmptyQuery && recentItems.length > 0 && (
                <Section title={t.commandPalette.recent} borderTop={pinnedItems.length > 0}>
                  {recentItems.map((item, i) => {
                    const absIdx = pinnedItems.length + i;
                    return (
                      <PaletteRow
                        key={item.id}
                        item={item}
                        selected={selectedIdx === absIdx}
                        pinned={false}
                        onHover={() => setSelectedIdx(absIdx)}
                        onRun={() => enterItem(item)}
                        onOpen={() => openItem(item)}
                        onTogglePin={() => togglePin(item.id)}
                        t={t}
                      altAction={context.altAction ? { label: context.altAction.label, shortcut: context.altAction.shortcut, run: () => runAltAction(item) } : undefined}
                      />
                    );
                  })}
                </Section>
              )}

              {!isEmptyQuery && results.length > 0 && (
                <div className="py-1">
                  {results.map((item, idx) => (
                    <PaletteRow
                      key={item.id}
                      item={item}
                      selected={selectedIdx === idx}
                      pinned={isPinned(item.id)}
                      onHover={() => setSelectedIdx(idx)}
                      onRun={() => enterItem(item)}
                      onOpen={() => openItem(item)}
                      onTogglePin={() => togglePin(item.id)}
                      t={t}
                      altAction={context.altAction ? { label: context.altAction.label, shortcut: context.altAction.shortcut, run: () => runAltAction(item) } : undefined}
                    />
                  ))}
                </div>
              )}

              {!isEmptyQuery && hasAssistantRow && (
                <AssistantRow
                  query={query.trim()}
                  selected={selectedIdx === assistantIdx}
                  onHover={() => setSelectedIdx(assistantIdx)}
                  onClick={askAssistant}
                  label={t.commandPalette.askAssistant}
                  borderTop={results.length > 0}
                />
              )}

              {isEmptyQuery && pinnedItems.length === 0 && recentItems.length === 0 && (
                <div className="px-4 py-6 text-center text-xs text-text-tertiary">
                  {t.commandPalette.hint}
                </div>
              )}
            </div>
          </>
        )}
      </div>
    </div>
  );
}

interface SectionProps {
  title: string;
  icon?: React.ReactNode;
  borderTop?: boolean;
  children: React.ReactNode;
}

function Section({ title, icon, borderTop, children }: SectionProps) {
  return (
    <div className={`py-1 ${borderTop ? 'border-t border-border' : ''}`}>
      <div className="px-4 py-1 text-[10px] font-semibold uppercase tracking-wider text-text-tertiary flex items-center gap-1">
        {icon}
        {title}
      </div>
      {children}
    </div>
  );
}

const TONE_CLASSES: Record<BadgeTone, string> = {
  success: 'bg-success/10 text-success',
  danger: 'bg-danger/10 text-danger',
  warn: 'bg-warning/10 text-warning',
  info: 'bg-accent/10 text-accent',
  neutral: 'bg-surface-raised text-text-tertiary',
};

interface PaletteRowProps {
  item: PaletteItem;
  selected: boolean;
  pinned: boolean;
  onHover: () => void;
  onRun: () => void;
  onOpen: () => void;
  onTogglePin: () => void;
  altAction?: {
    label: string;
    shortcut: string;
    run: () => void;
  };
  t: ReturnType<typeof useT>;
}

function renderHighlighted(text: string, matches: PaletteMatch[] | undefined, key: HighlightKey): React.ReactNode {
  if (!text) return text;
  const ranges = matches?.filter((m) => m.key === key).flatMap((m) => m.indices) ?? [];
  if (ranges.length === 0) return text;
  const sorted = [...ranges].sort((a, b) => a[0] - b[0]);
  const out: React.ReactNode[] = [];
  let cursor = 0;
  sorted.forEach(([start, end], i) => {
    if (start > cursor) out.push(text.slice(cursor, start));
    out.push(
      <span key={`h${i}`} className="font-semibold text-accent">
        {text.slice(start, end + 1)}
      </span>,
    );
    cursor = end + 1;
  });
  if (cursor < text.length) out.push(text.slice(cursor));
  return out;
}

function PaletteRow({ item, selected, pinned, onHover, onRun, onOpen, onTogglePin, altAction, t }: PaletteRowProps) {
  return (
    <div
      onMouseEnter={onHover}
      onClick={onRun}
      className={`flex items-center gap-3 px-4 py-2 cursor-pointer ${
        selected ? 'bg-accent/10' : 'hover:bg-surface-raised'
      }`}
    >
      <span className="shrink-0">
        <WorkflowIcon icon={item.icon ?? null} className="w-4 h-4" />
      </span>
      <div className="flex-1 min-w-0">
        <div className="text-sm text-text truncate">{renderHighlighted(item.title, item.matches, 'title')}</div>
        {item.subtitle && (
          <div className="text-xs text-text-tertiary truncate">
            {renderHighlighted(item.subtitle, item.matches, 'subtitle')}
          </div>
        )}
      </div>
      {item.badge && (
        <span className={`text-[10px] px-1.5 py-0.5 rounded-sm shrink-0 ${TONE_CLASSES[item.badge.tone]}`}>
          {item.badge.label}
        </span>
      )}
      <button
        onClick={(e) => { e.stopPropagation(); onTogglePin(); }}
        className={`p-1 rounded-sm transition-colors ${
          pinned ? 'text-accent' : 'text-text-tertiary hover:text-accent'
        }`}
        title={pinned ? t.commandPalette.unpinHint : t.commandPalette.pinHint}
        aria-label={pinned ? t.commandPalette.unpinHint : t.commandPalette.pinHint}
      >
        <Star className={`w-3.5 h-3.5 ${pinned ? 'fill-current' : ''}`} />
      </button>
      {altAction && (
        <button
          onClick={(e) => { e.stopPropagation(); altAction.run(); }}
          className="p-1 rounded-sm hover:bg-warning/20 text-warning"
          title={`${altAction.label} (Alt+${altAction.shortcut.toUpperCase()})`}
          aria-label={altAction.label}
        >
          <RotateCw className="w-3.5 h-3.5" />
        </button>
      )}
      <button
        onClick={(e) => { e.stopPropagation(); onOpen(); }}
        className="p-1 rounded-sm hover:bg-surface text-text-tertiary hover:text-text"
        title={t.commandPalette.openHint}
        aria-label={t.commandPalette.openHint}
      >
        <ExternalLink className="w-3.5 h-3.5" />
      </button>
      <button
        onClick={(e) => { e.stopPropagation(); onRun(); }}
        className="p-1 rounded-sm hover:bg-accent/20 text-accent"
        title={t.commandPalette.runHint}
        aria-label={t.commandPalette.runHint}
      >
        <Play className="w-3.5 h-3.5" />
      </button>
    </div>
  );
}

interface AssistantRowProps {
  query: string;
  selected: boolean;
  onHover: () => void;
  onClick: () => void;
  label: string;
  borderTop: boolean;
}

function AssistantRow({ query, selected, onHover, onClick, label, borderTop }: AssistantRowProps) {
  return (
    <div
      onMouseEnter={onHover}
      onClick={onClick}
      className={`flex items-center gap-3 px-4 py-2 cursor-pointer ${
        borderTop ? 'border-t border-border' : ''
      } ${selected ? 'bg-accent/10' : 'hover:bg-surface-raised'}`}
    >
      <span className="shrink-0 text-accent">
        <Sparkles className="w-4 h-4" />
      </span>
      <div className="flex-1 min-w-0">
        <div className="text-sm text-text truncate">
          {label}: <span className="text-text-tertiary">»{query}«</span>
        </div>
      </div>
      <span className="text-[10px] text-text-tertiary border border-border px-1.5 py-0.5 rounded-sm shrink-0">
        ⏎
      </span>
    </div>
  );
}

interface CollectFormProps {
  state: CollectingState;
  onChange: (values: Record<string, string>) => void;
  onSubmit: () => void;
  onCancel: () => void;
  loading: boolean;
  t: ReturnType<typeof useT>;
}

function CollectForm({ state, onChange, onSubmit, onCancel, loading, t }: CollectFormProps) {
  const firstRef = useRef<HTMLInputElement>(null);
  useEffect(() => { firstRef.current?.focus(); }, []);

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>, idx: number) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      if (idx === state.variables.length - 1 || (e.metaKey || e.ctrlKey)) {
        onSubmit();
      } else {
        const form = (e.target as HTMLInputElement).form;
        const nextInput = form?.elements[idx + 1] as HTMLInputElement | undefined;
        nextInput?.focus();
      }
    }
  };

  return (
    <form
      onSubmit={(e) => { e.preventDefault(); onSubmit(); }}
      className="divide-y divide-border"
    >
      <div className="flex items-center gap-2 px-4 py-3">
        <WorkflowIcon icon={state.workflow.icon} className="w-4 h-4 shrink-0" />
        <div className="flex-1 min-w-0">
          <div className="text-xs text-text-tertiary">{t.commandPalette.collectVariablesHeader}</div>
          <div className="text-sm text-text truncate">{state.workflow.name}</div>
        </div>
      </div>
      <div className="px-4 py-3 space-y-2 max-h-[50vh] overflow-y-auto">
        {state.variables.map((v, idx) => (
          <label key={v} className="block">
            <div className="text-[11px] text-text-tertiary mb-1 font-mono">{v}</div>
            <input
              ref={idx === 0 ? firstRef : undefined}
              type="text"
              value={state.values[v] ?? ''}
              onChange={(e) => onChange({ ...state.values, [v]: e.target.value })}
              onKeyDown={(e) => handleKeyDown(e, idx)}
              placeholder={state.workflow.defaultContext?.[v] ?? ''}
              className="w-full px-3 py-1.5 text-sm bg-surface-raised border border-border rounded-md text-text focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </label>
        ))}
      </div>
      <div className="flex items-center justify-between gap-2 px-4 py-3">
        <button
          type="button"
          onClick={onCancel}
          className="flex items-center gap-1 px-3 py-1.5 text-xs text-text-tertiary hover:text-text rounded-md hover:bg-surface-raised"
        >
          <ArrowLeft className="w-3.5 h-3.5" />
          {t.commandPalette.collectCancel}
        </button>
        <button
          type="submit"
          disabled={loading}
          className="flex items-center gap-1 px-3 py-1.5 text-xs font-medium text-white bg-accent rounded-md hover:bg-accent/90 disabled:opacity-50"
        >
          <Play className="w-3.5 h-3.5" />
          {t.commandPalette.collectStart}
          <span className="ml-1 text-[10px] border border-white/30 rounded-sm px-1">⏎</span>
        </button>
      </div>
    </form>
  );
}

function useDebounced<T>(value: T, delay = 250): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const h = setTimeout(() => setDebounced(value), delay);
    return () => clearTimeout(h);
  }, [value, delay]);
  return debounced;
}
