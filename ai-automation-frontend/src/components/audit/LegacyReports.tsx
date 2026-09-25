import { useState } from 'react';
import { FileText } from 'lucide-react';
import { useReportContent, useReportsList } from '../../hooks/useReports';
import { useT } from '../../i18n';
import { AuditMarkdown, CenteredLoading } from './AuditParts';

/** The read-only archive of file-based Markdown reports from before the Audit Center. */
export function LegacyReports() {
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
    <div className="flex-1 min-w-0 overflow-y-auto">{content.isLoading ? <CenteredLoading /> : content.data ? <AuditMarkdown content={content.data} /> : <p className="text-text-secondary">{t.auditCenter.legacyNone}</p>}</div>
  </div>;
}
