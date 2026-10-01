import { useState } from 'react';
import { Sparkles, ChevronDown, ChevronRight, Play } from 'lucide-react';
import { Card, Button, Input, Select, Spinner, Badge, Toggle } from '../ui';
import { useProjects } from '../../hooks/useProjects';
import { useFactoryTopics } from '../../hooks/useWorkflowFactory';
import { useT } from '../../i18n';
import { providerOptions } from '../shared/aiProviderStatus';
import type { FactoryForm } from '../../hooks/useFactoryForm';

/**
 * Creation form of the Workflow Factory: topic, description, project, category and provider with
 * "Create"; test inputs, auto-promote/auto-fix and "Create & Test" behind "Advanced options".
 */
export function FactoryCreateForm({ form }: { form: FactoryForm }) {
  const t = useT();
  const { data: projects } = useProjects();
  const { data: topics } = useFactoryTopics();
  const [showAdvanced, setShowAdvanced] = useState(false);

  const categoryOptions = [
    { value: '', label: t.common.select },
    ...(topics?.map(t => ({ value: t.id, label: `${t.name} — ${t.description}` })) ?? []),
  ];

  const projectOptions = [
    { value: '', label: t.common.selectProject },
    ...(projects?.map(p => ({ value: p.id, label: p.name })) ?? []),
  ];

  return (
    <Card className="p-6">
      <div className="space-y-4">
        {/* Row 1: Topic */}
        <Input
          label={t.factory.topicLabel}
          placeholder={t.factory.topicPlaceholder}
          value={form.topic}
          onChange={e => form.setTopic(e.target.value)}
        />

        {/* Row 2: Description */}
        <div>
          <label className="block text-sm font-medium text-text-secondary mb-1.5">{t.factory.descriptionLabel}</label>
          <textarea
            className="input-base w-full"
            rows={3}
            placeholder={t.factory.descriptionPlaceholder}
            value={form.description}
            onChange={e => form.setDescription(e.target.value)}
          />
        </div>

        {/* Row 3: Project, Category, Provider */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <Select
            label={t.factory.projectLabel}
            options={projectOptions}
            value={form.projectId}
            onChange={e => form.setProjectId(e.target.value)}
          />
          <Select
            label={t.factory.categoryLabel}
            options={categoryOptions}
            value={form.category}
            onChange={e => form.setCategory(e.target.value)}
          />
          <Select
            label={t.factory.providerLabel}
            options={[
              { value: '', label: t.factory.providerDefault },
              ...providerOptions(['CLAUDE', 'CODEX', 'PI']),
            ]}
            value={form.provider}
            onChange={e => form.setProvider(e.target.value)}
          />
        </div>
      </div>

      {/* Primary Action */}
      <div className="flex items-center gap-3 mt-5">
        <Button
          onClick={form.create}
          disabled={form.isRunning || !form.topic.trim() || !form.projectId}
          variant="primary"
        >
          {form.isGenerating ? <Spinner size="sm" /> : <Sparkles className="w-4 h-4" />}
          {form.isGenerating ? t.factory.generating : t.factory.createWorkflow}
        </Button>
        <span className="text-xs text-text-secondary">{t.factory.createHint}</span>
      </div>

      {/* Advanced Options */}
      <div className="mt-4 pt-4 border-t border-border">
        <button
          className="flex items-center gap-1.5 text-sm text-text-secondary hover:text-text transition-colors"
          onClick={() => setShowAdvanced(!showAdvanced)}
        >
          {showAdvanced ? <ChevronDown className="w-4 h-4" /> : <ChevronRight className="w-4 h-4" />}
          {t.factory.advancedOptions}
          {!form.testHealthy && (
            <span className="ml-2"><Badge variant="warning">{t.factory.testOffline}</Badge></span>
          )}
        </button>

        {showAdvanced && (
          <div className="mt-4 space-y-4">
            <div>
              <label className="block text-sm font-medium text-text-secondary mb-1.5">{t.factory.testInputsLabel}</label>
              <textarea
                className="input-base w-full font-mono text-sm"
                rows={3}
                placeholder={t.factory.testInputsPlaceholder}
                value={form.testInputs}
                onChange={e => form.setTestInputs(e.target.value)}
              />
            </div>

            <div className="flex flex-wrap items-center gap-6">
              <Toggle
                label={t.factory.autoPromote}
                enabled={form.autoPromote}
                onChange={form.setAutoPromote}
              />
              <Toggle
                label={t.factory.autoFix}
                enabled={form.autoFix}
                onChange={form.setAutoFix}
              />
              {form.autoFix && (
                <div className="flex items-center gap-2">
                  <label className="text-sm text-text-secondary">{t.factory.maxRetries}</label>
                  <input
                    type="number"
                    min={1}
                    max={5}
                    value={form.maxRetries}
                    onChange={e => form.setMaxRetries(Number(e.target.value))}
                    className="input-base w-16 text-center"
                  />
                </div>
              )}
            </div>

            <div className="flex flex-wrap items-center gap-3">
              <Button
                onClick={form.createAndTest}
                disabled={form.isRunning || !form.topic.trim() || !form.projectId || !form.testHealthy}
                variant="secondary"
              >
                {form.isTesting ? <Spinner size="sm" /> : <Play className="w-4 h-4" />}
                {form.isTesting ? t.factory.testing : t.factory.createAndTest}
              </Button>
              <span className="text-xs text-text-secondary">{t.factory.createAndTestHint}</span>
            </div>
          </div>
        )}
      </div>
    </Card>
  );
}
