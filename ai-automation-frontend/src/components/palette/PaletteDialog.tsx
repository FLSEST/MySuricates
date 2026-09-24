import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Search, Star, X } from 'lucide-react';
import { useExecuteWorkflow } from '../../hooks/useWorkflows';
import { usePaletteHistory } from '../../hooks/usePaletteHistory';
import { useAssistantStore } from '../../stores/assistantStore';
import { useT } from '../../i18n';
import type { Workflow } from '../../types';
import { CONTEXT_ORDER } from './resolveContext';
import type { PaletteContextHandle, PaletteContextId, PaletteItem } from './contexts/types';
import { parsePaletteQuery } from './queryParser';
import { PaletteRow } from './PaletteRow';
import { Section } from './Section';
import { AssistantRow } from './AssistantRow';
import { CollectForm, type CollectingState } from './CollectForm';
import { useDebounced } from '../../hooks/useDebounced';
import { useModuleEnabled } from '../../hooks/useModules';
import { usePaletteSelection } from './usePaletteSelection';

export interface PaletteDialogProps {
  context: PaletteContextHandle;
  onClose: () => void;
  query: string;
  setQuery: (q: string) => void;
  contextId: PaletteContextId;
  cycleContext: (direction: 1 | -1) => void;
}

/**
 * The open palette for one context: context tabs, search field, pinned/recent rows or the results
 * plus the assistant row, and the variable form before a run that needs input.
 */
export function PaletteDialog({ context, onClose, query, setQuery, contextId, cycleContext }: PaletteDialogProps) {
  const t = useT();
  const navigate = useNavigate();
  const inputRef = useRef<HTMLInputElement>(null);
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

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

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

  const { selectedIdx, select, assistantIdx } = usePaletteSelection({
    isEmptyQuery, pinnedItems, recentItems, results, hasAssistantRow,
    keys: {
      close: onClose,
      cycleContext,
      enter: enterItem,
      open: openItem,
      openInNewTab: openItemInNewTab,
      askAssistant,
      altAction: context.altAction && { shortcut: context.altAction.shortcut, run: runAltAction },
      collecting: collecting !== null,
      cancelCollecting: () => setCollecting(null),
    },
  });

  // A new query starts the selection at the top again
  const changeQuery = (q: string) => {
    if (q !== query) select(0);
    setQuery(q);
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

  // One row of the lists below; `idx` is its position in the selection order
  const row = (item: PaletteItem, idx: number, pinned: boolean) => (
    <PaletteRow
      key={item.id}
      item={item}
      selected={selectedIdx === idx}
      pinned={pinned}
      onHover={() => select(idx)}
      onRun={() => enterItem(item)}
      onOpen={() => openItem(item)}
      onTogglePin={() => togglePin(item.id)}
      numberHint={idx < 9 ? idx + 1 : undefined}
      t={t}
      altAction={context.altAction ? { label: context.altAction.label, shortcut: context.altAction.shortcut, run: () => runAltAction(item) } : undefined}
    />
  );

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
                  onClick={() => changeQuery(parsed.query)}
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
                onChange={(e) => changeQuery(e.target.value)}
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
                  {pinnedItems.map((item, idx) => row(item, idx, true))}
                </Section>
              )}

              {isEmptyQuery && recentItems.length > 0 && (
                <Section title={t.commandPalette.recent} borderTop={pinnedItems.length > 0}>
                  {recentItems.map((item, i) => row(item, pinnedItems.length + i, false))}
                </Section>
              )}

              {!isEmptyQuery && results.length > 0 && (
                <div className="py-1">
                  {results.map((item, idx) => row(item, idx, isPinned(item.id)))}
                </div>
              )}

              {!isEmptyQuery && hasAssistantRow && (
                <AssistantRow
                  query={query.trim()}
                  selected={selectedIdx === assistantIdx}
                  onHover={() => select(assistantIdx)}
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
