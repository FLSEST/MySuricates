import {
  BookOpen, CalendarPlus, CircleAlert, Clock, FolderKanban, FolderPlus, GitBranch, Hand, History, Languages,
  LayoutTemplate, Lightbulb, ListPlus, Loader2, MessageSquare, Package, FilePlus, Settings, Sparkles, SquareCheck, SunMoon,
  type LucideIcon,
} from 'lucide-react';
import { Button } from '../../ui';
import { ProBadge } from '../ProHandoff';
import { StatusDot } from '../kit';
import type { HomeAction, HomeIcon, HomeItem } from '../../../utils/homeCommand/types';

const ICONS: Record<Exclude<HomeIcon, { emoji: string }>, LucideIcon> = {
  workflow: GitBranch,
  template: LayoutTemplate,
  pool: Package,
  run: History,
  'run-failed': CircleAlert,
  approval: Hand,
  input: MessageSquare,
  todo: SquareCheck,
  'todo-add': ListPlus,
  project: FolderKanban,
  schedule: Clock,
  'schedule-add': CalendarPlus,
  language: Languages,
  theme: SunMoon,
  settings: Settings,
  hint: Lightbulb,
  pro: Sparkles,
  'project-add': FolderPlus,
  'workflow-add': FilePlus,
  explanation: BookOpen,
};

function ItemIcon({ icon, human }: { icon: HomeIcon; human?: boolean }) {
  if (typeof icon === 'object') {
    return <span className="w-8 h-8 flex items-center justify-center text-lg leading-none shrink-0" aria-hidden>{icon.emoji}</span>;
  }
  const Icon = ICONS[icon];
  const tone = human ? 'text-human bg-human-subtle' : icon === 'run-failed' ? 'text-danger bg-danger-subtle' : 'text-text-secondary bg-surface-raised';
  return (
    <span className={`w-8 h-8 rounded-lg flex items-center justify-center shrink-0 ${tone}`} aria-hidden>
      <Icon className="w-4 h-4" />
    </span>
  );
}

/** Answer buttons in the role colours: `human` for an answer, `danger` for a rejecting one. */
const TONE_CLASS: Record<'neutral' | 'danger' | 'human', string> = {
  neutral: 'bg-surface-raised border border-border text-text hover:bg-surface-overlay hover:border-border-strong',
  danger: 'border border-danger/40 text-danger hover:bg-danger-subtle',
  human: 'border border-human/40 text-human hover:bg-human-subtle',
};

/**
 * One hit: icon, label and sub line, the Pro badge where it leads to Pro, secondary buttons and the
 * main button. The row is an `option` of the field's listbox; a click anywhere outside the buttons
 * does what the main button does. Expanded explanations show their bullets below.
 */
export function HomeCommandRow({
  item, optionId, active, expanded, primaryLabel, execute, isPending,
}: {
  item: HomeItem;
  optionId: string;
  active: boolean;
  expanded: boolean;
  primaryLabel: string;
  execute: (action: HomeAction) => void;
  isPending: (action: HomeAction) => boolean;
}) {
  const frame = active
    ? 'bg-surface-raised border-accent/50'
    : item.human ? 'border-human/30 hover:border-human/50' : 'border-transparent hover:bg-surface-raised';
  return (
    <div
      id={optionId}
      role="option"
      aria-selected={active}
      aria-expanded={item.points ? expanded : undefined}
      className={`rounded-xl border px-3 py-2 transition-colors ${frame}`}
    >
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
        <div
          className="flex items-center gap-3 min-w-0 flex-1 basis-48 cursor-pointer"
          onClick={() => execute(item.primary.action)}
        >
          <ItemIcon icon={item.icon} human={item.human} />
          <div className="min-w-0">
            <p className="flex items-center gap-2 text-sm font-medium text-text min-w-0">
              <span className="truncate">{item.label}</span>
              <StatusDot status={item.status} />
            </p>
            {item.sub && <p className="text-xs text-text-tertiary truncate">{item.sub}</p>}
          </div>
        </div>
        <div className="flex flex-wrap items-center justify-end gap-1.5 ml-auto">
          {item.pro && <ProBadge />}
          {item.secondary?.map((button) => {
            const pending = isPending(button.action);
            return (
              <button
                key={button.id}
                type="button"
                disabled={pending}
                onClick={() => execute(button.action)}
                className={`btn-base px-3 py-1.5 text-xs ${TONE_CLASS[button.tone ?? 'neutral']}`}
              >
                {pending && <Loader2 className="w-4 h-4 animate-spin" aria-hidden />}
                {button.label}
              </button>
            );
          })}
          <Button
            size="sm"
            variant={active ? 'primary' : 'secondary'}
            loading={isPending(item.primary.action)}
            onClick={() => execute(item.primary.action)}
          >
            {primaryLabel}
          </Button>
        </div>
      </div>
      {expanded && item.points && (
        <ul className="mt-2 ml-11 list-disc space-y-1 pl-4 text-sm text-text-secondary">
          {item.points.map((point) => <li key={point}>{point}</li>)}
        </ul>
      )}
    </div>
  );
}
