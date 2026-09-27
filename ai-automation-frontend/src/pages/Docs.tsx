import { Hint } from '../components/hints/Hint';
import { useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { BookOpen, FileText, Loader2 } from 'lucide-react';
import { useGroupedDocs, useDocContent } from '../hooks/useDocs';
import { useT } from '../i18n';

export function Docs() {
  const t = useT();
  const [selectedLabel, setSelectedLabel] = useState<string | null>(null);
  const [lang, setLang] = useState<'de' | 'en'>('de');

  const { isLoading: isLoadingList, grouped } = useGroupedDocs();

  const activeGroup = grouped.find(g => g.label === selectedLabel) ?? grouped[0] ?? null;
  const hasAnyEn = grouped.some(g => g.en !== null);

  // Resolve which file to load: prefer requested lang, fall back to de
  const enMissing = lang === 'en' && activeGroup !== null && activeGroup.en === null;
  const activeName = activeGroup
    ? (lang === 'en' && activeGroup.en ? activeGroup.en : activeGroup.de)
    : null;
  const activeLang = (lang === 'en' && activeGroup?.en) ? 'en' : 'de';

  const { data: content, isLoading: isLoadingContent } = useDocContent(activeName, activeLang);

  return (
    <div className="flex flex-col h-full">
      <div className="mb-6 flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold">{t.docs.title}</h1>
          <p className="text-text-secondary mt-1">{t.docs.subtitle}</p>
        </div>
        {hasAnyEn && (
          <div className="flex items-center gap-1 shrink-0 mt-1">
            <button
              onClick={() => setLang('de')}
              className={`px-3 py-1 rounded-md text-sm font-medium transition-colors ${
                lang === 'de'
                  ? 'bg-accent text-text-inverse'
                  : 'text-text-secondary hover:text-text hover:bg-surface-raised'
              }`}
            >
              DE
            </button>
            <button
              onClick={() => setLang('en')}
              className={`px-3 py-1 rounded-md text-sm font-medium transition-colors ${
                lang === 'en'
                  ? 'bg-accent text-text-inverse'
                  : 'text-text-secondary hover:text-text hover:bg-surface-raised'
              }`}
            >
              EN
            </button>
          </div>
        )}
      </div>

      <Hint id="docs-reading" />

      <div className="flex gap-6 flex-1 min-h-0">
        {/* Sidebar */}
        <aside className="w-64 shrink-0 flex flex-col gap-1 overflow-y-auto">
          {isLoadingList ? (
            <div className="flex items-center gap-2 text-text-secondary text-sm px-3 py-2">
              <Loader2 className="w-4 h-4 animate-spin" />
              {t.common.loading}
            </div>
          ) : grouped.length === 0 ? (
            <p className="text-text-secondary text-sm px-3 py-2">{t.docs.noDocs}</p>
          ) : (
            grouped.map((doc) => (
              <button
                key={doc.label}
                onClick={() => setSelectedLabel(doc.label)}
                className={`flex items-center gap-2.5 px-3 py-2 rounded-lg text-sm text-left transition-colors w-full
                  ${activeGroup?.label === doc.label
                    ? 'bg-accent/15 text-accent font-medium'
                    : 'text-text-secondary hover:text-text hover:bg-surface-raised'
                  }`}
              >
                <FileText className="w-4 h-4 shrink-0" />
                <span className="truncate">{doc.label}</span>
              </button>
            ))
          )}
        </aside>

        {/* Content */}
        <div className="flex-1 min-w-0 overflow-y-auto rounded-xl border border-card-border bg-surface-raised p-6">
          {!activeGroup ? (
            <div className="flex flex-col items-center justify-center h-full text-text-secondary gap-3">
              <BookOpen className="w-10 h-10 opacity-40" />
              <p>{t.docs.selectDoc}</p>
            </div>
          ) : isLoadingContent ? (
            <div className="flex items-center gap-2 text-text-secondary text-sm">
              <Loader2 className="w-4 h-4 animate-spin" />
              {t.common.loading}
            </div>
          ) : content ? (
            <>
              {enMissing && (
                <p className="text-xs text-text-secondary mb-4 italic">{t.docs.noEnVersion}</p>
              )}
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
            </>
          ) : null}
        </div>
      </div>
    </div>
  );
}
