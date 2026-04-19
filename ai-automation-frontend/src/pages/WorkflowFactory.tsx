import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Factory, Sparkles, CheckCircle2, XCircle, Clock, ArrowUpRight,
  RotateCcw, Award, Package, ChevronDown, ChevronRight, Play,
} from 'lucide-react';
import { Card, Button, Input, Select, Spinner, Badge, Toggle } from '../components/ui';
import { useProjects } from '../hooks/useProjects';
import {
  useFactoryTopics, useFactoryGenerate, useFactoryRun, useFactoryVerify, useFactoryPromote,
} from '../hooks/useWorkflowFactory';
import { useWorkflows } from '../hooks/useWorkflows';
import { useTestingStatus } from '../hooks/useTesting';
import { useT } from '../i18n';
import { useToast } from '../hooks/useToast';
import { formatDuration } from '../utils/formatTime';
import { ExportToPoolModal } from '../components/factory/ExportToPoolModal';
import type { WorkflowFactoryResult } from '../types';

function StatusIcon({ status }: { status: string }) {
  switch (status) {
    case 'VERIFIED': return <CheckCircle2 className="w-5 h-5 text-emerald-500" />;
    case 'FAILED': return <XCircle className="w-5 h-5 text-red-500" />;
    case 'CREATED': return <Clock className="w-5 h-5 text-blue-500" />;
    case 'PROMOTED': return <Award className="w-5 h-5 text-amber-500" />;
    case 'EXPORTED': return <Package className="w-5 h-5 text-violet-500" />;
    default: return <Spinner size="sm" />;
  }
}

function ResultStatusBadge({ status }: { status: string }) {
  const variant = status === 'VERIFIED' ? 'success'
    : status === 'PROMOTED' ? 'warning'
    : status === 'FAILED' ? 'danger'
    : status === 'EXPORTED' ? 'accent'
    : 'default';
  return <Badge variant={variant} dot>{status}</Badge>;
}

