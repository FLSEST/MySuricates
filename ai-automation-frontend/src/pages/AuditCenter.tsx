import { Hint } from '../components/hints/Hint';
import { useMemo, useState, type Dispatch, type SetStateAction } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {
  AlertTriangle, ArrowDown, ArrowRight, ArrowUp, Calendar, CheckCircle2, ClipboardList,
  ExternalLink, FileClock, FileText, History, Link2, Loader2, Minus, Plus, ShieldCheck,
} from 'lucide-react';
import { Badge, Button, FilterChips } from '../components/ui';
import { TriggerSourceChip } from '../components/workflow/TriggerSourceChip';
import { useProjects } from '../hooks/useProjects';
import { useReportsList, useReportContent } from '../hooks/useReports';
import {
  type AuditFinding, type AuditFindingStatus, type AuditReport, type AuditSummary,
  FINDINGS_PAGE_SIZE, useAuditFinding, useAuditFindingsByStatus, useAuditReports, useAudits, useCreateAuditTodo,
  useLinkAuditTodo, useUpdateAuditFinding,
} from '../hooks/useAuditCenter';
import { useToast } from '../hooks/useToast';
import { useI18nStore, useT, type Locale } from '../i18n';
import { formatDateTime, formatRelativeTime } from '../utils/formatTime';
import { formatDate, formatTimestamp } from '../utils/localeFormat';
import { todoDeepLink } from '../utils/todoLinks';

type MainView = 'overview' | 'audit' | 'legacy';

const FINDING_STATUSES: AuditFindingStatus[] = ['OPEN', 'AWAITING_VERIFICATION', 'ACCEPTED_RISK', 'DISMISSED', 'RESOLVED'];
/** Dispositions a person has to justify: the finding stays, but nobody acts on it. */
const NEEDS_REASON = new Set<AuditFindingStatus>(['ACCEPTED_RISK', 'DISMISSED']);
type AuditTab = 'report' | 'history' | 'findings';

type FindingFilter = 'open' | 'accepted' | 'dismissed' | 'resolved';
/**
 * The status chips of the findings tab, each named like the badge of its first status. "Open" is
 * everything that still needs work or a check — the default, so settled findings do not bury it.
 */
const FINDING_FILTERS: Record<FindingFilter, [AuditFindingStatus, ...AuditFindingStatus[]]> = {
  open: ['OPEN', 'AWAITING_VERIFICATION'],
  accepted: ['ACCEPTED_RISK'],
  dismissed: ['DISMISSED'],
  resolved: ['RESOLVED'],
};
const FILTER_ORDER = Object.keys(FINDING_FILTERS) as FindingFilter[];

function filterOf(status: AuditFindingStatus): FindingFilter {
  return FILTER_ORDER.find((filter) => FINDING_FILTERS[filter].includes(status)) ?? 'open';
}

/** From the summary, which counts every finding — the loaded list stops at a page per status. */
function filterCount(audit: AuditSummary, filter: FindingFilter): number {
  return FINDING_FILTERS[filter].reduce((sum, status) => sum + (audit.findingsByStatus[status] ?? 0), 0);
}

/** A deep-linked finding outside "open" switches its own chip on as well, so that it is visible. */
function initialFindingFilter(linkedStatus: AuditFindingStatus | undefined): FindingFilter[] {
  const linked = linkedStatus ? filterOf(linkedStatus) : 'open';
  return linked === 'open' ? ['open'] : ['open', linked];
}

const OVERVIEW_COLUMNS = 'grid-cols-[minmax(240px,2fr)_130px_110px_180px_150px_32px]';

