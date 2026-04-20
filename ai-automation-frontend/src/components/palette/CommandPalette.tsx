import { useEffect, useRef, useState, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Search, Play, ExternalLink, Sparkles } from 'lucide-react';
import { api } from '../../api/client';
import { useWorkflows, useExecuteWorkflow } from '../../hooks/useWorkflows';
import { extractWorkflowVariables } from '../../utils/extractWorkflowVariables';
import { ExecuteDialog } from '../workflow/ExecuteDialog';
import { useT } from '../../i18n';
import { WorkflowIcon } from '../../utils/workflowIcon';
import type { Workflow } from '../../types';

interface SemanticHit {
  workflowId: string;
  workflowName: string;
  reason: string;
  score: number;
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
  const { data: workflows = [] } = useWorkflows();
  const executeWorkflow = useExecuteWorkflow();
  const [executeDialog, setExecuteDialog] = useState<{
    open: boolean;
    workflow: Workflow | null;
    variables: string[];
  }>({ open: false, workflow: null, variables: [] });

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

  // Combine results: local first, then semantic-only (not already in local).
  const combined = useMemo(() => {
    const localIds = new Set(localMatches.map((w) => w.id));
    const semanticOnly = (semantic.data ?? []).filter((h) => !localIds.has(h.workflowId));
    return {
      local: localMatches,
      semantic: semanticOnly,
    };
  }, [localMatches, semantic.data]);

  const totalItems = combined.local.length + combined.semantic.length;

  useEffect(() => {
    if (!open) return;
    inputRef.current?.focus();
    setSelectedIdx(0);
  }, [open]);

  useEffect(() => {
    setSelectedIdx(0);
  }, [query]);

  const itemAt = (idx: number): Workflow | null => {
    if (idx < combined.local.length) return combined.local[idx] ?? null;
    const semIdx = idx - combined.local.length;
    const hit = combined.semantic[semIdx];
    if (!hit) return null;
    return workflows.find((w) => w.id === hit.workflowId) ?? null;
  };

  const openDetail = (id: string) => {
    onClose();
    navigate(`/workflows/${id}`);
  };

  const runWorkflow = (w: Workflow) => {
    const variables = extractWorkflowVariables(w.blocks);
    if (variables.length > 0) {
      setExecuteDialog({ open: true, workflow: w, variables });
      return;
    }
    executeWorkflow.mutate(
      { id: w.id },
      {
        onSuccess: (run) => {
          onClose();
          navigate(`/workflows/${w.id}/run/${run.id}`);
        },
      },
    );
  };

  useEffect(() => {
    if (!open) return;
    const handleKey = (e: KeyboardEvent) => {
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
  }, [open, selectedIdx, totalItems, combined]); // eslint-disable-line react-hooks/exhaustive-deps

  const handleExecuteWithContext = (context: Record<string, string>) => {
    const w = executeDialog.workflow;
    if (!w) return;
    executeWorkflow.mutate(
      { id: w.id, context },
      {
        onSuccess: (run) => {
          setExecuteDialog({ open: false, workflow: null, variables: [] });
          onClose();
          navigate(`/workflows/${w.id}/run/${run.id}`);
        },
      },
    );
  };

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
          {totalItems === 0 && query.trim() && (
            <div className="px-4 py-6 text-center text-sm text-text-tertiary">
              {t.commandPalette.noResults}
            </div>
          )}

          {combined.local.length > 0 && (
            <div className="py-1">
              <div className="px-4 py-1 text-[10px] font-semibold uppercase tracking-wider text-text-tertiary">
                {t.commandPalette.workflows}
              </div>
              {combined.local.map((w, idx) => (
                <PaletteRow
                  key={w.id}
                  workflow={w}
                  selected={selectedIdx === idx}
                  onHover={() => setSelectedIdx(idx)}
                  onRun={() => runWorkflow(w)}
                  onOpen={() => openDetail(w.id)}
                  tHint={t.commandPalette.runHint}
                  tOpenHint={t.commandPalette.openHint}
                />
              ))}
            </div>
          )}

          {combined.semantic.length > 0 && (
            <div className="py-1 border-t border-border">
              <div className="px-4 py-1 text-[10px] font-semibold uppercase tracking-wider text-text-tertiary flex items-center gap-1">
                <Sparkles className="w-3 h-3" />
                {t.commandPalette.byMeaning}
              </div>
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
                    onHover={() => setSelectedIdx(absIdx)}
                    onRun={() => runWorkflow(w)}
                    onOpen={() => openDetail(w.id)}
                    tHint={t.commandPalette.runHint}
                    tOpenHint={t.commandPalette.openHint}
                  />
                );
              })}
            </div>
          )}

          {query.trim().length === 0 && (
            <div className="px-4 py-6 text-center text-xs text-text-tertiary">
              {t.commandPalette.hint}
            </div>
          )}
        </div>
      </div>

      <ExecuteDialog
        open={executeDialog.open}
        onClose={() => setExecuteDialog({ open: false, workflow: null, variables: [] })}
        onExecute={handleExecuteWithContext}
        variables={executeDialog.variables}
        defaultValues={executeDialog.workflow?.defaultContext}
        loading={executeWorkflow.isPending}
      />
    </div>
  );
}

interface PaletteRowProps {
  workflow: Workflow;
  reason?: string;
  selected: boolean;
  onHover: () => void;
  onRun: () => void;
  onOpen: () => void;
  tHint: string;
  tOpenHint: string;
}

function PaletteRow({ workflow, reason, selected, onHover, onRun, onOpen, tHint, tOpenHint }: PaletteRowProps) {
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
        onClick={(e) => { e.stopPropagation(); onOpen(); }}
        className="p-1 rounded-sm hover:bg-surface text-text-tertiary hover:text-text"
        title={tOpenHint}
        aria-label={tOpenHint}
      >
        <ExternalLink className="w-3.5 h-3.5" />
      </button>
      <button
        onClick={(e) => { e.stopPropagation(); onRun(); }}
        className="p-1 rounded-sm hover:bg-accent/20 text-accent"
        title={tHint}
        aria-label={tHint}
      >
        <Play className="w-3.5 h-3.5" />
      </button>
    </div>
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
