import { useState } from 'react';
import { CheckCircle, Download, FileText, Search, Terminal } from 'lucide-react';
import { Card, Input, Spinner } from '../ui';
import { useT } from '../../i18n';
import { useI18nStore } from '../../i18n';
import { useTemplates, exportTemplate } from '../../hooks/useTemplates';
import { useModuleEnabled, useSystemModules } from '../../hooks/useModules';
import { categoryLabel, resolveTemplateName } from '../../utils/templateUtils';
import { ExecutionBadges } from '../shared/ExecutionBadges';
import { Hint } from '../hints/Hint';
import { PoolBrowser } from './PoolBrowser';
import { BlueprintBrowser } from './BlueprintBrowser';
import { ProHandoff } from '../simple/ProHandoff';
import { NeedsModuleToggle, TemplateModuleNote } from './TemplateAvailability';
import { missingModules, splitByAvailability, templateNeedsAi } from './templateAvailability';
import type { WorkflowTemplate, PoolWorkflowEntry } from '../../types';

type Tab = 'templates' | 'pool' | 'blueprints';

interface TemplateSelectorProps {
  onSelect: (template: WorkflowTemplate | null) => void;
  onPoolSelect?: (entry: PoolWorkflowEntry) => void;
  /**
   * `simple`: only what runs as it is — templates first, the pool next to them. Blueprints and the
   * blank workflow need the editor, which Simple mode does not have, so a visible hand-off to Pro
   * takes their place instead of a dead end.
   */
  variant?: 'pro' | 'simple';
}

