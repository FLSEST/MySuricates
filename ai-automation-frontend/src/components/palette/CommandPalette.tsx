import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { Search, Star, X } from 'lucide-react';
import { useExecuteWorkflow } from '../../hooks/useWorkflows';
import { usePaletteHistory } from '../../hooks/usePaletteHistory';
import { useAssistantStore } from '../../stores/assistantStore';
import { useT } from '../../i18n';
import type { Workflow } from '../../types';
import { resolveContextId } from './resolveContext';
import { useWorkflowContext } from './contexts/workflowContext';
import { useRunContext } from './contexts/runContext';
import { useProjectContext } from './contexts/projectContext';
import type { PaletteContextHandle, PaletteContextId, PaletteItem } from './contexts/types';
import { parsePaletteQuery } from './queryParser';
import { PaletteRow } from './PaletteRow';
import { Section } from './Section';
import { AssistantRow } from './AssistantRow';
import { CollectForm, type CollectingState } from './CollectForm';
import { useDebounced } from './useDebounced';
import { useModuleEnabled } from '../../hooks/useModules';

const CONTEXT_ORDER: PaletteContextId[] = ['workflow', 'run', 'project'];


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
  const routeContextId = resolveContextId(location.pathname);
  const [contextId, setContextId] = useState<PaletteContextId>(routeContextId);
  const [query, setQuery] = useState('');

  useEffect(() => {
    if (open) setContextId(routeContextId);
    // Only reset to route context when the palette opens; Tab-switch must survive re-renders.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const cycleContext = (direction: 1 | -1) => {
    setContextId((c) => {
      const idx = CONTEXT_ORDER.indexOf(c);
      const next = (idx + direction + CONTEXT_ORDER.length) % CONTEXT_ORDER.length;
      return CONTEXT_ORDER[next] ?? c;
    });
  };

  if (!open) return null;
  return (
    <PaletteBody
      key={contextId}
      contextId={contextId}
      onClose={onClose}
      query={query}
      setQuery={setQuery}
      cycleContext={cycleContext}
    />
  );
}

interface PaletteBodyProps {
  contextId: PaletteContextId;
  onClose: () => void;
  query: string;
  setQuery: (q: string) => void;
  cycleContext: (direction: 1 | -1) => void;
}

function PaletteBody({ contextId, onClose, query, setQuery, cycleContext }: PaletteBodyProps) {
  // Exactly one context hook per variant — satisfies react-hooks/rules-of-hooks.
  if (contextId === 'run') return <RunBody onClose={onClose} query={query} setQuery={setQuery} contextId={contextId} cycleContext={cycleContext} />;
  if (contextId === 'project') return <ProjectBody onClose={onClose} query={query} setQuery={setQuery} contextId={contextId} cycleContext={cycleContext} />;
  return <WorkflowBody onClose={onClose} query={query} setQuery={setQuery} contextId={contextId} cycleContext={cycleContext} />;
}

interface BodyProps {
  onClose: () => void;
  query: string;
  setQuery: (q: string) => void;
  contextId: PaletteContextId;
  cycleContext: (direction: 1 | -1) => void;
}

function WorkflowBody(props: BodyProps) {
  const context = useWorkflowContext();
  return <InnerPalette context={context} {...props} />;
}

function RunBody(props: BodyProps) {
  const context = useRunContext();
  return <InnerPalette context={context} {...props} />;
}

function ProjectBody(props: BodyProps) {
  const context = useProjectContext();
  return <InnerPalette context={context} {...props} />;
}

interface InnerPaletteProps extends BodyProps {
  context: PaletteContextHandle;
}

function InnerPalette({ context, onClose, query, setQuery, contextId, cycleContext }: InnerPaletteProps) {
  const t = useT();
  const navigate = useNavigate();
  const inputRef = useRef<HTMLInputElement>(null);
  const [selectedIdx, setSelectedIdx] = useState(0);
  const [collecting, setCollecting] = useState<CollectingState | null>(null);

  const executeWorkflow = useExecuteWorkflow();
  const { recents, pins, pushRecent, togglePin, isPinned } = usePaletteHistory(context.id);
  const setAssistantInput = useAssistantStore((s) => s.setPendingInput);
  const setAssistantOpen = useAssistantStore((s) => s.setOpen);
  const assistantModule = useModuleEnabled('assistant');

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
  const hasAssistantRow = assistantModule && query.trim().length >= 2;
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
      if (e.key === 'Tab') {
        e.preventDefault();
        cycleContext(e.shiftKey ? -1 : 1);
        return;
      }
      if (e.altKey && /^[1-9]$/.test(e.key)) {
        // Alt+N jumps directly to result row N (1-indexed). Works for result rows and
        // the trailing assistant row; pinned/recent sections are skipped.
        e.preventDefault();
        const n = parseInt(e.key, 10) - 1;
        if (isEmptyQuery) {
          const item = itemAt(n);
          if (item) {
            setSelectedIdx(n);
            enterItem(item);
          }
          return;
        }
        if (n === assistantIdx) {
          setSelectedIdx(n);
          askAssistant();
          return;
        }
        const item = itemAt(n);
        if (item) {
          setSelectedIdx(n);
          enterItem(item);
        }
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
            <div className="flex items-center gap-1 px-2 py-1.5 border-b border-border">
              {CONTEXT_ORDER.map((cid) => (
                <button
                  key={cid}
                  type="button"
                  onClick={() => {
                    const diff = (CONTEXT_ORDER.indexOf(cid) - CONTEXT_ORDER.indexOf(contextId) + CONTEXT_ORDER.length) % CONTEXT_ORDER.length;
                    if (diff === 0) return;
                    for (let i = 0; i < diff; i += 1) cycleContext(1);
                  }}
                  className={`px-2 py-1 text-[11px] rounded-md transition-colors ${
                    cid === contextId
                      ? 'bg-accent/15 text-accent font-medium'
                      : 'text-text-tertiary hover:text-text hover:bg-surface-raised'
                  }`}
                >
                  {t.commandPalette.contexts[cid].triggerLabel.replace(/…$/, '').replace(/\.\.\.$/, '')}
                </button>
              ))}
              <span className="ml-auto text-[10px] text-text-tertiary border border-border px-1.5 py-0.5 rounded-sm">
                {t.commandPalette.switchContextHint}
              </span>
            </div>
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
                      numberHint={idx < 9 ? idx + 1 : undefined}
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
                        numberHint={absIdx < 9 ? absIdx + 1 : undefined}
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
                      numberHint={idx < 9 ? idx + 1 : undefined}
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
                  numberHint={assistantIdx < 9 ? assistantIdx + 1 : undefined}
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
