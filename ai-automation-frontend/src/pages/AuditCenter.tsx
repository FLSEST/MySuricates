import { useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {
  AlertTriangle, ArrowDown, ArrowRight, ArrowUp, CheckCircle2, ClipboardList,
  ExternalLink, FileClock, FileText, History, Link2, Loader2, Minus, Plus, ShieldCheck,
} from 'lucide-react';
import { Badge, Button } from '../components/ui';
import { useProjects } from '../hooks/useProjects';
import { useReportsList, useReportContent } from '../hooks/useReports';
import {
  type AuditFinding, type AuditFindingStatus, type AuditReport, type AuditSummary,
  useAuditFinding, useAuditFindings, useAuditReports, useAudits, useCreateAuditTodo, useLinkAuditTodo,
  useUpdateAuditFinding,
} from '../hooks/useAuditCenter';
import { useI18nStore, useT } from '../i18n';
import { formatDateTime, formatRelativeTime } from '../utils/formatTime';
import { todoDeepLink } from '../utils/todoLinks';

type MainView = 'overview' | 'audit' | 'legacy';
type AuditTab = 'report' | 'history' | 'findings';

export function AuditCenter() {
  const locale = useI18nStore((state) => state.locale);
  const [searchParams] = useSearchParams();
  const requestedFinding = searchParams.get('finding');
  const [view, setView] = useState<MainView>(requestedFinding ? 'audit' : 'overview');
  const [auditTab, setAuditTab] = useState<AuditTab>(requestedFinding ? 'findings' : 'report');
  const [selected, setSelected] = useState<AuditSummary | null>(null);
  const { data: audits, isLoading } = useAudits();
  const { data: projects } = useProjects();
  const linkedFindingQuery = useAuditFinding(requestedFinding);

  const projectNames = useMemo(() => new Map(projects?.map((project) => [project.id, project.name]) ?? []), [projects]);

  const linkedFinding = linkedFindingQuery.data;
  const linkedAudit = linkedFinding && audits?.find((item) => item.projectId === linkedFinding.projectId && item.auditKey === linkedFinding.auditKey);
  const activeAudit = requestedFinding ? linkedAudit ?? null : selected;

  const openAudit = (audit: AuditSummary) => {
    setSelected(audit);
    setView('audit');
    setAuditTab('report');
  };

  return (
    <div className="h-full min-h-0 flex flex-col p-6 gap-5">
      <header className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-text flex items-center gap-2">
            <ShieldCheck className="w-6 h-6 text-accent" /> Audit Center
          </h1>
          <p className="text-sm text-text-secondary mt-1">
            Aktueller Qualitätsstand, nachvollziehbare Historie und verifizierte Finding-TODOs
          </p>
        </div>
        <nav className="inline-flex border border-border rounded-lg p-1 bg-surface-raised" aria-label="Report views">
          <NavButton active={view === 'overview'} onClick={() => setView('overview')} icon={<ClipboardList className="w-4 h-4" />}>Übersicht</NavButton>
          <NavButton active={view === 'audit'} disabled={!activeAudit} onClick={() => activeAudit && setView('audit')} icon={<FileText className="w-4 h-4" />}>Audit</NavButton>
          <NavButton active={view === 'legacy'} onClick={() => setView('legacy')} icon={<FileClock className="w-4 h-4" />}>Legacy</NavButton>
        </nav>
      </header>

      {view === 'overview' && (
        <AuditOverview audits={audits ?? []} loading={isLoading} projectNames={projectNames} locale={locale} onOpen={openAudit} />
      )}
      {view === 'audit' && activeAudit && (
        <AuditDetail audit={activeAudit} tab={auditTab} setTab={setAuditTab} locale={locale}
          projectName={projectNames.get(activeAudit.projectId)} requestedFinding={requestedFinding} />
      )}
      {view === 'audit' && requestedFinding && !activeAudit && !linkedFindingQuery.isLoading && (
        <div className="flex-1 flex items-center justify-center text-sm text-text-secondary">
          Das verlinkte Finding oder sein Audit wurde nicht gefunden.
        </div>
      )}
      {view === 'legacy' && <LegacyReports />}
    </div>
  );
}

function AuditOverview({ audits, loading, projectNames, locale, onOpen }: {
  audits: AuditSummary[]; loading: boolean; projectNames: Map<string, string>; locale: string;
  onOpen: (audit: AuditSummary) => void;
}) {
  if (loading) return <CenteredLoading />;
  if (!audits.length) {
    return <div className="flex-1 flex flex-col items-center justify-center text-text-secondary gap-3">
      <ShieldCheck className="w-10 h-10 opacity-40" />
      <p>Noch keine strukturierten Audit-Reports. Der erste v4-Audit-Run legt die Baseline an.</p>
    </div>;
  }
  return (
    <div className="flex-1 min-h-0 overflow-y-auto border-y border-border">
      <div className="grid grid-cols-[minmax(240px,2fr)_130px_110px_150px_150px_32px] gap-4 px-4 py-2 text-xs font-medium text-text-tertiary uppercase">
        <span>Audit</span><span>Gate</span><span>Trend</span><span>Offene Findings</span><span>Letzter Lauf</span><span />
      </div>
      {audits.map((audit) => (
        <button key={`${audit.projectId}:${audit.auditKey}`} onClick={() => onOpen(audit)}
          className="w-full grid grid-cols-[minmax(240px,2fr)_130px_110px_150px_150px_32px] gap-4 items-center px-4 py-4 border-t border-border text-left hover:bg-surface-raised transition-colors">
          <span className="min-w-0">
            <span className="block font-medium text-text truncate">{audit.title}</span>
            <span className="block text-xs text-text-tertiary truncate">{projectNames.get(audit.projectId) ?? audit.projectId} · {audit.category}</span>
          </span>
          <GateBadge status={audit.gateStatus} />
          <Trend trend={audit.trend} />
          <FindingCounts counts={audit.openFindingsBySeverity} accepted={audit.findingsByStatus.ACCEPTED_RISK ?? 0} />
          <span className="text-xs text-text-secondary">
            {formatRelativeTime(audit.generatedAt, locale)}
            {audit.stale && <span className="block text-warning">veraltet</span>}
            {audit.latestAttemptStatus === 'INVALID' && <span className="block text-danger">letzter Versuch ungültig</span>}
          </span>
          <ArrowRight className="w-4 h-4 text-text-tertiary" />
        </button>
      ))}
    </div>
  );
}

function AuditDetail({ audit, tab, setTab, locale, projectName, requestedFinding }: {
  audit: AuditSummary; tab: AuditTab; setTab: (tab: AuditTab) => void; locale: string;
  projectName?: string; requestedFinding: string | null;
}) {
  const reports = useAuditReports(audit.projectId, audit.auditKey);
  const findings = useAuditFindings(audit.projectId, audit.auditKey);
  const latest = reports.data?.content.find((report) => report.ingestionStatus === 'VALID');
  return (
    <div className="flex-1 min-h-0 flex flex-col gap-4">
      <div className="flex items-center justify-between gap-4 border-b border-border pb-4">
        <div className="min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <h2 className="text-lg font-semibold text-text truncate">{audit.title}</h2>
            <GateBadge status={audit.gateStatus} />
            <Trend trend={audit.trend} />
          </div>
          <p className="text-xs text-text-tertiary mt-1">{projectName ?? audit.projectId} · {audit.auditKey}</p>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <Link className="text-xs text-accent hover:underline inline-flex items-center gap-1" to={`/workflows/${audit.workflowId}`}>
            Workflow <ExternalLink className="w-3 h-3" />
          </Link>
          <Link className="text-xs text-accent hover:underline inline-flex items-center gap-1" to={`/workflows/${audit.workflowId}/run/${audit.latestRunId}`}>
            Evidenz-Run <ExternalLink className="w-3 h-3" />
          </Link>
        </div>
      </div>
      <div className="flex gap-1">
        <TabButton active={tab === 'report'} onClick={() => setTab('report')} icon={<FileText className="w-4 h-4" />}>Aktueller Report</TabButton>
        <TabButton active={tab === 'history'} onClick={() => setTab('history')} icon={<History className="w-4 h-4" />}>Historie</TabButton>
        <TabButton active={tab === 'findings'} onClick={() => setTab('findings')} icon={<AlertTriangle className="w-4 h-4" />}>Findings ({findings.data?.totalElements ?? 0})</TabButton>
      </div>
      <div className="flex-1 min-h-0 overflow-y-auto border-t border-border pt-5">
        {reports.isLoading || findings.isLoading ? <CenteredLoading /> : null}
        {tab === 'report' && latest && <ReportContent report={latest} />}
        {tab === 'history' && <ReportHistory reports={reports.data?.content ?? []} locale={locale} workflowId={audit.workflowId} />}
        {tab === 'findings' && <Findings findings={findings.data?.content ?? []} requestedFinding={requestedFinding} workflowId={audit.workflowId} locale={locale} />}
      </div>
    </div>
  );
}

function ReportContent({ report }: { report: AuditReport }) {
  return <div className="max-w-5xl">
    <div className="flex items-center gap-3 text-xs text-text-tertiary mb-5 flex-wrap">
      <span>{report.provider ?? 'Default'} / {report.model ?? 'Default'}</span>
      <span>Workflow v{report.workflowVersion ?? '?'}</span>
      <span>{report.findingCount} Findings im Snapshot</span>
    </div>
    <Markdown content={report.markdownContent} />
  </div>;
}

function ReportHistory({ reports, locale, workflowId }: { reports: AuditReport[]; locale: string; workflowId: string }) {
  return <div className="divide-y divide-border">
    {reports.map((report) => <div key={report.id} className="py-4 flex items-start justify-between gap-4">
      <div className="flex items-start gap-3">
        {report.ingestionStatus === 'VALID' ? <CheckCircle2 className="w-5 h-5 text-success mt-0.5" /> : <AlertTriangle className="w-5 h-5 text-danger mt-0.5" />}
        <div>
          <div className="flex gap-2 items-center"><GateBadge status={report.gateStatus} /><Badge variant={report.ingestionStatus === 'VALID' ? 'success' : 'danger'}>{report.ingestionStatus}</Badge></div>
          <p className="text-sm text-text-secondary mt-2">{report.gateDescription ?? report.ingestionError ?? 'Keine Beschreibung'}</p>
          <p className="text-xs text-text-tertiary mt-1">{formatDateTime(report.generatedAt, locale)} · {report.findingCount} Findings · v{report.workflowVersion ?? '?'}</p>
        </div>
      </div>
      <Link to={`/workflows/${workflowId}/run/${report.workflowRunId}`} className="text-xs text-accent hover:underline shrink-0">Run öffnen</Link>
    </div>)}
  </div>;
}

function Findings({ findings, requestedFinding, workflowId, locale }: { findings: AuditFinding[]; requestedFinding: string | null; workflowId: string; locale: string }) {
  const t = useT();
  const update = useUpdateAuditFinding();
  const createTodo = useCreateAuditTodo();
  const linkTodo = useLinkAuditTodo();
  const [linking, setLinking] = useState<string | null>(null);
  const [todoId, setTodoId] = useState('');
  if (!findings.length) return <p className="text-sm text-text-secondary">Keine Findings in diesem Audit.</p>;
  return <div className="divide-y divide-border">
    {findings.map((finding) => {
      const hasManagedTodo = finding.todos.some((todo) => todo.managed);
      return <section key={finding.id} className={`py-5 scroll-mt-4 ${requestedFinding === finding.id ? 'bg-accent-subtle/30 px-3 -mx-3' : ''}`}>
        <div className="flex items-start justify-between gap-4">
          <div className="min-w-0">
            <div className="flex items-center gap-2 flex-wrap"><SeverityBadge severity={finding.severity} /><FindingStatus status={finding.status} /><h3 className="font-medium text-text">{finding.title}</h3></div>
            <p className="text-sm text-text-secondary mt-2">{finding.description}</p>
            {finding.evidence && <pre className="text-xs text-text-secondary font-mono whitespace-pre-wrap mt-3 border-l-2 border-border pl-3">{finding.evidence}</pre>}
            <Disposition finding={finding} locale={locale} />
            <div className="flex items-center gap-3 mt-3 text-xs text-text-tertiary flex-wrap">
              <span>{finding.ruleKey} · {finding.subject}</span>
              {finding.unknownRuleKey && <span
                className="inline-flex items-center gap-1 text-warning border border-warning/40 rounded px-1.5 py-0.5"
                title={t.auditCenter.unknownRuleKeyHint} data-testid="unknown-rule-key">
                <AlertTriangle className="w-3 h-3" /> {t.auditCenter.unknownRuleKey}
              </span>}
              <span>{finding.occurrenceCount}× gesehen</span>
              {finding.regressionCount > 0 && <span className="text-danger">{finding.regressionCount} Regression(en)</span>}
              <Link className="text-accent hover:underline" to={`/workflows/${workflowId}/run/${finding.lastSeenRunId}`}>Evidenz-Run</Link>
            </div>
            {finding.todos.length > 0 && <div className="flex items-center gap-2 mt-3 flex-wrap">
              {finding.todos.map((todo) => <Link key={todo.id} to={todoDeepLink(todo)} className="inline-flex items-center gap-1 text-xs text-accent hover:underline">
                <Link2 className="w-3 h-3" /> {todo.title} · {todo.status}{todo.managed ? ' · verwaltet' : ''}
              </Link>)}
            </div>}
          </div>
          <div className="flex items-center gap-2 shrink-0 flex-wrap justify-end">
            {!hasManagedTodo && !finding.status.includes('RESOLVED') && <Button size="sm" variant="secondary" icon={<Plus className="w-4 h-4" />} loading={createTodo.isPending} onClick={() => createTodo.mutate(finding.id)}>TODO</Button>}
            <select className="bg-surface-raised border border-border rounded-md px-2 py-1.5 text-xs text-text" value={finding.status}
              onChange={(event) => update.mutate({ id: finding.id, status: event.target.value as AuditFindingStatus })}>
              <option value="OPEN">Offen</option><option value="AWAITING_VERIFICATION">Gegenprüfung ausstehend</option>
              <option value="ACCEPTED_RISK">Risiko akzeptiert</option><option value="DISMISSED">Verworfen</option><option value="RESOLVED">Behoben</option>
            </select>
            <button className="p-2 text-text-tertiary hover:text-text" title="Bestehendes TODO verknüpfen" onClick={() => setLinking(linking === finding.id ? null : finding.id)}><Link2 className="w-4 h-4" /></button>
          </div>
        </div>
        {linking === finding.id && <form className="mt-4 flex gap-2 max-w-md" onSubmit={(event) => { event.preventDefault(); if (todoId.trim()) linkTodo.mutate({ findingId: finding.id, todoId: todoId.trim() }, { onSuccess: () => { setTodoId(''); setLinking(null); } }); }}>
          <input className="flex-1 bg-surface-raised border border-border rounded-md px-3 py-1.5 text-sm text-text" placeholder="TODO-ID" value={todoId} onChange={(event) => setTodoId(event.target.value)} />
          <Button size="sm" type="submit" loading={linkTodo.isPending}>Verknüpfen</Button>
        </form>}
      </section>;
    })}
  </div>;
}

function LegacyReports() {
  const [selectedName, setSelectedName] = useState<string | null>(null);
  const reports = useReportsList();
  const active = reports.data?.find((report) => report.name === selectedName) ?? reports.data?.[0] ?? null;
  const content = useReportContent(active?.name ?? null);
  return <div className="flex gap-5 flex-1 min-h-0">
    <aside className="w-72 shrink-0 overflow-y-auto border-r border-border pr-3">
      <p className="text-xs text-text-tertiary mb-3">Unveränderliches Archiv dateibasierter Markdown-Reports</p>
      {reports.isLoading ? <CenteredLoading /> : reports.data?.map((report) => <button key={report.name} onClick={() => setSelectedName(report.name)} className={`w-full flex items-center gap-2 px-3 py-2 text-sm text-left rounded-md ${active?.name === report.name ? 'bg-accent-subtle text-accent' : 'text-text-secondary hover:bg-surface-raised'}`}><FileText className="w-4 h-4 shrink-0" /><span className="truncate">{report.label}</span></button>)}
    </aside>
    <div className="flex-1 min-w-0 overflow-y-auto">{content.isLoading ? <CenteredLoading /> : content.data ? <Markdown content={content.data} /> : <p className="text-text-secondary">Kein Legacy-Report ausgewählt.</p>}</div>
  </div>;
}

function Markdown({ content }: { content: string }) {
  return <article className="prose prose-sm dark:prose-invert max-w-none prose-headings:text-text prose-p:text-text-secondary prose-a:text-accent prose-code:text-accent prose-pre:bg-surface-overlay prose-pre:border prose-pre:border-border prose-table:border-collapse prose-th:border prose-th:border-border prose-th:bg-surface-overlay prose-td:border prose-td:border-border prose-strong:text-text prose-hr:border-border"><ReactMarkdown remarkPlugins={[remarkGfm]}>{content}</ReactMarkdown></article>;
}

function GateBadge({ status }: { status: AuditSummary['gateStatus'] }) {
  const variant = status === 'GREEN' ? 'success' : status === 'YELLOW' ? 'warning' : status === 'RED' ? 'danger' : 'default';
  return <Badge variant={variant} dot>{status ?? 'OHNE GATE'}</Badge>;
}
function SeverityBadge({ severity }: { severity: AuditFinding['severity'] }) {
  return <Badge variant={severity === 'CRITICAL' || severity === 'HIGH' ? 'danger' : severity === 'MEDIUM' ? 'warning' : 'default'}>{severity}</Badge>;
}
function FindingStatus({ status }: { status: AuditFinding['status'] }) {
  const labels: Record<AuditFindingStatus, string> = { OPEN: 'Offen', AWAITING_VERIFICATION: 'Gegenprüfung', RESOLVED: 'Behoben', ACCEPTED_RISK: 'Akzeptiert', DISMISSED: 'Verworfen' };
  return <Badge variant={status === 'OPEN' ? 'danger' : status === 'AWAITING_VERIFICATION' ? 'warning' : 'success'}>{labels[status]}</Badge>;
}
function Trend({ trend }: { trend: AuditSummary['trend'] }) {
  const Icon = trend === 'IMPROVED' ? ArrowUp : trend === 'REGRESSED' ? ArrowDown : trend === 'UNCHANGED' ? Minus : Plus;
  const color = trend === 'IMPROVED' ? 'text-success' : trend === 'REGRESSED' ? 'text-danger' : 'text-text-tertiary';
  return <span className={`inline-flex items-center gap-1 text-xs ${color}`}><Icon className="w-3.5 h-3.5" />{trend}</span>;
}
function FindingCounts({ counts, accepted }: { counts: Record<string, number>; accepted: number }) {
  const t = useT();
  const total = Object.values(counts).reduce((sum, count) => sum + count, 0);
  return <span className="text-sm text-text">
    <strong>{total}</strong><span className="text-xs text-text-tertiary ml-1">({counts.CRITICAL ?? 0} kritisch, {counts.HIGH ?? 0} hoch)</span>
    {accepted > 0 && <span className="block text-xs text-text-tertiary" data-testid="accepted-count">{t.auditCenter.acceptedCount.replace('{count}', String(accepted))}</span>}
  </span>;
}

const REVIEW_INTERVAL_MS = 180 * 24 * 60 * 60 * 1000;

function Disposition({ finding, locale }: { finding: AuditFinding; locale: string }) {
  const t = useT();
  if (!finding.dispositionAt && !finding.dispositionEvidence && !finding.dispositionNote) return null;
  const formatDay = (value: string | Date) => new Date(value).toLocaleDateString(locale === 'de' ? 'de-DE' : 'en-US', { day: '2-digit', month: '2-digit', year: 'numeric' });
  const reviewDue = finding.dispositionAt ? new Date(new Date(finding.dispositionAt).getTime() + REVIEW_INTERVAL_MS) : null;
  return <div className="mt-3 text-xs text-text-tertiary border-l-2 border-warning/60 pl-3 space-y-1" data-testid="disposition">
    {finding.dispositionAt && <p>
      {t.auditCenter.acceptedAt} {formatDay(finding.dispositionAt)}
      {finding.status === 'ACCEPTED_RISK' && reviewDue && <> · {t.auditCenter.reviewDue} {formatDay(reviewDue)}</>}
    </p>}
    {finding.dispositionNote && <p className="whitespace-pre-wrap text-text-secondary">{t.auditCenter.dispositionNote}: {finding.dispositionNote}</p>}
    {finding.dispositionEvidence && <details>
      <summary className="cursor-pointer">{t.auditCenter.evidenceSnapshot}</summary>
      <pre className="font-mono whitespace-pre-wrap mt-1">{finding.dispositionEvidence}</pre>
    </details>}
  </div>;
}
function NavButton({ active, disabled, onClick, icon, children }: { active: boolean; disabled?: boolean; onClick: () => void; icon: React.ReactNode; children: React.ReactNode }) {
  return <button disabled={disabled} onClick={onClick} className={`inline-flex items-center gap-2 px-3 py-1.5 text-xs rounded-md disabled:opacity-40 ${active ? 'bg-surface-overlay text-text' : 'text-text-secondary hover:text-text'}`}>{icon}{children}</button>;
}
function TabButton({ active, onClick, icon, children }: { active: boolean; onClick: () => void; icon: React.ReactNode; children: React.ReactNode }) {
  return <button onClick={onClick} className={`inline-flex items-center gap-2 px-3 py-2 text-sm border-b-2 ${active ? 'border-accent text-accent' : 'border-transparent text-text-secondary hover:text-text'}`}>{icon}{children}</button>;
}
function CenteredLoading() { return <div className="flex items-center justify-center py-12 text-text-secondary"><Loader2 className="w-5 h-5 animate-spin" /></div>; }
