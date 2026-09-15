import { useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { ClipboardList, FileText, Loader2 } from 'lucide-react';
import { useReportsList, useReportContent } from '../hooks/useReports';
import { useT } from '../i18n';

export function Reports() {
  const t = useT();
  const [selectedName, setSelectedName] = useState<string | null>(null);

  const { data: reports, isLoading: isLoadingList } = useReportsList();

  const activeReport = reports?.find(r => r.name === selectedName) ?? reports?.[0] ?? null;
  const { data: content, isLoading: isLoadingContent } = useReportContent(activeReport?.name ?? null);

  return (
    <div className="flex flex-col h-full">
      <div className="mb-6">
        <h1 className="text-2xl font-bold">{t.reports.title}</h1>
        <p className="text-text-secondary mt-1">{t.reports.subtitle}</p>
      </div>

      <div className="flex gap-6 flex-1 min-h-0">
        {/* Sidebar */}
        <aside className="w-64 shrink-0 flex flex-col gap-1 overflow-y-auto">
          {isLoadingList ? (
            <div className="flex items-center gap-2 text-text-secondary text-sm px-3 py-2">
              <Loader2 className="w-4 h-4 animate-spin" />
              {t.common.loading}
            </div>
          ) : !reports || reports.length === 0 ? (
            <p className="text-text-secondary text-sm px-3 py-2">{t.reports.noReports}</p>
          ) : (
            reports.map((report) => (
              <button
                key={report.name}
                onClick={() => setSelectedName(report.name)}
                className={`flex items-center gap-2.5 px-3 py-2 rounded-lg text-sm text-left transition-colors w-full
                  ${activeReport?.name === report.name
                    ? 'bg-accent/15 text-accent font-medium'
                    : 'text-text-secondary hover:text-text hover:bg-surface-raised'
                  }`}
              >
                <FileText className="w-4 h-4 shrink-0" />
                <span className="truncate">{report.label}</span>
              </button>
            ))
          )}
        </aside>

        {/* Content */}
        <div className="flex-1 min-w-0 overflow-y-auto rounded-xl border border-card-border bg-surface-raised p-6">
          {!activeReport ? (
            <div className="flex flex-col items-center justify-center h-full text-text-secondary gap-3">
              <ClipboardList className="w-10 h-10 opacity-40" />
              <p>{t.reports.selectReport}</p>
            </div>
          ) : isLoadingContent ? (
            <div className="flex items-center gap-2 text-text-secondary text-sm">
              <Loader2 className="w-4 h-4 animate-spin" />
              {t.common.loading}
            </div>
          ) : content ? (
            <article className="prose prose-sm dark:prose-invert max-w-none
              prose-headings:text-text prose-p:text-text-secondary
              prose-a:text-accent prose-a:no-underline hover:prose-a:underline
              prose-code:text-accent prose-code:bg-surface-overlay prose-code:px-1 prose-code:py-0.5 prose-code:rounded
              prose-pre:bg-surface-overlay prose-pre:border prose-pre:border-border
              prose-table:border-collapse prose-th:border prose-th:border-border prose-th:bg-surface-overlay
              prose-td:border prose-td:border-border
              prose-blockquote:border-accent/40 prose-blockquote:text-text-secondary
              prose-strong:text-text prose-hr:border-border">
              <ReactMarkdown remarkPlugins={[remarkGfm]}>
                {content}
              </ReactMarkdown>
            </article>
          ) : null}
        </div>
      </div>
    </div>
  );
}
