import type { KeyboardEvent, ReactNode } from 'react';
import {
  Bot, CheckCircle2, Circle, CircleDot, GitBranch, HeartPulse, Loader2, ShieldCheck, UserRound, Wrench, XCircle, Zap,
} from 'lucide-react';
import { truncate, useNodeText } from './relationMapText';
import type { MapNode, Tone } from './goalRelationLayout';
import type { TodoPriority } from '../../types';

/** Farben nur aus Tokens (TODO c6ea8f5c): Status und Rollen, Workflows bleiben neutral. */
const TONE_STROKE: Record<Tone, string> = {
  human: 'var(--color-human)',
  ai: 'var(--color-ai)',
  neutral: 'var(--color-border-strong)',
  success: 'var(--color-success)',
  warning: 'var(--color-warning)',
  danger: 'var(--color-danger)',
  info: 'var(--color-info)',
  muted: 'var(--color-border-strong)',
};

const TONE_FILL: Record<Tone, string> = {
  human: 'var(--color-human-subtle)',
  ai: 'var(--color-ai-subtle)',
  neutral: 'var(--color-surface)',
  success: 'var(--color-success-subtle)',
  warning: 'var(--color-warning-subtle)',
  danger: 'var(--color-danger-subtle)',
  info: 'var(--color-info-subtle)',
  muted: 'var(--color-surface)',
};

const PRIORITY_FILL: Record<TodoPriority, string> = {
  CRITICAL: 'var(--color-danger)',
  HIGH: 'var(--color-danger)',
  MEDIUM: 'var(--color-warning)',
  LOW: 'var(--color-text-tertiary)',
};

const ICON = 14;

/** Symbol eines Knotens – dieselben Zeichen wie in `GoalBadges` und der Legende. */
function nodeIcon(node: MapNode): ReactNode {
  const props = { x: node.x + 8, y: node.y + (node.h - ICON) / 2, width: ICON, height: ICON, 'aria-hidden': true };
  const style = { color: TONE_STROKE[node.tone] === TONE_STROKE.neutral ? 'var(--color-text-secondary)' : TONE_STROKE[node.tone] };
  switch (node.kind) {
    case 'origin':
      switch (node.originKind) {
        case 'USER': return <UserRound {...props} style={style} />;
        case 'AGENT': return <Bot {...props} style={style} />;
        case 'AUDIT': return <ShieldCheck {...props} style={style} />;
        case 'HEALTH': return <HeartPulse {...props} style={style} />;
        default: return <GitBranch {...props} style={style} />;
      }
    case 'worker':
      return node.workerKinds?.includes('TRIGGER') ? <Zap {...props} style={style} /> : <Wrench {...props} style={style} />;
    case 'result':
      if (node.tone === 'success') return <CheckCircle2 {...props} style={style} />;
      if (node.tone === 'danger' || node.tone === 'warning') return <XCircle {...props} style={style} />;
      if (node.tone === 'info') return <Loader2 {...props} style={style} />;
      return <Circle {...props} style={style} />;
    case 'todo':
      if (node.todo?.status === 'DONE') return <CheckCircle2 {...props} style={{ color: 'var(--color-success)' }} />;
      if (node.todo?.status === 'IN_PROGRESS') return <CircleDot {...props} style={{ color: 'var(--color-info)' }} />;
      return <Circle {...props} style={{ color: 'var(--color-text-tertiary)' }} />;
    default:
      return null;
  }
}

interface NodeProps {
  node: MapNode;
  dimmed: boolean;
  onHover: (id: string | null) => void;
  onActivate?: () => void;
  onAssign?: () => void;
  assignLabel: string;
}

const onKey = (action?: () => void) => (e: KeyboardEvent) => {
  if (action && (e.key === 'Enter' || e.key === ' ')) {
    e.preventDefault();
    action();
  }
};

export function RelationMapNode({ node, dimmed, onHover, onActivate, onAssign, assignLabel }: NodeProps) {
  const text = useNodeText()(node);
  const opacity = dimmed ? 0.25 : 1;

  if (node.kind === 'gap') {
    return (
      <g data-node={node.id} opacity={opacity} onMouseEnter={() => onHover(node.id)} onMouseLeave={() => onHover(null)}>
        <title>{text.title}</title>
        <text x={node.x + 4} y={node.y + node.h / 2 + 4} className="fill-text-tertiary text-[11px] italic">{text.label}</text>
      </g>
    );
  }

  const assignWidth = onAssign ? 78 : 0;
  const maxChars = Math.floor((node.w - 34 - assignWidth) / 6.6);
  return (
    <g
      data-node={node.id}
      opacity={opacity}
      className={onActivate ? 'cursor-pointer' : undefined}
      role={onActivate ? 'button' : undefined}
      tabIndex={onActivate ? 0 : undefined}
      aria-label={onActivate ? text.title : undefined}
      onClick={onActivate}
      onKeyDown={onKey(onActivate)}
      onMouseEnter={() => onHover(node.id)}
      onMouseLeave={() => onHover(null)}
      onFocus={() => onHover(node.id)}
      onBlur={() => onHover(null)}
    >
      <title>{text.title}</title>
      <rect
        x={node.x} y={node.y} width={node.w} height={node.h} rx={8}
        style={{ fill: TONE_FILL[node.tone], stroke: TONE_STROKE[node.tone] }}
        strokeWidth={node.kind === 'todo' ? 1.2 : 1.4}
        strokeDasharray={node.candidate ? '5 4' : undefined}
      />
      {node.kind === 'todo' && node.todo && (
        <circle cx={node.x + node.w - 10 - assignWidth} cy={node.y + 10} r={3.5} style={{ fill: PRIORITY_FILL[node.todo.priority] }}>
          <title>{node.todo.priority}</title>
        </circle>
      )}
      {nodeIcon(node)}
      <text x={node.x + 28} y={node.y + node.h / 2 + 4}
        className={`text-[12px] ${node.candidate ? 'fill-text-secondary' : 'fill-text'} ${node.todo?.status === 'DONE' ? 'line-through' : ''}`}>
        {truncate(text.label, Math.max(8, maxChars))}
      </text>
      {onAssign && (
        <g
          role="button"
          tabIndex={0}
          aria-label={`${assignLabel}: ${node.label}`}
          className="cursor-pointer"
          onClick={(e) => { e.stopPropagation(); onAssign(); }}
          onKeyDown={(e) => { e.stopPropagation(); onKey(onAssign)(e); }}
        >
          <rect x={node.x + node.w - assignWidth} y={node.y + 6} width={assignWidth - 6} height={node.h - 12} rx={6}
            style={{ fill: 'var(--color-accent-subtle)', stroke: 'var(--color-accent)' }} strokeWidth={1} />
          <text x={node.x + node.w - assignWidth / 2 - 3} y={node.y + node.h / 2 + 4} textAnchor="middle" className="fill-accent text-[11px] font-medium">
            + {assignLabel}
          </text>
        </g>
      )}
    </g>
  );
}
