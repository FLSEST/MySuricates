import { useState } from 'react';
import { useFactoryGenerate, useFactoryRun } from './useWorkflowFactory';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type { WorkflowFactoryResult } from '../types';

/**
 * Form of the Workflow Factory and its two actions: "Create" generates the workflow only,
 * "Create & Test" runs the whole pipeline on the test instance (with auto-fix and auto-promote
 * if chosen). Topic and project are required, the test instance must be up for testing. Each
 * outcome goes to `onResult`; invalid JSON in the test inputs is reported and sent as `{}`.
 */
export function useFactoryForm({ testHealthy, onResult }: {
  testHealthy: boolean;
  onResult: (result: WorkflowFactoryResult) => void;
}) {
  const t = useT();
  const toast = useToast();
  const generateMutation = useFactoryGenerate();
  const runMutation = useFactoryRun();

  const [topic, setTopic] = useState('');
  const [description, setDescription] = useState('');
  const [category, setCategory] = useState('');
  const [projectId, setProjectId] = useState('');
  const [testInputs, setTestInputs] = useState('');
  const [provider, setProvider] = useState('');
  const [autoPromote, setAutoPromote] = useState(false);
  const [autoFix, setAutoFix] = useState(false);
  const [maxRetries, setMaxRetries] = useState(2);

  const parseTestInputs = (): Record<string, unknown> => {
    if (!testInputs.trim()) return {};
    try {
      return JSON.parse(testInputs);
    } catch {
      toast.error('Invalid JSON in test inputs');
      return {};
    }
  };

  const create = () => {
    if (!topic.trim()) { toast.error(t.factory.topicRequired); return; }
    if (!projectId) { toast.error(t.factory.projectRequired); return; }

    generateMutation.mutate({
      topic, description: description || undefined, category: category || undefined,
      projectId, testInputs: parseTestInputs(), provider: provider || undefined,
    }, {
      onSuccess: (workflow) => {
        toast.success(`Workflow "${workflow.name}" created`);
        onResult({
          workflowId: workflow.id, workflowName: workflow.name,
          status: 'CREATED', testRunId: null, testStatus: null, testError: null,
          promotedAsTemplate: false, durationMs: 0, retryErrors: [],
        });
      },
      onError: () => toast.error('Generation failed'),
    });
  };

  const createAndTest = () => {
    if (!topic.trim()) { toast.error(t.factory.topicRequired); return; }
    if (!projectId) { toast.error(t.factory.projectRequired); return; }
    if (!testHealthy) { toast.error(t.factory.testOffline); return; }

    runMutation.mutate({
      topic, description: description || undefined, category: category || undefined,
      projectId, testInputs: parseTestInputs(), autoPromote, autoFix, maxRetries,
      provider: provider || undefined,
    }, {
      onSuccess: (result) => {
        onResult(result);
        if (result.status === 'VERIFIED') {
          toast.success(`Workflow "${result.workflowName}" verified!`);
        } else {
          toast.error(`Workflow failed: ${result.testError ?? 'Unknown error'}`);
        }
      },
      onError: () => toast.error('Factory pipeline failed'),
    });
  };

  return {
    topic, setTopic,
    description, setDescription,
    category, setCategory,
    projectId, setProjectId,
    testInputs, setTestInputs,
    provider, setProvider,
    autoPromote, setAutoPromote,
    autoFix, setAutoFix,
    maxRetries, setMaxRetries,
    testHealthy,
    create,
    createAndTest,
    isGenerating: generateMutation.isPending,
    isTesting: runMutation.isPending,
    isRunning: generateMutation.isPending || runMutation.isPending,
  };
}

export type FactoryForm = ReturnType<typeof useFactoryForm>;
