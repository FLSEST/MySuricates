import type { ReactNode } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { ArrowDown, ArrowUp, Loader2, Minus, Plus } from 'lucide-react';
import { Badge } from '../ui';
import type { AuditFinding, AuditSummary } from '../../hooks/useAuditCenter';
import { useT } from '../../i18n';

// Small building blocks shared by the Audit Center's views.

export function GateBadge({ status }: { status: AuditSummary['gateStatus'] }) {
  const t = useT();
  const variant = status === 'GREEN' ? 'success' : status === 'YELLOW' ? 'warning' : status === 'RED' ? 'danger' : 'default';
  return <Badge variant={variant} dot>{status ?? t.auditCenter.noGate}</Badge>;
}

export function SeverityBadge({ severity }: { severity: AuditFinding['severity'] }) {
  return <Badge variant={severity === 'CRITICAL' || severity === 'HIGH' ? 'danger' : severity === 'MEDIUM' ? 'warning' : 'default'}>{severity}</Badge>;
}

export function FindingStatusBadge({ status }: { status: AuditFinding['status'] }) {
  const t = useT();
  return <Badge variant={status === 'OPEN' ? 'danger' : status === 'AWAITING_VERIFICATION' ? 'warning' : 'success'}>{t.auditCenter.statusBadges[status]}</Badge>;
}

export function Trend({ trend }: { trend: AuditSummary['trend'] }) {
  const Icon = trend === 'IMPROVED' ? ArrowUp : trend === 'REGRESSED' ? ArrowDown : trend === 'UNCHANGED' ? Minus : Plus;
  const color = trend === 'IMPROVED' ? 'text-success' : trend === 'REGRESSED' ? 'text-danger' : 'text-text-tertiary';
  return <span className={`inline-flex items-center gap-1 text-xs ${color}`}><Icon className="w-3.5 h-3.5" />{trend}</span>;
}

export function CenteredLoading() {
  return <div className="flex items-center justify-center py-12 text-text-secondary"><Loader2 className="w-5 h-5 animate-spin" /></div>;
}

/** Report Markdown in the colours of the theme. */
export function AuditMarkdown({ content }: { content: string }) {
  return <article className="prose prose-sm dark:prose-invert max-w-none prose-headings:text-text prose-p:text-text-secondary prose-a:text-accent prose-code:text-accent prose-pre:bg-surface-overlay prose-pre:border prose-pre:border-border prose-table:border-collapse prose-th:border prose-th:border-border prose-th:bg-surface-overlay prose-td:border prose-td:border-border prose-strong:text-text prose-hr:border-border"><ReactMarkdown remarkPlugins={[remarkGfm]}>{content}</ReactMarkdown></article>;
}

/** `template` with `placeholder` replaced by `children` — for a value that needs its own markup. */
export function TemplateValue({ template, placeholder, children }: { template: string; placeholder: string; children: ReactNode }) {
  const [before, after = ''] = template.split(placeholder);
  return <>{before}{children}{after}</>;
}
