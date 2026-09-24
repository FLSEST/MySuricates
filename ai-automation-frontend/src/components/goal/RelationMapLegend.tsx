import type { ReactNode } from 'react';
import { Bot, CheckCircle2, GitBranch, HeartPulse, ShieldCheck, UserRound, Wrench, XCircle, Zap } from 'lucide-react';
import { useT } from '../../i18n';

function Line({ dash }: { dash?: string }) {
  return (
    <svg width="28" height="8" aria-hidden="true">
      <line x1="1" y1="4" x2="27" y2="4" strokeWidth="1.6" strokeLinecap="round" strokeDasharray={dash}
        style={{ stroke: 'var(--color-text-secondary)' }} />
    </svg>
  );
}

function Item({ icon, children }: { icon: ReactNode; children: ReactNode }) {
  return <span className="inline-flex items-center gap-1.5">{icon}{children}</span>;
}

/** Legende der Relationskarte: Symbole der Herkunft und Bearbeitung, Linienarten, Ergebnisfarben. */
export function RelationMapLegend() {
  const t = useT();
  const m = t.goals.map;
  const icon = 'w-3.5 h-3.5';
  return (
    <div className="flex flex-wrap gap-x-5 gap-y-2 text-xs text-text-secondary" aria-label={m.legend} role="note">
      <Item icon={<UserRound className={`${icon} text-human`} />}>{m.origin.USER}</Item>
      <Item icon={<Bot className={`${icon} text-ai`} />}>{m.origin.AGENT}</Item>
      <Item icon={<GitBranch className={icon} />}>{m.origin.WORKFLOW_RUN}</Item>
      <Item icon={<ShieldCheck className={icon} />}>{m.origin.AUDIT}</Item>
      <Item icon={<HeartPulse className={icon} />}>{m.origin.HEALTH}</Item>
      <Item icon={<Wrench className={icon} />}>{m.linked}</Item>
      <Item icon={<Zap className={icon} />}>{m.trigger}</Item>
      <Item icon={<CheckCircle2 className={`${icon} text-success`} />}>{m.result.success}</Item>
      <Item icon={<XCircle className={`${icon} text-danger`} />}>{m.result.danger}</Item>
      <Item icon={<Line />}>{m.legendAssigned}</Item>
      <Item icon={<Line dash="6 4" />}>{m.legendCandidate}</Item>
      <Item icon={<Line dash="1 4" />}>{m.noWorkflow}</Item>
    </div>
  );
}