export function AuditCenter() {
  const t = useT();
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
          <p className="text-sm text-text-secondary mt-1">{t.auditCenter.subtitle}</p>
        </div>
        <nav className="inline-flex border border-border rounded-lg p-1 bg-surface-raised" aria-label={t.auditCenter.viewsLabel}>
          <NavButton active={view === 'overview'} onClick={() => setView('overview')} icon={<ClipboardList className="w-4 h-4" />}>{t.auditCenter.navOverview}</NavButton>
          <NavButton active={view === 'audit'} disabled={!activeAudit} onClick={() => activeAudit && setView('audit')} icon={<FileText className="w-4 h-4" />}>{t.auditCenter.navAudit}</NavButton>
          <NavButton active={view === 'legacy'} onClick={() => setView('legacy')} icon={<FileClock className="w-4 h-4" />}>{t.auditCenter.navLegacy}</NavButton>
        </nav>
      </header>

      <Hint id="audit-findings" className="shrink-0" />

      {view === 'overview' && (
        <AuditOverview audits={audits ?? []} loading={isLoading} projectNames={projectNames} locale={locale} onOpen={openAudit} />
      )}
      {view === 'audit' && activeAudit && (
        <AuditDetail audit={activeAudit} tab={auditTab} setTab={setAuditTab} locale={locale}
          projectName={projectNames.get(activeAudit.projectId)} requestedFinding={requestedFinding}
          requestedFindingStatus={requestedFinding ? linkedFinding?.status : undefined} />
      )}
      {view === 'audit' && requestedFinding && !activeAudit && !linkedFindingQuery.isLoading && (
        <div className="flex-1 flex items-center justify-center text-sm text-text-secondary">
          {t.auditCenter.linkedNotFound}
        </div>
      )}
      {view === 'legacy' && <LegacyReports />}
    </div>
  );
}

function AuditOverview({ audits, loading, projectNames, locale, onOpen }: {
  audits: AuditSummary[]; loading: boolean; projectNames: Map<string, string>; locale: Locale;
  onOpen: (audit: AuditSummary) => void;
}) {
  const t = useT();
  if (loading) return <CenteredLoading />;
  if (!audits.length) {
    return <div className="flex-1 flex flex-col items-center justify-center text-text-secondary gap-3">
      <ShieldCheck className="w-10 h-10 opacity-40" />
      <p>{t.auditCenter.empty}</p>
    </div>;
  }
  return (
    <div className="flex-1 min-h-0 overflow-y-auto border-y border-border">
      <div className={`grid ${OVERVIEW_COLUMNS} gap-4 px-4 py-2 text-xs font-medium text-text-tertiary uppercase`}>
        <span>{t.auditCenter.colAudit}</span><span>{t.auditCenter.colGate}</span><span>{t.auditCenter.colTrend}</span>
        <span>{t.auditCenter.colOpenFindings}</span><span>{t.auditCenter.colLastRun}</span><span />
      </div>
      {audits.map((audit) => (
        <button key={`${audit.projectId}:${audit.auditKey}`} onClick={() => onOpen(audit)}
          className={`w-full grid ${OVERVIEW_COLUMNS} gap-4 items-center px-4 py-4 border-t border-border text-left hover:bg-surface-raised transition-colors`}>
          <span className="min-w-0">
            <span className="block font-medium text-text truncate">{audit.title}</span>
            <span className="block text-xs text-text-tertiary truncate">{projectNames.get(audit.projectId) ?? audit.projectId} · {audit.category}</span>
          </span>
          <GateBadge status={audit.gateStatus} />
          <Trend trend={audit.trend} />
          <FindingCounts audit={audit} />
          <span className="text-xs text-text-secondary">
            {formatRelativeTime(audit.generatedAt, locale)}
            {audit.stale && <span className="block text-warning">{t.auditCenter.stale}</span>}
            {audit.latestAttemptStatus === 'INVALID' && <span className="block text-danger">{t.auditCenter.lastAttemptInvalid}</span>}
          </span>
          <ArrowRight className="w-4 h-4 text-text-tertiary" />
        </button>
      ))}
    </div>
  );
}

