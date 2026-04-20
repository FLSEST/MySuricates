import { useEffect, useRef, useState, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Search, Play, ExternalLink, Sparkles, Star, ArrowLeft } from 'lucide-react';
import { api } from '../../api/client';
import { useWorkflows, useExecuteWorkflow } from '../../hooks/useWorkflows';
import { usePaletteHistory } from '../../hooks/usePaletteHistory';
import { extractWorkflowVariables } from '../../utils/extractWorkflowVariables';
import { useT } from '../../i18n';
import { WorkflowIcon } from '../../utils/workflowIcon';
import type { Workflow } from '../../types';

interface SemanticHit {
  workflowId: string;
  workflowName: string;
  reason: string;
  score: number;
}

interface CollectingState {
  workflow: Workflow;
  variables: string[];
  values: Record<string, string>;
}

function rankLocal(workflows: Workflow[], q: string): Workflow[] {
  const query = q.toLowerCase().trim();
  if (!query) return [];
  const scored = workflows
    .map((w) => {
      let score = 0;
      const name = w.name.toLowerCase();
      const desc = (w.description ?? '').toLowerCase();
      const cat = (w.category ?? '').toLowerCase();
      const tags = (w.tags ?? []).join(' ').toLowerCase();
      if (name === query) score += 10;
      if (name.startsWith(query)) score += 5;
      if (name.includes(query)) score += 3;
      if (desc.includes(query)) score += 1.5;
      if (tags.includes(query)) score += 2;
      if (cat.includes(query)) score += 1;
      return { w, score };
    })
    .filter((s) => s.score > 0)
    .sort((a, b) => b.score - a.score)
    .slice(0, 10);
  return scored.map((s) => s.w);
}

interface CommandPaletteProps {
  open: boolean;
  onClose: () => void;
}

