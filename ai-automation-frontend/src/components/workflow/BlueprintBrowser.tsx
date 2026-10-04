import { useState } from 'react';
import { FileText, Search } from 'lucide-react';
import { Card, Input } from '../ui';
import { useI18nStore, useT } from '../../i18n';
import { resolveTemplateName } from '../../utils/templateUtils';
import { useSystemModules } from '../../hooks/useModules';
import { ExecutionBadges } from '../shared/ExecutionBadges';
import { NeedsModuleToggle, TemplateModuleNote } from './TemplateAvailability';
import { missingModules, splitByAvailability } from './templateAvailability';
import type { WorkflowTemplate } from '../../types';

/** Gallery order of the blueprint groups; a group the backend doesn't know yet goes last. */
const GROUPS = ['BASICS', 'COMBINATIONS', 'AROUND', 'AI'] as const;
type Group = (typeof GROUPS)[number];

interface BlueprintBrowserProps {
  blueprints: WorkflowTemplate[];
  /** `null` = the blank workflow, the emptiest scaffold of all */
  onSelect: (blueprint: WorkflowTemplate | null) => void;
}

/**
 * Third tab next to templates and pool (TODO 268ae0db): empty scaffolds with runnable placeholders
 * that show how block types connect. Selecting one starts the same creation flow as a template.
 */
export function BlueprintBrowser({ blueprints, onSelect }: BlueprintBrowserProps) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const [search, setSearch] = useState('');
  const { data: moduleState } = useSystemModules();
  // Like the templates tab: what needs a switched-off module waits behind a toggle (TODO 560d18dd)
  const [showNeedsModule, setShowNeedsModule] = useState(false);

  const q = search.trim().toLowerCase();
  const found = blueprints.filter((bp) => !q
    || resolveTemplateName(bp.name, locale).toLowerCase().includes(q)
    || resolveTemplateName(bp.description, locale).toLowerCase().includes(q));
  const modules = moduleState?.modules;
  const { runnable, needsModule } = splitByAvailability(found, modules);
  const matching = showNeedsModule ? [...runnable, ...needsModule] : runnable;

  const groupOf = (bp: WorkflowTemplate): string => bp.category ?? '';
  const known = new Set<string>(GROUPS);
  const groups = [...GROUPS, ...new Set(matching.map(groupOf).filter((g) => !known.has(g)))];

  return (
    <div className="space-y-4">
      <div>
        <h3 className="text-lg font-semibold text-text">{t.blueprints.title}</h3>
        <p className="text-sm text-text-secondary mt-1">{t.blueprints.subtitle}</p>
      </div>

      <Input
        placeholder={t.blueprints.searchPlaceholder}
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        icon={<Search className="w-4 h-4 text-text-tertiary" />}
      />

      <div className="max-h-[60vh] overflow-y-auto space-y-5">
        {!q && (
          <Card className="cursor-pointer hover:border-border-strong transition-colors" onClick={() => onSelect(null)}>
            <div className="flex items-center gap-3 mb-2">
              <div className="p-2 rounded-lg bg-surface">
                <FileText className="w-5 h-5 text-text-tertiary" />
              </div>
              <h5 className="font-semibold text-text">{t.templates.blank}</h5>
            </div>
            <p className="text-xs text-text-secondary">{t.templates.blankDescription}</p>
          </Card>
        )}
        {q && matching.length === 0 && (
          <div className="py-8 text-center text-sm text-text-tertiary">{t.common.noResults}</div>
        )}
        {groups.map((group) => {
          const inGroup = matching.filter((bp) => groupOf(bp) === group)
            .sort((a, b) => a.sortOrder - b.sortOrder);
          if (inGroup.length === 0) return null;
          return (
            <section key={group} aria-label={t.blueprints.groups[group as Group] ?? group}>
              <h4 className="mb-2 text-xs font-semibold uppercase tracking-wide text-text-tertiary">
                {t.blueprints.groups[group as Group] ?? group}
              </h4>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                {inGroup.map((bp) => (
                  <Card
                    key={bp.id}
                    className="cursor-pointer hover:border-border-strong transition-colors"
                    onClick={() => onSelect(bp)}
                  >
                    <div className="flex items-start gap-3 mb-2">
                      <span className="text-2xl mt-0.5">{bp.icon}</span>
                      <div className="min-w-0 flex-1">
                        <h5 className="font-semibold text-text truncate">{resolveTemplateName(bp.name, locale)}</h5>
                        <div className="flex items-center gap-2">
                          <span className="text-xs text-text-tertiary">
                            {bp.blocks.length} {t.workflows.blocks.toLowerCase()}
                          </span>
                          <ExecutionBadges blocks={bp.blocks} />
                        </div>
                      </div>
                    </div>
                    <p className="text-xs text-text-secondary">{resolveTemplateName(bp.description, locale)}</p>
                    <TemplateModuleNote missing={missingModules(bp, modules)} />
                  </Card>
                ))}
              </div>
            </section>
          );
        })}
        {needsModule.length > 0 && (
          <div className="text-center">
            <NeedsModuleToggle
              count={needsModule.length}
              shown={showNeedsModule}
              onToggle={() => setShowNeedsModule((on) => !on)}
            />
          </div>
        )}
      </div>
    </div>
  );
}
