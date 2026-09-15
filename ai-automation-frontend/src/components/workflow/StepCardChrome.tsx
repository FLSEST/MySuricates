import type { ReactNode } from 'react';
import {
  Clock, Terminal, Sparkles, Brain, Repeat, GitBranch, RefreshCw, ShieldCheck, Layers,
  MessageSquare, Route, RotateCcw, Wrench, Plug, FileText, ListPlus, SquarePen, ListChecks,
  Bot, Cog, User, CircleHelp, Shrink,
} from 'lucide-react';
import { Badge } from '../ui';
import { useT } from '../../i18n';
import { formatDuration } from '../../utils/formatTime';
import { BLOCK_TYPES, type BlockType, type StepResult } from '../../types';
import { stepBlockName, stepBlockType, stepKind, type StepKind } from './stepMetadata';

const ICON = 'w-3 h-3';

const BLOCK_TYPE_ICONS: Record<BlockType, ReactNode> = {
  shell: <Terminal className={ICON} />,
  simple: <Sparkles className={ICON} />,
  agentic: <Brain className={ICON} />,
  foreach: <Repeat className={ICON} />,
  conditional: <GitBranch className={ICON} />,
  while: <RefreshCw className={ICON} />,
  approval: <ShieldCheck className={ICON} />,
  'sub-workflow': <Layers className={ICON} />,
  'human-input': <MessageSquare className={ICON} />,
  switch: <Route className={ICON} />,
  retry: <RotateCcw className={ICON} />,
  'skill-capability': <Wrench className={ICON} />,
  integration: <Plug className={ICON} />,
  document: <FileText className={ICON} />,
  'todo-create': <ListPlus className={ICON} />,
  'todo-update': <SquarePen className={ICON} />,
  'todo-close': <ListChecks className={ICON} />,
  compress: <Shrink className={ICON} />,
  'compress-ai': <Shrink className={ICON} />,
};

// Theme tokens only, so every theme applies: AI and human have their own role colours; deterministic
// stays neutral on purpose (the normal case — colour marks what is special), unknown is dashed.
const KIND_STYLES: Record<StepKind, { stripe: string; chip: string; icon: ReactNode }> = {
  ai: {
    stripe: 'border-l-ai',
    chip: 'bg-ai-subtle text-ai border-ai/30',
    icon: <Bot className={ICON} />,
  },
  deterministic: {
    stripe: 'border-l-border-strong',
    chip: 'bg-surface-raised text-text-secondary border-border-strong',
    icon: <Cog className={ICON} />,
  },
  human: {
    stripe: 'border-l-human',
    chip: 'bg-human-subtle text-human border-human/30',
    icon: <User className={ICON} />,
  },
  unknown: {
    stripe: 'border-dashed border-l-border',
    chip: 'bg-surface-raised text-text-tertiary border-border border-dashed',
    icon: <CircleHelp className={ICON} />,
  },
};

function isBlockType(type: string): type is BlockType {
  return (BLOCK_TYPES as readonly string[]).includes(type);
}

/** Card root with a left stripe coloured by the step kind (AI / deterministic / human). */
export function StepCardFrame({ step, children }: { step: StepResult; children: ReactNode }) {
  const kind = stepKind(step);
  return (
    <div
      id={`step-${step.stepIndex}`}
      className={`flex gap-3 py-3 pl-3 border-l-2 ${KIND_STYLES[kind].stripe}`}
      data-step-kind={kind}
    >
      {children}
    </div>
  );
}

export function StepKindChip({ kind }: { kind: StepKind }) {
  const labels = useT().workflows.execution.stepKind;
  return (
    <span
      className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium border ${KIND_STYLES[kind].chip}`}
      title={labels[`${kind}Hint`]}
    >
      {KIND_STYLES[kind].icon}
      {labels[kind]}
    </span>
  );
}

/** Translated block type with its icon (neutral — the status icon and kind chip carry the colour); raw type as tooltip. */
export function StepBlockTypeBadge({ step }: { step: StepResult }) {
  const t = useT();
  const type = stepBlockType(step);
  if (!type) return null;
  const known = isBlockType(type);
  return (
    <Badge>
      <span className="inline-flex items-center gap-1" title={type}>
        {known && BLOCK_TYPE_ICONS[type]}
        {known ? t.workflows.blockTypes[type] : type}
      </span>
    </Badge>
  );
}

/** Header row shared by all step cards: number, kind chip, block badge (or `badge` override), block name, duration. */
export function StepCardHeader({ step, badge, className = 'mb-1' }: { step: StepResult; badge?: ReactNode; className?: string }) {
  const t = useT();
  const blockName = stepBlockName(step);
  return (
    <div className={`flex flex-wrap items-center gap-2 ${className}`}>
      <span className="text-xs font-mono text-text-tertiary">
        {t.workflows.execution.stepNumber.replace('{n}', String(step.stepIndex + 1))}
      </span>
      <StepKindChip kind={stepKind(step)} />
      {badge ?? <StepBlockTypeBadge step={step} />}
      {blockName && <span className="text-xs text-text-secondary truncate">{blockName}</span>}
      {step.durationMs != null && (
        <span className="text-xs text-text-tertiary flex items-center gap-1">
          <Clock className="w-3 h-3" />
          {formatDuration(step.durationMs)}
        </span>
      )}
    </div>
  );
}
