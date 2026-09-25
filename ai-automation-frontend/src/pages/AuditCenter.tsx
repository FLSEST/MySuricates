import { Hint } from '../components/hints/Hint';
import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { ClipboardList, FileClock, FileText, ShieldCheck } from 'lucide-react';
import { AuditDetail, type AuditTab } from '../components/audit/AuditDetail';
import { AuditOverview } from '../components/audit/AuditOverview';
import { LegacyReports } from '../components/audit/LegacyReports';
import { useActiveAudit } from '../hooks/useActiveAudit';
import type { AuditSummary } from '../hooks/useAuditCenter';
import { useProjects } from '../hooks/useProjects';
import { useI18nStore, useT } from '../i18n';

type MainView = 'overview' | 'audit' | 'legacy';

/**
 * Audit Center: the audits at a glance, one audit with report, history and findings, and the legacy
 * archive. `?finding=<id>` opens the findings of that finding's audit. The views live in
 * `components/audit/`, the logic in `useActiveAudit`, `useFindingActions` and `useAuditCenter`.
 */
export function AuditCenter() {
  const t = useT();
  const locale = useI18nStore((state) => state.locale);
  const [searchParams, setSearchParams] = useSearchParams();
  const requestedFinding = searchParams.get('finding');
  const [view, setView] = useState<MainView>(requestedFinding ? 'audit' : 'overview');
  const [auditTab, setAuditTab] = useState<AuditTab>(requestedFinding ? 'findings' : 'report');
  const { audits, loading, activeAudit, linkedStatus, linkPending, select } = useActiveAudit(requestedFinding);
  const { data: projects } = useProjects();

  const projectNames = useMemo(() => new Map(projects?.map((project) => [project.id, project.name]) ?? []), [projects]);

  const openAudit = (audit: AuditSummary) => {
    select(audit);
    // A click in the overview wins over the deep link, which otherwise kept its own audit open (6ff1c580):
    // the link is used up, and the URL says what is open.
    if (requestedFinding) {
      setSearchParams((params) => {
        const next = new URLSearchParams(params);
        next.delete('finding');
        return next;
      }, { replace: true });
    }
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
        <AuditOverview audits={audits} loading={loading} projectNames={projectNames} locale={locale} onOpen={openAudit} />
      )}
      {view === 'audit' && activeAudit && (
        // Keyed by audit: another audit starts with its own chips, not those of the previous one or of a link
        <AuditDetail key={`${activeAudit.projectId}:${activeAudit.auditKey}`}
          audit={activeAudit} tab={auditTab} setTab={setAuditTab} locale={locale}
          projectName={projectNames.get(activeAudit.projectId)} requestedFinding={requestedFinding}
          requestedFindingStatus={linkedStatus} />
      )}
      {view === 'audit' && requestedFinding && !activeAudit && !linkPending && (
        <div className="flex-1 flex items-center justify-center text-sm text-text-secondary">
          {t.auditCenter.linkedNotFound}
        </div>
      )}
      {view === 'legacy' && <LegacyReports />}
    </div>
  );
}

function NavButton({ active, disabled, onClick, icon, children }: { active: boolean; disabled?: boolean; onClick: () => void; icon: React.ReactNode; children: React.ReactNode }) {
  return <button disabled={disabled} onClick={onClick} className={`inline-flex items-center gap-2 px-3 py-1.5 text-xs rounded-md disabled:opacity-40 ${active ? 'bg-surface-overlay text-text' : 'text-text-secondary hover:text-text'}`}>{icon}{children}</button>;
}