export function WorkflowFactory() {
  const t = useT();
  const navigate = useNavigate();
  const toast = useToast();
  const { data: projects } = useProjects();
  const { data: topics } = useFactoryTopics();
  const { data: workflows } = useWorkflows();
  const { data: testingStatus } = useTestingStatus();
  const generateMutation = useFactoryGenerate();
  const runMutation = useFactoryRun();
  const verifyMutation = useFactoryVerify();
  const promoteMutation = useFactoryPromote();

  const [topic, setTopic] = useState('');
  const [description, setDescription] = useState('');
  const [category, setCategory] = useState('');
  const [projectId, setProjectId] = useState('');
  const [testInputs, setTestInputs] = useState('');
  const [provider, setProvider] = useState('');
  const [autoPromote, setAutoPromote] = useState(false);
  const [autoFix, setAutoFix] = useState(false);
  const [maxRetries, setMaxRetries] = useState(2);
  const [results, setResults] = useState<WorkflowFactoryResult[]>([]);
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [exportModal, setExportModal] = useState<{
    workflowId: string; name: string; description?: string | null;
  } | null>(null);

  const isTestHealthy = testingStatus?.healthy ?? false;
  const isRunning = generateMutation.isPending || runMutation.isPending;

  const parseTestInputs = (): Record<string, unknown> => {
    if (!testInputs.trim()) return {};
    try {
      return JSON.parse(testInputs);
    } catch {
      toast.error('Invalid JSON in test inputs');
      return {};
    }
  };

  const handleCreate = () => {
    if (!topic.trim()) { toast.error(t.factory.topicRequired); return; }
    if (!projectId) { toast.error(t.factory.projectRequired); return; }

    generateMutation.mutate({
      topic, description: description || undefined, category: category || undefined,
      projectId, testInputs: parseTestInputs(), provider: provider || undefined,
    }, {
      onSuccess: (workflow) => {
        toast.success(`Workflow "${workflow.name}" created`);
        setResults(prev => [{
          workflowId: workflow.id, workflowName: workflow.name,
          status: 'CREATED', testRunId: null, testStatus: null, testError: null,
          promotedAsTemplate: false, durationMs: 0, retryErrors: [],
        }, ...prev]);
      },
      onError: () => toast.error('Generation failed'),
    });
  };

  const handleCreateAndTest = () => {
    if (!topic.trim()) { toast.error(t.factory.topicRequired); return; }
    if (!projectId) { toast.error(t.factory.projectRequired); return; }
    if (!isTestHealthy) { toast.error(t.factory.testOffline); return; }

    runMutation.mutate({
      topic, description: description || undefined, category: category || undefined,
      projectId, testInputs: parseTestInputs(), autoPromote, autoFix, maxRetries,
      provider: provider || undefined,
    }, {
      onSuccess: (result) => {
        setResults(prev => [result, ...prev]);
        if (result.status === 'VERIFIED') {
          toast.success(`Workflow "${result.workflowName}" verified!`);
        } else {
          toast.error(`Workflow failed: ${result.testError ?? 'Unknown error'}`);
        }
      },
      onError: () => toast.error('Factory pipeline failed'),
    });
  };

  const handleVerify = (workflowId: string) => {
    verifyMutation.mutate(workflowId, {
      onSuccess: (result) => {
        setResults(prev => {
          const idx = prev.findIndex(r => r.workflowId === workflowId);
          if (idx >= 0) { const next = [...prev]; next[idx] = result; return next; }
          return [result, ...prev];
        });
        if (result.status === 'VERIFIED') {
          toast.success('Workflow verified!');
        } else {
          toast.error(`Verification failed: ${result.testError ?? 'Unknown error'}`);
        }
      },
    });
  };

  const handlePromote = (workflowId: string) => {
    promoteMutation.mutate(workflowId, {
      onSuccess: (result) => {
        setResults(prev => {
          const idx = prev.findIndex(r => r.workflowId === workflowId);
          if (idx >= 0) { const next = [...prev]; next[idx] = result; return next; }
          return [result, ...prev];
        });
        toast.success('Workflow promoted as template!');
      },
    });
  };

  const handleExported = (workflowId: string) => {
    setResults(prev => prev.map(r =>
      r.workflowId === workflowId ? { ...r, status: 'EXPORTED' } : r
    ));
  };

  // Workflows that were factory-generated
  const factoryWorkflows = workflows?.filter(w =>
    (w.metadata as Record<string, unknown>)?.factoryGenerated === true
  ) ?? [];

  const categoryOptions = [
    { value: '', label: t.common.select },
    ...(topics?.map(t => ({ value: t.id, label: `${t.name} — ${t.description}` })) ?? []),
  ];

  const projectOptions = [
    { value: '', label: t.common.selectProject },
    ...(projects?.map(p => ({ value: p.id, label: p.name })) ?? []),
  ];

  return (
    <div className="space-y-6">
      {/* Header */}
      <div>
        <div className="flex items-center gap-3">
          <Factory className="w-7 h-7 text-accent" />
          <h1 className="text-2xl font-bold text-text-primary">{t.factory.title}</h1>
        </div>
        <p className="text-sm text-text-secondary mt-1">{t.factory.subtitle}</p>
      </div>

      {/* Creation Form */}
      <Card className="p-6">
        <div className="space-y-4">
          {/* Row 1: Topic */}
          <Input
            label={t.factory.topicLabel}
            placeholder={t.factory.topicPlaceholder}
            value={topic}
            onChange={e => setTopic(e.target.value)}
          />

          {/* Row 2: Description */}
          <div>
            <label className="block text-sm font-medium text-text-secondary mb-1.5">{t.factory.descriptionLabel}</label>
            <textarea
              className="input-base w-full"
              rows={3}
              placeholder={t.factory.descriptionPlaceholder}
              value={description}
              onChange={e => setDescription(e.target.value)}
            />
          </div>

          {/* Row 3: Project, Category, Provider */}
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <Select
              label={t.factory.projectLabel}
              options={projectOptions}
              value={projectId}
              onChange={e => setProjectId(e.target.value)}
            />
            <Select
              label={t.factory.categoryLabel}
              options={categoryOptions}
              value={category}
              onChange={e => setCategory(e.target.value)}
            />
            <Select
              label={t.factory.providerLabel}
              options={[
                { value: '', label: t.factory.providerDefault },
                { value: 'CLAUDE', label: 'Claude' },
                { value: 'CODEX', label: 'Codex' },
              ]}
              value={provider}
              onChange={e => setProvider(e.target.value)}
            />
          </div>
        </div>

        {/* Primary Action */}
        <div className="flex items-center gap-3 mt-5">
          <Button
            onClick={handleCreate}
            disabled={isRunning || !topic.trim() || !projectId}
            variant="primary"
          >
            {generateMutation.isPending ? <Spinner size="sm" /> : <Sparkles className="w-4 h-4" />}
            {generateMutation.isPending ? t.factory.generating : t.factory.createWorkflow}
          </Button>
        </div>

        {/* Advanced Options */}
        <div className="mt-4 pt-4 border-t border-border">
          <button
            className="flex items-center gap-1.5 text-sm text-text-secondary hover:text-text-primary transition-colors"
            onClick={() => setShowAdvanced(!showAdvanced)}
          >
            {showAdvanced ? <ChevronDown className="w-4 h-4" /> : <ChevronRight className="w-4 h-4" />}
            {t.factory.advancedOptions}
            {!isTestHealthy && (
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
                  value={testInputs}
                  onChange={e => setTestInputs(e.target.value)}
                />
              </div>

              <div className="flex flex-wrap items-center gap-6">
                <Toggle
                  label={t.factory.autoPromote}
                  enabled={autoPromote}
                  onChange={setAutoPromote}
                />
                <Toggle
                  label={t.factory.autoFix}
                  enabled={autoFix}
                  onChange={setAutoFix}
                />
                {autoFix && (
                  <div className="flex items-center gap-2">
                    <label className="text-sm text-text-secondary">{t.factory.maxRetries}</label>
                    <input
                      type="number"
                      min={1}
                      max={5}
                      value={maxRetries}
                      onChange={e => setMaxRetries(Number(e.target.value))}
                      className="input-base w-16 text-center"
                    />
                  </div>
                )}
              </div>

              <Button
                onClick={handleCreateAndTest}
                disabled={isRunning || !topic.trim() || !projectId || !isTestHealthy}
                variant="secondary"
              >
                {runMutation.isPending ? <Spinner size="sm" /> : <Play className="w-4 h-4" />}
                {runMutation.isPending ? t.factory.testing : t.factory.createAndTest}
              </Button>
            </div>
          )}
        </div>
      </Card>

      {/* Results */}
      <div>
        <h2 className="text-lg font-semibold text-text-primary mb-3">{t.factory.recentRuns}</h2>
        {results.length === 0 && factoryWorkflows.length === 0 ? (
          <Card className="p-8 text-center text-text-secondary">
            <Factory className="w-10 h-10 mx-auto mb-3 opacity-40" />
            <p>{t.factory.noRuns}</p>
          </Card>
        ) : (
          <div className="space-y-2">
            {results.map((r, i) => (
              <Card key={`result-${i}`} className="p-4 flex items-center gap-4">
                <StatusIcon status={r.status} />
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="font-medium text-text-primary truncate">
                      {r.workflowName ?? 'Unknown'}
                    </span>
                    <ResultStatusBadge status={r.status} />
                  </div>
                  {r.testError && (
                    <p className="text-xs text-red-400 mt-0.5 truncate">{r.testError}</p>
                  )}
                  {r.retryErrors.length > 0 && (
                    <p className="text-xs text-yellow-400 mt-0.5">
                      {r.retryErrors.length} retry error(s)
                    </p>
                  )}
                </div>
                {r.durationMs > 0 && (
                  <span className="text-xs text-text-secondary">{formatDuration(r.durationMs)}</span>
                )}
                <div className="flex gap-1">
                  {r.workflowId && (
                    <Button
                      size="sm" variant="ghost"
                      onClick={() => setExportModal({
                        workflowId: r.workflowId!,
                        name: r.workflowName ?? '',
                        description: null,
                      })}
                      title={t.factory.exportToPool}
                    >
                      <Package className="w-3.5 h-3.5" />
                    </Button>
                  )}
                  {r.workflowId && r.status !== 'VERIFIED' && r.status !== 'PROMOTED' && isTestHealthy && (
                    <Button
                      size="sm" variant="ghost"
                      onClick={() => handleVerify(r.workflowId!)}
                      disabled={verifyMutation.isPending}
                      title={t.factory.verifyExisting}
                    >
                      <RotateCcw className="w-3.5 h-3.5" />
                    </Button>
                  )}
                  {r.workflowId && r.status === 'VERIFIED' && (
                    <Button
                      size="sm" variant="ghost"
                      onClick={() => handlePromote(r.workflowId!)}
                      disabled={promoteMutation.isPending}
                      title={t.factory.promoteTemplate}
                    >
                      <Award className="w-3.5 h-3.5" />
                    </Button>
                  )}
                  {r.workflowId && (
                    <Button
                      size="sm" variant="ghost"
                      onClick={() => navigate(`/workflows/${r.workflowId}`)}
                      title="Open workflow"
                    >
                      <ArrowUpRight className="w-3.5 h-3.5" />
                    </Button>
                  )}
                </div>
              </Card>
            ))}

            {/* Also show factory-generated workflows not in current session results */}
            {factoryWorkflows
              .filter(fw => !results.some(r => r.workflowId === fw.id))
              .map(fw => (
                <Card key={fw.id} className="p-4 flex items-center gap-4">
                  <StatusIcon status={fw.testStatus === 'PASSED' ? 'VERIFIED' : fw.testStatus === 'FAILED' ? 'FAILED' : 'CREATED'} />
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="font-medium text-text-primary truncate">{fw.name}</span>
                      <Badge variant="default">{fw.projectName}</Badge>
                      {fw.testStatus && <ResultStatusBadge status={fw.testStatus === 'PASSED' ? 'VERIFIED' : fw.testStatus} />}
                    </div>
                  </div>
                  <div className="flex gap-1">
                    <Button
                      size="sm" variant="ghost"
                      onClick={() => setExportModal({
                        workflowId: fw.id,
                        name: fw.name,
                        description: fw.description,
                      })}
                      title={t.factory.exportToPool}
                    >
                      <Package className="w-3.5 h-3.5" />
                    </Button>
                    {isTestHealthy && (
                      <Button
                        size="sm" variant="ghost"
                        onClick={() => handleVerify(fw.id)}
                        disabled={verifyMutation.isPending}
                        title={t.factory.verifyExisting}
                      >
                        <RotateCcw className="w-3.5 h-3.5" />
                      </Button>
                    )}
                    <Button
                      size="sm" variant="ghost"
                      onClick={() => navigate(`/workflows/${fw.id}`)}
                      title="Open workflow"
                    >
                      <ArrowUpRight className="w-3.5 h-3.5" />
                    </Button>
                  </div>
                </Card>
              ))}
          </div>
        )}
      </div>

      {/* Export Modal */}
      {exportModal && (
        <ExportToPoolModal
          open={!!exportModal}
          onClose={() => setExportModal(null)}
          workflowId={exportModal.workflowId}
          workflowName={exportModal.name}
          workflowDescription={exportModal.description}
          onExported={() => handleExported(exportModal.workflowId)}
        />
      )}
    </div>
  );
}
