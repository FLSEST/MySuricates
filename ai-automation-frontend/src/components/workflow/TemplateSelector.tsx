import { useState } from 'react';
import { CheckCircle, FileText, Search } from 'lucide-react';
import { Card, Input, Spinner } from '../ui';
import { useT } from '../../i18n';
import { useI18nStore } from '../../i18n';
import { useTemplates } from '../../hooks/useTemplates';
import { resolveTemplateName } from '../../utils/templateUtils';
import { ExecutionBadges } from '../shared/ExecutionBadges';
import { PoolBrowser } from './PoolBrowser';
import type { WorkflowTemplate, PoolWorkflowEntry } from '../../types';

type Tab = 'templates' | 'pool';

interface TemplateSelectorProps {
  onSelect: (template: WorkflowTemplate | null) => void;
  onPoolSelect?: (entry: PoolWorkflowEntry) => void;
}

export function TemplateSelector({ onSelect, onPoolSelect }: TemplateSelectorProps) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const { data: templates, isLoading } = useTemplates();
  const [search, setSearch] = useState('');
  const [activeTab, setActiveTab] = useState<Tab>('templates');

  const filtered = search.trim()
    ? (templates ?? []).filter((tmpl) => {
        const name = resolveTemplateName(tmpl.name, locale).toLowerCase();
        const desc = resolveTemplateName(tmpl.description, locale).toLowerCase();
        const q = search.toLowerCase();
        return name.includes(q) || desc.includes(q);
      })
    : (templates ?? []);

  return (
    <div className="space-y-4">
      {/* Tabs */}
      <div className="flex border-b border-border">
        <button
          onClick={() => setActiveTab('templates')}
          className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
            activeTab === 'templates'
              ? 'border-accent text-accent'
              : 'border-transparent text-text-secondary hover:text-text'
          }`}
        >
          {t.templates.tab}
        </button>
        <button
          onClick={() => setActiveTab('pool')}
          className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
            activeTab === 'pool'
              ? 'border-accent text-accent'
              : 'border-transparent text-text-secondary hover:text-text'
          }`}
        >
          {t.pool.tab}
        </button>
      </div>

      {activeTab === 'pool' ? (
        <PoolBrowser onSelect={(entry) => onPoolSelect?.(entry)} />
      ) : (
        <>
          <div>
            <h3 className="text-lg font-semibold text-text">{t.templates.title}</h3>
            <p className="text-sm text-text-secondary mt-1">{t.templates.subtitle}</p>
          </div>

          <Input
            placeholder={t.templates.searchPlaceholder}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            icon={<Search className="w-4 h-4 text-text-tertiary" />}
          />

          {isLoading ? (
            <div className="flex justify-center py-8">
              <Spinner text={t.common.loading} />
            </div>
          ) : (
            <div className="max-h-[60vh] overflow-y-auto grid grid-cols-1 sm:grid-cols-2 gap-3">
              {/* Blank option - only when not searching */}
              {!search.trim() && (
                <Card
                  className="cursor-pointer hover:border-border-strong transition-colors"
                  onClick={() => onSelect(null)}
                >
                  <div className="flex items-center gap-3 mb-2">
                    <div className="p-2 rounded-lg bg-surface">
                      <FileText className="w-5 h-5 text-text-tertiary" />
                    </div>
                    <h4 className="font-semibold text-text">{t.templates.blank}</h4>
                  </div>
                  <p className="text-xs text-text-secondary">{t.templates.blankDescription}</p>
                </Card>
              )}

              {/* Template options */}
              {filtered.length === 0 ? (
                <div className="col-span-2 py-8 text-center text-sm text-text-tertiary">
                  {t.common.noResults}
                </div>
              ) : (
                filtered.map((tmpl) => (
                  <Card
                    key={tmpl.id}
                    className="cursor-pointer hover:border-border-strong transition-colors"
                    onClick={() => onSelect(tmpl)}
                  >
                    <div className="flex items-center gap-3 mb-2">
                      <span className="text-2xl">{tmpl.icon}</span>
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-2">
                          <h4 className="font-semibold text-text truncate">
                            {resolveTemplateName(tmpl.name, locale)}
                          </h4>
                          {tmpl.qualityStatus === 'TESTED' && (
                            <span
                              className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded-full text-[10px] font-medium bg-green-500/15 text-green-600 dark:text-green-400 shrink-0"
                              title={`${t.templates.testedIn} ${tmpl.verifications.map((v) => v.projectName).join(', ')}`}
                            >
                              <CheckCircle className="w-3 h-3" />
                              {t.templates.tested}
                            </span>
                          )}
                        </div>
                        <div className="flex items-center gap-2">
                          <span className="text-xs text-text-tertiary">
                            {tmpl.blocks.length} {t.workflows.blocks.toLowerCase()}
                          </span>
                          <ExecutionBadges blocks={tmpl.blocks} />
                        </div>
                      </div>
                    </div>
                    <p className="text-xs text-text-secondary">
                      {resolveTemplateName(tmpl.description, locale)}
                    </p>
                  </Card>
                ))
              )}
            </div>
          )}
        </>
      )}
    </div>
  );
}
