import { Terminal, Brain, ShieldCheck } from 'lucide-react';
import type { BlockDefinition } from '../../types';
import { getExecutionModes, type ExecutionMode } from './executionModes';

const modeConfig: Record<ExecutionMode, { icon: typeof Terminal; label: string; color: string }> = {
  shell: { icon: Terminal, label: 'Shell', color: 'text-emerald-500' },
  ai: { icon: Brain, label: 'AI', color: 'text-violet-400' },
  approval: { icon: ShieldCheck, label: 'Approval', color: 'text-amber-400' },
};

export function ExecutionBadges({ blocks, className = '' }: { blocks: BlockDefinition[]; className?: string }) {
  const modes = getExecutionModes(blocks);
  if (modes.length === 0) return null;
  return (
    <span className={`flex items-center gap-1 ${className}`}>
      {modes.map((mode) => {
        const cfg = modeConfig[mode];
        const Icon = cfg.icon;
        return (
          <span
            key={mode}
            className="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded-sm text-[10px] font-medium bg-surface-hover"
            title={cfg.label}
          >
            <Icon className={`w-3 h-3 ${cfg.color}`} />
            <span className={cfg.color}>{cfg.label}</span>
          </span>
        );
      })}
    </span>
  );
}
