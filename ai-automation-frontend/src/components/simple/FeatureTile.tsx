import type { LucideIcon } from 'lucide-react';
import { Card } from '../ui';
import { StatusBadge } from '../shared/StatusBadge';
import type { RunStatus } from '../../types';

interface FeatureTileProps {
  /** Nav tiles pass a lucide icon; run tiles pass an emoji (workflow.icon). */
  icon?: LucideIcon;
  emoji?: string;
  title: string;
  description?: string;
  /** Last-run status for run tiles. */
  status?: RunStatus;
  onClick: () => void;
}

export function FeatureTile({ icon: Icon, emoji, title, description, status, onClick }: FeatureTileProps) {
  return (
    <Card onClick={onClick} className="cursor-pointer hover:border-accent/40 transition-colors">
      <div className="flex items-start gap-3">
        <div className="p-2 rounded-lg bg-accent/10 shrink-0">
          {Icon ? (
            <Icon className="w-5 h-5 text-accent" />
          ) : (
            <span className="text-xl leading-none">{emoji || '⚡'}</span>
          )}
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2 min-w-0">
            <p className="text-sm font-medium text-text truncate">{title}</p>
            {status && <StatusBadge status={status} />}
          </div>
          {description && <p className="text-xs text-text-tertiary mt-0.5 truncate">{description}</p>}
        </div>
      </div>
    </Card>
  );
}