function AuditDetail({ audit, tab, setTab, locale, projectName, requestedFinding, requestedFindingStatus }: {
  audit: AuditSummary; tab: AuditTab; setTab: (tab: AuditTab) => void; locale: Locale;
  projectName?: string; requestedFinding: string | null; requestedFindingStatus?: AuditFindingStatus;
}) {
  const t = useT();
  const reports = useAuditReports(audit.projectId, audit.auditKey);
  // Kept here, not in the tab: switching to the report and back keeps the chosen chips.
  const [findingFilter, setFindingFilter] = useState(() => initialFindingFilter(requestedFindingStatus));
  const shown = shownReport(reports.data?.content ?? [], audit);
  // Header link, report head and history all point at the run of the report on screen.
  const evidenceRun = shown ? `/workflows/${shown.workflowId}/run/${shown.workflowRunId}` : `/workflows/${audit.workflowId}/run/${audit.latestRunId}`;
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
            {t.auditCenter.workflowLink} <ExternalLink className="w-3 h-3" />
          </Link>
          <Link className="text-xs text-accent hover:underline inline-flex items-center gap-1" to={evidenceRun} data-testid="evidence-run">
            {t.auditCenter.evidenceRun} <ExternalLink className="w-3 h-3" />
          </Link>
        </div>
      </div>
      <div className="flex gap-1">
        <TabButton active={tab === 'report'} onClick={() => setTab('report')} icon={<FileText className="w-4 h-4" />}>{t.auditCenter.tabReport}</TabButton>
        <TabButton active={tab === 'history'} onClick={() => setTab('history')} icon={<History className="w-4 h-4" />}>{t.auditCenter.tabHistory}</TabButton>
        <TabButton active={tab === 'findings'} onClick={() => setTab('findings')} icon={<AlertTriangle className="w-4 h-4" />}>{t.auditCenter.tabFindings.replace('{count}', String(filterCount(audit, 'open')))}</TabButton>
      </div>
      <div className="flex-1 min-h-0 overflow-y-auto border-t border-border pt-5">
        {tab !== 'findings' && reports.isLoading ? <CenteredLoading /> : null}
        {tab === 'report' && shown && <ReportContent report={shown} audit={audit} locale={locale} />}
        {tab === 'history' && <ReportHistory reports={reports.data?.content ?? []} locale={locale} />}
        {tab === 'findings' && <Findings audit={audit} filter={findingFilter} setFilter={setFindingFilter}
          requestedFinding={requestedFinding} locale={locale} />}
      </div>
    </div>
  );
}

/** The report the summary names (`latestReportId`) — the newest valid one; if the page lacks it, the first valid. */
function shownReport(reports: AuditReport[], audit: AuditSummary): AuditReport | undefined {
  return reports.find((report) => report.id === audit.latestReportId)
    ?? reports.find((report) => report.ingestionStatus === 'VALID');
}

const REPORT_DATE: Intl.DateTimeFormatOptions = { dateStyle: 'medium', timeStyle: 'short' };

function ReportContent({ report, audit, locale }: { report: AuditReport; audit: AuditSummary; locale: Locale }) {
  const t = useT();
  const date = formatTimestamp(report.generatedAt, locale, REPORT_DATE);
  return <div className="max-w-5xl">
    {audit.latestAttemptStatus !== 'VALID' && <div role="note" data-testid="older-report-notice"
      className="flex items-start gap-2 mb-4 px-3 py-2 rounded-md border border-warning/40 bg-warning-subtle text-sm text-warning">
      <AlertTriangle className="w-4 h-4 mt-0.5 shrink-0" />
      <div>
        <p>{t.auditCenter.olderReport.replace('{date}', date)}</p>
        {audit.latestAttemptError && <p className="text-xs text-text-secondary mt-1 whitespace-pre-wrap">{audit.latestAttemptError}</p>}
      </div>
    </div>}
    <div className="flex items-center gap-x-3 gap-y-2 text-xs text-text-tertiary mb-5 flex-wrap" data-testid="report-origin">
      <span className="text-text font-medium">{t.auditCenter.reportFrom.replace('{date}', date)}</span>
      <Link to={`/workflows/${report.workflowId}/run/${report.workflowRunId}`} className="text-accent hover:underline">
        {withValue(t.auditCenter.runShort, '{id}', <span className="font-mono">{report.workflowRunId.slice(0, 8)}</span>)}
      </Link>
      <TriggerSourceChip source={report.triggerSource} />
      <ReportChain report={report} />
      {!report.chainRunId && report.scheduleId && report.triggerSource !== 'SCHEDULE' && <span className="inline-flex items-center gap-1">
        <Calendar className="w-3 h-3" /> {t.auditCenter.schedule}
      </span>}
      {report.definitionVersionNumber != null && <span>{t.auditCenter.definitionVersion.replace('{number}', String(report.definitionVersionNumber))}</span>}
      {report.workflowVersion && <span>{t.auditCenter.workflowVersion.replace('{version}', report.workflowVersion)}</span>}
      <span>{report.provider ?? t.auditCenter.providerDefault} / {report.model ?? t.auditCenter.providerDefault}</span>
      <span>{t.auditCenter.findingsInSnapshot.replace('{count}', String(report.findingCount))}</span>
    </div>
    <Markdown content={report.markdownContent} />
  </div>;
}