export function CommandPalette({ open, onClose }: CommandPaletteProps) {
  const t = useT();
  const navigate = useNavigate();
  const inputRef = useRef<HTMLInputElement>(null);
  const [query, setQuery] = useState('');
  const [selectedIdx, setSelectedIdx] = useState(0);
  const [collecting, setCollecting] = useState<CollectingState | null>(null);
  const { data: workflows = [] } = useWorkflows();
  const executeWorkflow = useExecuteWorkflow();
  const { recents, pins, pushRecent, togglePin, isPinned } = usePaletteHistory();

  const debouncedQuery = useDebounced(query, 250);

  const semantic = useQuery({
    queryKey: ['search', 'semantic', debouncedQuery],
    queryFn: async () =>
      api.post<SemanticHit[]>('/search/workflows/semantic', {
        query: debouncedQuery,
        limit: 5,
      }),
    enabled: open && debouncedQuery.trim().length >= 3,
    staleTime: 60_000,
  });

  const localMatches = useMemo(() => rankLocal(workflows, query), [workflows, query]);

  const combined = useMemo(() => {
    const localIds = new Set(localMatches.map((w) => w.id));
    const semanticOnly = (semantic.data ?? []).filter((h) => !localIds.has(h.workflowId));
    return { local: localMatches, semantic: semanticOnly };
  }, [localMatches, semantic.data]);

  const pinnedWorkflows = useMemo(
    () => pins.map((id) => workflows.find((w) => w.id === id)).filter((w): w is Workflow => !!w),
    [pins, workflows],
  );
  const recentWorkflows = useMemo(() => {
    const pinSet = new Set(pins);
    return recents
      .filter((id) => !pinSet.has(id))
      .map((id) => workflows.find((w) => w.id === id))
      .filter((w): w is Workflow => !!w)
      .slice(0, 5);
  }, [recents, pins, workflows]);

  const isEmptyQuery = query.trim().length === 0;
  const totalItems = isEmptyQuery
    ? pinnedWorkflows.length + recentWorkflows.length
    : combined.local.length + combined.semantic.length;

  useEffect(() => {
    if (!open) return;
    inputRef.current?.focus();
    setSelectedIdx(0);
  }, [open]);

  useEffect(() => {
    if (!open) {
      setQuery('');
      setCollecting(null);
    }
  }, [open]);

  useEffect(() => {
    setSelectedIdx(0);
  }, [query]);

  const itemAt = (idx: number): Workflow | null => {
    if (isEmptyQuery) {
      if (idx < pinnedWorkflows.length) return pinnedWorkflows[idx] ?? null;
      return recentWorkflows[idx - pinnedWorkflows.length] ?? null;
    }
    if (idx < combined.local.length) return combined.local[idx] ?? null;
    const hit = combined.semantic[idx - combined.local.length];
    if (!hit) return null;
    return workflows.find((w) => w.id === hit.workflowId) ?? null;
  };

  const openDetail = (id: string) => {
    onClose();
    navigate(`/workflows/${id}`);
  };

  const startWithContext = (w: Workflow, context: Record<string, string>) => {
    pushRecent(w.id);
    executeWorkflow.mutate(
      { id: w.id, context },
      {
        onSuccess: (run) => {
          setCollecting(null);
          onClose();
          navigate(`/workflows/${w.id}/run/${run.id}`);
        },
      },
    );
  };

  const runWorkflow = (w: Workflow) => {
    const variables = extractWorkflowVariables(w.blocks);
    if (variables.length === 0) {
      startWithContext(w, {});
      return;
    }
    const defaults: Record<string, string> = {};
    const ctx = w.defaultContext ?? {};
    for (const v of variables) {
      defaults[v] = typeof ctx[v] === 'string' ? (ctx[v] as string) : '';
    }
    setCollecting({ workflow: w, variables, values: defaults });
  };

  const submitCollected = () => {
    if (!collecting) return;
    startWithContext(collecting.workflow, collecting.values);
  };

  useEffect(() => {
    if (!open) return;
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
        const item = itemAt(selectedIdx);
        if (item) {
          if (e.shiftKey) openDetail(item.id);
          else runWorkflow(item);
        }
      }
    };
    window.addEventListener('keydown', handleKey);
    return () => window.removeEventListener('keydown', handleKey);
  }, [open, selectedIdx, totalItems, combined, collecting, isEmptyQuery, pinnedWorkflows, recentWorkflows]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!open) return null;

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
              <input
                ref={inputRef}
                type="text"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder={t.commandPalette.placeholder}
                className="flex-1 bg-transparent text-sm text-text placeholder:text-text-tertiary focus:outline-none"
              />
              <span className="text-[10px] text-text-tertiary border border-border px-1.5 py-0.5 rounded-sm">
                ESC
              </span>
            </div>

            <div className="max-h-[60vh] overflow-y-auto">
              {totalItems === 0 && !isEmptyQuery && (
                <div className="px-4 py-6 text-center text-sm text-text-tertiary">
                  {t.commandPalette.noResults}
                </div>
              )}

              {isEmptyQuery && pinnedWorkflows.length > 0 && (
                <Section
                  title={t.commandPalette.pinned}
                  icon={<Star className="w-3 h-3 fill-current" />}
                >
                  {pinnedWorkflows.map((w, idx) => (
                    <PaletteRow
                      key={w.id}
                      workflow={w}
                      selected={selectedIdx === idx}
                      pinned
                      onHover={() => setSelectedIdx(idx)}
                      onRun={() => runWorkflow(w)}
                      onOpen={() => openDetail(w.id)}
                      onTogglePin={() => togglePin(w.id)}
                      t={t}
                    />
                  ))}
                </Section>
              )}

              {isEmptyQuery && recentWorkflows.length > 0 && (
                <Section title={t.commandPalette.recent} borderTop={pinnedWorkflows.length > 0}>
                  {recentWorkflows.map((w, i) => {
                    const absIdx = pinnedWorkflows.length + i;
                    return (
                      <PaletteRow
                        key={w.id}
                        workflow={w}
                        selected={selectedIdx === absIdx}
                        pinned={false}
                        onHover={() => setSelectedIdx(absIdx)}
                        onRun={() => runWorkflow(w)}
                        onOpen={() => openDetail(w.id)}
                        onTogglePin={() => togglePin(w.id)}
                        t={t}
                      />
                    );
                  })}
                </Section>
              )}

              {!isEmptyQuery && combined.local.length > 0 && (
                <Section title={t.commandPalette.workflows}>
                  {combined.local.map((w, idx) => (
                    <PaletteRow
                      key={w.id}
                      workflow={w}
                      selected={selectedIdx === idx}
                      pinned={isPinned(w.id)}
                      onHover={() => setSelectedIdx(idx)}
                      onRun={() => runWorkflow(w)}
                      onOpen={() => openDetail(w.id)}
                      onTogglePin={() => togglePin(w.id)}
                      t={t}
                    />
                  ))}
                </Section>
              )}

              {!isEmptyQuery && combined.semantic.length > 0 && (
                <Section
                  title={t.commandPalette.byMeaning}
                  icon={<Sparkles className="w-3 h-3" />}
                  borderTop={combined.local.length > 0}
                >
                  {combined.semantic.map((hit, i) => {
                    const w = workflows.find((x) => x.id === hit.workflowId);
                    if (!w) return null;
                    const absIdx = combined.local.length + i;
                    return (
                      <PaletteRow
                        key={hit.workflowId}
                        workflow={w}
                        reason={hit.reason}
                        selected={selectedIdx === absIdx}
                        pinned={isPinned(w.id)}
                        onHover={() => setSelectedIdx(absIdx)}
                        onRun={() => runWorkflow(w)}
                        onOpen={() => openDetail(w.id)}
                        onTogglePin={() => togglePin(w.id)}
                        t={t}
                      />
                    );
                  })}
                </Section>
              )}

              {isEmptyQuery && pinnedWorkflows.length === 0 && recentWorkflows.length === 0 && (
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

interface PaletteRowProps {
  workflow: Workflow;
  reason?: string;
  selected: boolean;
  pinned: boolean;
  onHover: () => void;
  onRun: () => void;
  onOpen: () => void;
  onTogglePin: () => void;
  t: ReturnType<typeof useT>;
}

function PaletteRow({ workflow, reason, selected, pinned, onHover, onRun, onOpen, onTogglePin, t }: PaletteRowProps) {
  return (
    <div
      onMouseEnter={onHover}
      onClick={onRun}
      className={`flex items-center gap-3 px-4 py-2 cursor-pointer ${
        selected ? 'bg-accent/10' : 'hover:bg-surface-raised'
      }`}
    >
      <span className="shrink-0">
        <WorkflowIcon icon={workflow.icon} className="w-4 h-4" />
      </span>
      <div className="flex-1 min-w-0">
        <div className="text-sm text-text truncate">{workflow.name}</div>
        {(reason ?? workflow.description) && (
          <div className="text-xs text-text-tertiary truncate">{reason ?? workflow.description}</div>
        )}
      </div>
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