export function TemplateSelector({ onSelect, onPoolSelect, variant = 'pro' }: TemplateSelectorProps) {
  const simple = variant === 'simple';
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const { data: templates, isLoading } = useTemplates();
  const { data: moduleState } = useSystemModules();
  const [search, setSearch] = useState('');
  // Blueprints come first: they show what the building blocks can do (TODO 268ae0db)
  const [activeTab, setActiveTab] = useState<Tab>(simple ? 'templates' : 'blueprints');
  const [selectedCategory, setSelectedCategory] = useState<string | null>(null);
  // "Runs without AI" (TODO 9c4c3b7c): the server's needsAi, the same rule as the AI badge
  const [withoutAi, setWithoutAi] = useState(false);
  // Templates whose module is off stay hidden until asked for (TODO 560d18dd)
  const [showNeedsModule, setShowNeedsModule] = useState(false);
  // No way to build from a description here (preset core): say that they exist (TODO 3f96f7a0)
  const assistantOn = useModuleEnabled('assistant');
  const factoryOn = useModuleEnabled('factory');
  const wishesOn = useModuleEnabled('wishes');
  const describingIsOff = !assistantOn && !factoryOn && !wishesOn;

  // Blueprints share the endpoint but have their own tab
  const taskTemplates = (templates ?? []).filter((tmpl) => tmpl.kind !== 'BLUEPRINT');
  const blueprints = (templates ?? []).filter((tmpl) => tmpl.kind === 'BLUEPRINT');

  const allCategories = Array.from(
    new Set(taskTemplates.map((tmpl) => tmpl.category).filter(Boolean) as string[]),
  ).sort((a, b) => categoryLabel(t, a).localeCompare(categoryLabel(t, b), locale));

  const filtered = taskTemplates.filter((tmpl) => {
    if (selectedCategory && tmpl.category !== selectedCategory) return false;
    if (withoutAi && templateNeedsAi(tmpl)) return false;
    if (!search.trim()) return true;
    const name = resolveTemplateName(tmpl.name, locale).toLowerCase();
    const desc = resolveTemplateName(tmpl.description, locale).toLowerCase();
    const q = search.toLowerCase();
    return name.includes(q) || desc.includes(q);
  });
  const modules = moduleState?.modules;
  const { runnable, needsModule } = splitByAvailability(filtered, modules);
  const shown = showNeedsModule ? [...runnable, ...needsModule] : runnable;

  return (
    <div className="space-y-4">
      {simple && <Hint id="simple-create" />}

      {/* Tabs */}
      <div className="flex border-b border-border">
        {!simple && (
          <button
            onClick={() => setActiveTab('blueprints')}
            className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
              activeTab === 'blueprints'
                ? 'border-accent text-accent'
                : 'border-transparent text-text-secondary hover:text-text'
            }`}
          >
            {t.blueprints.tab}
          </button>
        )}
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

      {!simple && describingIsOff && <Hint id="build-from-description" />}

      {activeTab === 'pool' ? (
        <PoolBrowser onSelect={(entry) => onPoolSelect?.(entry)} variant={variant} />
      ) : activeTab === 'blueprints' ? (
        isLoading ? (
          <div className="flex justify-center py-8">
            <Spinner text={t.common.loading} />
          </div>
        ) : (
          <BlueprintBrowser blueprints={blueprints} onSelect={onSelect} />
        )
      ) : (
        <>
          <div>
            <h3 className="text-lg font-semibold text-text">{t.templates.title}</h3>
            <p className="text-sm text-text-secondary mt-1">{simple ? t.simple.create.subtitle : t.templates.subtitle}</p>
          </div>

          <Input
            placeholder={t.templates.searchPlaceholder}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            icon={<Search className="w-4 h-4 text-text-tertiary" />}
          />

          <div className="flex flex-wrap items-center gap-1.5">
            <button
              type="button"
              aria-pressed={withoutAi}
              title={t.templates.withoutAiHint}
              onClick={() => setWithoutAi((on) => !on)}
              className={`inline-flex items-center gap-1 px-2.5 py-1 rounded-full border text-xs font-medium transition-colors ${
                withoutAi
                  ? 'border-accent bg-accent-subtle text-accent'
                  : 'border-border text-text-secondary hover:text-text'
              }`}
            >
              <Terminal className="w-3 h-3" aria-hidden="true" />
              {t.templates.withoutAi}
            </button>
            {allCategories.length > 1 && (
              <>
                <span aria-hidden="true" className="mx-0.5 h-4 w-px bg-border" />
                <button
                  onClick={() => setSelectedCategory(null)}
                  className={`px-2.5 py-1 rounded-full text-xs font-medium transition-colors ${
                    !selectedCategory
                      ? 'bg-accent text-text-inverse'
                      : 'bg-surface-raised text-text-secondary hover:text-text'
                  }`}
                >
                  {t.common.all}
                </button>
                {allCategories.map((cat) => (
                  <button
                    key={cat}
                    onClick={() => setSelectedCategory(selectedCategory === cat ? null : cat)}
                    className={`px-2.5 py-1 rounded-full text-xs font-medium transition-colors ${
                      selectedCategory === cat
                        ? 'bg-accent text-text-inverse'
                        : 'bg-surface-raised text-text-secondary hover:text-text'
                    }`}
                  >
                    {categoryLabel(t, cat)}
                  </button>
                ))}
              </>
            )}
          </div>

          {isLoading ? (
            <div className="flex justify-center py-8">
              <Spinner text={t.common.loading} />
            </div>
          ) : (
            <div className="max-h-[60vh] overflow-y-auto grid grid-cols-1 sm:grid-cols-2 gap-3">
              {/* Blank option - only when not searching */}
              {!simple && !search.trim() && (
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
              {shown.length === 0 ? (
                <div className="col-span-2 py-8 text-center text-sm text-text-tertiary">
                  {t.common.noResults}
                </div>
              ) : (
                shown.map((tmpl) => (
                  <Card
                    key={tmpl.id}
                    className="cursor-pointer hover:border-border-strong transition-colors"
                    onClick={() => onSelect(tmpl)}
                  >
                    <div className="flex items-start gap-3 mb-2">
                      <span className="text-2xl mt-0.5">{tmpl.icon}</span>
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-2">
                          <h4 className="font-semibold text-text truncate">
                            {resolveTemplateName(tmpl.name, locale)}
                          </h4>
                          {tmpl.qualityStatus === 'TESTED' && (
                            <span
                              className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded-full text-[10px] font-medium bg-success/15 text-success shrink-0"
                              title={`${t.templates.testedIn} ${tmpl.verifications.map((v) => v.projectName).join(', ')}`}
                            >
                              <CheckCircle className="w-3 h-3" />
                              {t.templates.tested}
                            </span>
                          )}
                        </div>
                        {/* Step count, execution kinds and export are build details — Pro only */}
                        {!simple && (
                          <div className="flex items-center gap-2">
                            <span className="text-xs text-text-tertiary">
                              {tmpl.blocks.length} {t.workflows.blocks.toLowerCase()}
                            </span>
                            <ExecutionBadges blocks={tmpl.blocks} />
                          </div>
                        )}
                      </div>
                      {!simple && (
                        <button
                          className="p-1 rounded text-text-tertiary hover:text-text hover:bg-surface-raised transition-colors shrink-0"
                          title={t.templates.exportTemplate}
                          onClick={(e) => {
                            e.stopPropagation();
                            exportTemplate(tmpl, locale);
                          }}
                        >
                          <Download className="w-3.5 h-3.5" />
                        </button>
                      )}
                    </div>
                    <p className="text-xs text-text-secondary">
                      {resolveTemplateName(tmpl.description, locale)}
                    </p>
                    <TemplateModuleNote missing={missingModules(tmpl, modules)} />
                  </Card>
                ))
              )}
              {needsModule.length > 0 && (
                <div className="col-span-full pt-1 text-center">
                  <NeedsModuleToggle
                    count={needsModule.length}
                    shown={showNeedsModule}
                    onToggle={() => setShowNeedsModule((on) => !on)}
                  />
                </div>
              )}
            </div>
          )}
        </>
      )}

      {simple && <ProHandoff reason={t.simple.create.buildInPro} to="/workflows" />}
    </div>
  );
}