/** The chain a report's run was an entry of; its name links to that chain run (there is no chain detail page). */
function ReportChain({ report }: { report: AuditReport }) {
  const t = useT();
  if (report.chainId && report.chainRunId) {
    return <Link to={`/chains/${report.chainId}/run/${report.chainRunId}`} title={t.auditCenter.openChainRun}
      className="inline-flex items-center gap-1 text-accent hover:underline" data-testid="report-chain">
      <Link2 className="w-3 h-3" /> {t.auditCenter.chainNamed.replace('{name}', report.chainName ?? report.chainId.slice(0, 8))}
    </Link>;
  }
  if (report.chainRunId) return <span className="inline-flex items-center gap-1" data-testid="report-chain"><Link2 className="w-3 h-3" /> {t.auditCenter.chainGone}</span>;
  return null;
}

function ReportHistory({ reports, locale }: { reports: AuditReport[]; locale: Locale }) {
  const t = useT();
  return <div className="divide-y divide-border">
    {reports.map((report) => <div key={report.id} className="py-4 flex items-start justify-between gap-4" data-testid="report-history-entry">
      <div className="flex items-start gap-3">
        {report.ingestionStatus === 'VALID' ? <CheckCircle2 className="w-5 h-5 text-success mt-0.5" /> : <AlertTriangle className="w-5 h-5 text-danger mt-0.5" />}
        <div>
          <div className="flex gap-2 items-center flex-wrap text-xs">
            <GateBadge status={report.gateStatus} /><Badge variant={report.ingestionStatus === 'VALID' ? 'success' : 'danger'}>{report.ingestionStatus}</Badge>
            <TriggerSourceChip source={report.triggerSource} />
            <ReportChain report={report} />
          </div>
          <p className="text-sm text-text-secondary mt-2">{report.gateDescription ?? report.ingestionError ?? t.auditCenter.noDescription}</p>
          <p className="text-xs text-text-tertiary mt-1">{formatDateTime(report.generatedAt, locale)} · {t.auditCenter.findingsCount.replace('{count}', String(report.findingCount))} · v{report.workflowVersion ?? '?'}</p>
        </div>
      </div>
      <Link to={`/workflows/${report.workflowId}/run/${report.workflowRunId}`} className="text-xs text-accent hover:underline shrink-0">{t.auditCenter.openRun}</Link>
    </div>)}
  </div>;
}

