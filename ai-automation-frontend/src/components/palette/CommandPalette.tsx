import { useState } from 'react';
import { useLocation } from 'react-router-dom';
import { CONTEXT_ORDER, resolveContextId } from './resolveContext';
import { useWorkflowContext } from './contexts/workflowContext';
import { useRunContext } from './contexts/runContext';
import { useProjectContext } from './contexts/projectContext';
import type { PaletteContextId } from './contexts/types';
import { PaletteDialog, type PaletteDialogProps } from './PaletteDialog';

interface CommandPaletteProps {
  open: boolean;
  onClose: () => void;
}

/**
 * Outer wrapper: keeps the query across openings and mounts OpenPalette only while open.
 */
export function CommandPalette({ open, onClose }: CommandPaletteProps) {
  const [query, setQuery] = useState('');
  if (!open) return null;
  return <OpenPalette onClose={onClose} query={query} setQuery={setQuery} />;
}

/**
 * Resolves the context by route when the palette opens (it mounts with every opening, so a
 * Tab switch survives re-renders but not a reopen) and remounts PaletteBody on context change
 * via `key`. Remounting guarantees a stable hook order inside the inner body.
 */
function OpenPalette({ onClose, query, setQuery }: { onClose: () => void; query: string; setQuery: (q: string) => void }) {
  const location = useLocation();
  const [contextId, setContextId] = useState<PaletteContextId>(() => resolveContextId(location.pathname));

  const cycleContext = (direction: 1 | -1) => {
    setContextId((c) => {
      const idx = CONTEXT_ORDER.indexOf(c);
      const next = (idx + direction + CONTEXT_ORDER.length) % CONTEXT_ORDER.length;
      return CONTEXT_ORDER[next] ?? c;
    });
  };

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

type BodyProps = Omit<PaletteDialogProps, 'context'>;

function PaletteBody(props: BodyProps) {
  // Exactly one context hook per variant — satisfies react-hooks/rules-of-hooks.
  if (props.contextId === 'run') return <RunBody {...props} />;
  if (props.contextId === 'project') return <ProjectBody {...props} />;
  return <WorkflowBody {...props} />;
}

function WorkflowBody(props: BodyProps) {
  const context = useWorkflowContext();
  return <PaletteDialog context={context} {...props} />;
}

function RunBody(props: BodyProps) {
  const context = useRunContext();
  return <PaletteDialog context={context} {...props} />;
}

function ProjectBody(props: BodyProps) {
  const context = useProjectContext();
  return <PaletteDialog context={context} {...props} />;
}
