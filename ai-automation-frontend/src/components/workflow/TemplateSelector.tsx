import { useState } from 'react';
import { FileText, Search } from 'lucide-react';
import { Card, Input, Spinner } from '../ui';
import { useT } from '../../i18n';
import { useI18nStore } from '../../i18n';
import { useTemplates } from '../../hooks/useTemplates';
import { resolveTemplateName } from '../../utils/templateUtils';
import { ExecutionBadges } from '../shared/ExecutionBadges';
import type { WorkflowTemplate } from '../../types';

interface TemplateSelectorProps {
  onSelect: (template: WorkflowTemplate | null) => void;
}

export function TemplateSelector({ onSelect }: TemplateSelectorProps) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const { data: templates, isLoading } = useTemplates();
  const [search, setSearch] = useState('');

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
                  <div>
                    <h4 className="font-semibold text-text">
                      {resolveTemplateName(tmpl.name, locale)}
                    </h4>
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
    </div>
  );
}