function Findings({ audit, filter, setFilter, requestedFinding, locale }: {
  audit: AuditSummary; filter: FindingFilter[]; setFilter: Dispatch<SetStateAction<FindingFilter[]>>;
  requestedFinding: string | null; locale: Locale;
}) {
  const t = useT();
  const toast = useToast();
  const { findings, capped, isLoading } = useAuditFindingsByStatus(audit.projectId, audit.auditKey,
    filter.flatMap((item) => FINDING_FILTERS[item]));
  const update = useUpdateAuditFinding();
  const createTodo = useCreateAuditTodo();
  const linkTodo = useLinkAuditTodo();
  const [linking, setLinking] = useState<string | null>(null);
  const [todoId, setTodoId] = useState('');
  // Accepting a risk or dismissing a finding asks for the reason first — it stays with the finding
  // (dispositionNote) and is what a later review reads (d75fbfef).
  const [disposing, setDisposing] = useState<{ findingId: string; status: AuditFindingStatus; note: string } | null>(null);
  const filterLabel = (item: FindingFilter) => t.auditCenter.statusBadges[FINDING_FILTERS[item][0]];
  // A finding filed under a status whose chip is off leaves the list — the toast says where it went.
  const announceMove = (updated: AuditFinding) => {
    const target = filterOf(updated.status);
    if (filter.includes(target)) return;
    toast.success(t.auditCenter.movedTo.replace('{filter}', filterLabel(target)), {
      label: t.auditCenter.showFilter,
      onClick: () => setFilter((current) => FILTER_ORDER.filter((item) => item === target || current.includes(item))),
    });
  };
  const chooseStatus = (finding: AuditFinding, status: AuditFindingStatus) => {
    if (NEEDS_REASON.has(status)) {
      setDisposing({ findingId: finding.id, status, note: finding.dispositionNote ?? '' });
      return;
    }
    setDisposing(null);
    update.mutate({ id: finding.id, status }, { onSuccess: announceMove });
  };
  const auditHasFindings = FILTER_ORDER.some((item) => filterCount(audit, item) > 0);
  return <div className="space-y-3">
    <FilterChips ariaLabel={t.auditCenter.findingsFilterLabel} selected={filter} onChange={setFilter}
      options={FILTER_ORDER.map((value) => ({ value, label: filterLabel(value), count: filterCount(audit, value) }))} />
    {isLoading && !findings.length && <CenteredLoading />}
    {!isLoading && !findings.length && <p className="text-sm text-text-secondary">
      {auditHasFindings ? t.auditCenter.noFindingsInFilter : t.auditCenter.noFindings}
    </p>}
    {findings.length > 0 && <div className="divide-y divide-border">
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
                <span>{t.auditCenter.seenTimes.replace('{count}', String(finding.occurrenceCount))}</span>
                {finding.regressionCount > 0 && <span className="text-danger">{t.auditCenter.regressions.replace('{count}', String(finding.regressionCount))}</span>}
                <Link className="text-accent hover:underline" to={`/workflows/${audit.workflowId}/run/${finding.lastSeenRunId}`}>{t.auditCenter.evidenceRun}</Link>
              </div>
              {finding.todos.length > 0 && <div className="flex items-center gap-2 mt-3 flex-wrap">
                {finding.todos.map((todo) => <Link key={todo.id} to={todoDeepLink(todo)} className="inline-flex items-center gap-1 text-xs text-accent hover:underline">
                  <Link2 className="w-3 h-3" /> {todo.title} · {todo.status}{todo.managed ? ` · ${t.auditCenter.managed}` : ''}
                </Link>)}
              </div>}
            </div>
            <div className="flex items-center gap-2 shrink-0 flex-wrap justify-end">
              {!hasManagedTodo && !finding.status.includes('RESOLVED') && <Button size="sm" variant="secondary" icon={<Plus className="w-4 h-4" />} loading={createTodo.isPending} onClick={() => createTodo.mutate(finding.id)}>{t.auditCenter.createTodo}</Button>}
              <select className="bg-surface-raised border border-border rounded-md px-2 py-1.5 text-xs text-text" value={finding.status}
                data-testid="finding-status"
                onChange={(event) => chooseStatus(finding, event.target.value as AuditFindingStatus)}>
                {FINDING_STATUSES.map((status) => <option key={status} value={status}>{t.auditCenter.statusOptions[status]}</option>)}
              </select>
              <button className="p-2 text-text-tertiary hover:text-text" title={t.auditCenter.linkTodoTitle} onClick={() => setLinking(linking === finding.id ? null : finding.id)}><Link2 className="w-4 h-4" /></button>
            </div>
          </div>
          {disposing?.findingId === finding.id && <form className="mt-4 max-w-xl space-y-2" data-testid="disposition-form"
            onSubmit={(event) => {
              event.preventDefault();
              update.mutate({ id: finding.id, status: disposing.status, dispositionNote: disposing.note.trim() },
                { onSuccess: (updated) => { setDisposing(null); announceMove(updated); } });
            }}>
            <label className="block text-xs text-text-secondary" htmlFor={`disposition-${finding.id}`}>
              {t.auditCenter.dispositionPrompt.replace('{status}', t.auditCenter.statusOptions[disposing.status])}
            </label>
            <textarea id={`disposition-${finding.id}`} rows={3} value={disposing.note}
              onChange={(event) => setDisposing({ ...disposing, note: event.target.value })}
              placeholder={t.auditCenter.dispositionPlaceholder}
              className="w-full bg-surface-raised border border-border rounded-md px-3 py-1.5 text-sm text-text" />
            <div className="flex gap-2">
              <Button size="sm" type="submit" loading={update.isPending}>{t.auditCenter.dispositionSave}</Button>
              <Button size="sm" type="button" variant="ghost" onClick={() => setDisposing(null)}>{t.common.cancel}</Button>
            </div>
          </form>}
          {linking === finding.id && <form className="mt-4 flex gap-2 max-w-md" onSubmit={(event) => { event.preventDefault(); if (todoId.trim()) linkTodo.mutate({ findingId: finding.id, todoId: todoId.trim() }, { onSuccess: () => { setTodoId(''); setLinking(null); } }); }}>
            <input className="flex-1 bg-surface-raised border border-border rounded-md px-3 py-1.5 text-sm text-text" placeholder={t.auditCenter.linkTodoPlaceholder} value={todoId} onChange={(event) => setTodoId(event.target.value)} />
            <Button size="sm" type="submit" loading={linkTodo.isPending}>{t.auditCenter.linkTodo}</Button>
          </form>}
        </section>;
      })}
    </div>}
    {capped && <p className="text-xs text-text-tertiary">{t.auditCenter.findingsCapped.replace('{size}', String(FINDINGS_PAGE_SIZE))}</p>}
  </div>;
}

function LegacyReports() {
  const t = useT();
  const [selectedName, setSelectedName] = useState<string | null>(null);
  const reports = useReportsList();
  const active = reports.data?.find((report) => report.name === selectedName) ?? reports.data?.[0] ?? null;
  const content = useReportContent(active?.name ?? null);
  return <div className="flex gap-5 flex-1 min-h-0">
    <aside className="w-72 shrink-0 overflow-y-auto border-r border-border pr-3">
      <p className="text-xs text-text-tertiary mb-3">{t.auditCenter.legacyArchive}</p>
      {reports.isLoading ? <CenteredLoading /> : reports.data?.map((report) => <button key={report.name} onClick={() => setSelectedName(report.name)} className={`w-full flex items-center gap-2 px-3 py-2 text-sm text-left rounded-md ${active?.name === report.name ? 'bg-accent-subtle text-accent' : 'text-text-secondary hover:bg-surface-raised'}`}><FileText className="w-4 h-4 shrink-0" /><span className="truncate">{report.label}</span></button>)}
    </aside>
    <div className="flex-1 min-w-0 overflow-y-auto">{content.isLoading ? <CenteredLoading /> : content.data ? <Markdown content={content.data} /> : <p className="text-text-secondary">{t.auditCenter.legacyNone}</p>}</div>
  </div>;
}

function Markdown({ content }: { content: string }) {
  return <article className="prose prose-sm dark:prose-invert max-w-none prose-headings:text-text prose-p:text-text-secondary prose-a:text-accent prose-code:text-accent prose-pre:bg-surface-overlay prose-pre:border prose-pre:border-border prose-table:border-collapse prose-th:border prose-th:border-border prose-th:bg-surface-overlay prose-td:border prose-td:border-border prose-strong:text-text prose-hr:border-border"><ReactMarkdown remarkPlugins={[remarkGfm]}>{content}</ReactMarkdown></article>;
}

function GateBadge({ status }: { status: AuditSummary['gateStatus'] }) {
  const t = useT();
  const variant = status === 'GREEN' ? 'success' : status === 'YELLOW' ? 'warning' : status === 'RED' ? 'danger' : 'default';
  return <Badge variant={variant} dot>{status ?? t.auditCenter.noGate}</Badge>;
}
function SeverityBadge({ severity }: { severity: AuditFinding['severity'] }) {
  return <Badge variant={severity === 'CRITICAL' || severity === 'HIGH' ? 'danger' : severity === 'MEDIUM' ? 'warning' : 'default'}>{severity}</Badge>;
}
function FindingStatus({ status }: { status: AuditFinding['status'] }) {
  const t = useT();
  return <Badge variant={status === 'OPEN' ? 'danger' : status === 'AWAITING_VERIFICATION' ? 'warning' : 'success'}>{t.auditCenter.statusBadges[status]}</Badge>;
}
function Trend({ trend }: { trend: AuditSummary['trend'] }) {
  const Icon = trend === 'IMPROVED' ? ArrowUp : trend === 'REGRESSED' ? ArrowDown : trend === 'UNCHANGED' ? Minus : Plus;
  const color = trend === 'IMPROVED' ? 'text-success' : trend === 'REGRESSED' ? 'text-danger' : 'text-text-tertiary';
  return <span className={`inline-flex items-center gap-1 text-xs ${color}`}><Icon className="w-3.5 h-3.5" />{trend}</span>;
}
/** Open findings (with their severities) and, apart from them, the accepted risks. */
function FindingCounts({ audit }: { audit: AuditSummary }) {
  const t = useT();
  const counts = audit.openFindingsBySeverity;
  const open = Object.values(counts).reduce((sum, count) => sum + count, 0);
  const accepted = audit.findingsByStatus.ACCEPTED_RISK ?? 0;
  return <span className="text-sm text-text" data-testid="finding-counts">
    {withValue(t.auditCenter.openCount, '{count}', <strong>{open}</strong>)}
    {accepted > 0 && <span className="text-text-tertiary" data-testid="accepted-count"> · {t.auditCenter.acceptedCount.replace('{count}', String(accepted))}</span>}
    <span className="block text-xs text-text-tertiary">{t.auditCenter.severityCounts.replace('{critical}', String(counts.CRITICAL ?? 0)).replace('{high}', String(counts.HIGH ?? 0))}</span>
  </span>;
}

const REVIEW_INTERVAL_MS = 180 * 24 * 60 * 60 * 1000;

function Disposition({ finding, locale }: { finding: AuditFinding; locale: Locale }) {
  const t = useT();
  if (!finding.dispositionAt && !finding.dispositionEvidence && !finding.dispositionNote) return null;
  const formatDay = (value: string | Date) => formatDate(value, locale, { day: '2-digit', month: '2-digit', year: 'numeric' });
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
/** `template` with `placeholder` replaced by a node — for a value that needs its own markup. */
function withValue(template: string, placeholder: string, value: React.ReactNode) {
  const [before, after = ''] = template.split(placeholder);
  return <>{before}{value}{after}</>;
}
function NavButton({ active, disabled, onClick, icon, children }: { active: boolean; disabled?: boolean; onClick: () => void; icon: React.ReactNode; children: React.ReactNode }) {
  return <button disabled={disabled} onClick={onClick} className={`inline-flex items-center gap-2 px-3 py-1.5 text-xs rounded-md disabled:opacity-40 ${active ? 'bg-surface-overlay text-text' : 'text-text-secondary hover:text-text'}`}>{icon}{children}</button>;
}
function TabButton({ active, onClick, icon, children }: { active: boolean; onClick: () => void; icon: React.ReactNode; children: React.ReactNode }) {
  return <button onClick={onClick} className={`inline-flex items-center gap-2 px-3 py-2 text-sm border-b-2 ${active ? 'border-accent text-accent' : 'border-transparent text-text-secondary hover:text-text'}`}>{icon}{children}</button>;
}
function CenteredLoading() { return <div className="flex items-center justify-center py-12 text-text-secondary"><Loader2 className="w-5 h-5 animate-spin" /></div>; }
