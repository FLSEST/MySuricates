import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import {
  useUpdateWorkflow, useDeleteWorkflow, useExecuteWorkflow, useOptimizeWorkflow, useRepairWorkflow, useCreateWorkflow,
} from './useWorkflows';
import { useActivateSkills, useDeactivateSkill } from './useSkills';
import { useT } from '../i18n';
import { extractWorkflowVariables } from '../utils/extractWorkflowVariables';
import type { OptimizeResult, UpdateWorkflowRequest, Workflow } from '../types';

/**
 * Aktionen der Workflow-Detailseite mit ihren Dialogen: Bearbeiten (Name, Beschreibung, Icon),
 * Ausführen (mit Variablen erst über den Dialog, danach zur Laufseite), Klonen, Löschen,
 * Optimieren (Vorschlag prüfen, dann übernehmen), Reparieren, Anweisungen und Skills.
 * `workflow` darf noch fehlen (Laden); dann tun die Aktionen nichts.
 */
export function useWorkflowDetailActions(workflow: Workflow | undefined) {
  const navigate = useNavigate();
  const t = useT();
  const toast = useToast();
  const updateWorkflow = useUpdateWorkflow();
  const deleteWorkflow = useDeleteWorkflow();
  const executeWorkflow = useExecuteWorkflow();
  const optimizeWorkflow = useOptimizeWorkflow();
  const repairWorkflow = useRepairWorkflow();
  const createWorkflow = useCreateWorkflow();
  const activateSkills = useActivateSkills();
  const deactivateSkill = useDeactivateSkill();

  const [showEdit, setShowEdit] = useState(false);
  const [editForm, setEditForm] = useState({ name: '', description: '', icon: '' });
  const [showDelete, setShowDelete] = useState(false);
  const [showEditInstructions, setShowEditInstructions] = useState(false);
  const [editInstructions, setEditInstructions] = useState('');
  const [showOptimizeResult, setShowOptimizeResult] = useState(false);
  const [optimizeResult, setOptimizeResult] = useState<OptimizeResult | null>(null);
  const [showExecuteDialog, setShowExecuteDialog] = useState(false);
  const [showClone, setShowClone] = useState(false);
  const [cloneForm, setCloneForm] = useState({ projectId: '', name: '' });
  const [showAddSkill, setShowAddSkill] = useState(false);
  const [selectedSkillId, setSelectedSkillId] = useState('');

  const workflowVariables = workflow ? extractWorkflowVariables(workflow.blocks) : [];

  const openEdit = () => {
    if (!workflow) return;
    setEditForm({ name: workflow.name, description: workflow.description, icon: workflow.icon });
    setShowEdit(true);
  };

  const update = () => {
    if (!workflow) return;
    updateWorkflow.mutate(
      { id: workflow.id, data: editForm },
      { onSuccess: () => setShowEdit(false) }
    );
  };

  const remove = () => {
    if (!workflow) return;
    deleteWorkflow.mutate(workflow.id, {
      onSuccess: () => navigate('/workflows'),
    });
  };

  const execute = () => {
    if (!workflow) return;
    if (workflowVariables.length > 0) {
      setShowExecuteDialog(true);
    } else {
      executeWorkflow.mutate(
        { id: workflow.id },
        { onSuccess: (run) => navigate(`/workflows/${workflow.id}/run/${run.id}`) }
      );
    }
  };

  const executeWithContext = (context: Record<string, string>) => {
    if (!workflow) return;
    executeWorkflow.mutate(
      { id: workflow.id, context },
      {
        onSuccess: (run) => {
          setShowExecuteDialog(false);
          navigate(`/workflows/${workflow.id}/run/${run.id}`);
        },
      }
    );
  };

  const optimize = () => {
    if (!workflow) return;
    optimizeWorkflow.mutate(workflow.id, {
      onSuccess: (data) => {
        setOptimizeResult(data);
        setShowOptimizeResult(true);
      },
    });
  };

  const applyOptimize = () => {
    if (!workflow || !optimizeResult) return;
    const data: UpdateWorkflowRequest = {};
    if (optimizeResult.suggestedName) data.name = optimizeResult.suggestedName;
    if (optimizeResult.suggestedDescription) data.description = optimizeResult.suggestedDescription;
    if (optimizeResult.suggestedInstructions) data.instructions = optimizeResult.suggestedInstructions;
    if (optimizeResult.blocks && optimizeResult.blocks.length > 0) {
      data.blocks = optimizeResult.blocks;
    }
    updateWorkflow.mutate(
      { id: workflow.id, data },
      {
        onSuccess: () => setShowOptimizeResult(false),
        onError: (err) => toast.error(apiErrorMessage(err, t.toast.workflowUpdateFailed)),
      }
    );
  };

  const repair = () => {
    if (workflow) repairWorkflow.mutate(workflow.id);
  };

  const openClone = () => {
    if (!workflow) return;
    setCloneForm({ projectId: workflow.projectId, name: `${workflow.name} (Copy)` });
    setShowClone(true);
  };

  const clone = () => {
    if (!workflow) return;
    createWorkflow.mutate(
      {
        projectId: cloneForm.projectId,
        name: cloneForm.name,
        description: workflow.description,
        icon: workflow.icon,
        blocks: workflow.blocks,
        activatedSkillIds: workflow.activatedSkillIds,
        instructions: workflow.instructions ?? undefined,
        model: workflow.model ?? undefined,
      },
      {
        onSuccess: (newWorkflow) => {
          setShowClone(false);
          navigate(`/workflows/${newWorkflow.id}`);
        },
      }
    );
  };

  const openInstructions = () => {
    setEditInstructions(workflow?.instructions || '');
    setShowEditInstructions(true);
  };

  const saveInstructions = () => {
    if (!workflow) return;
    updateWorkflow.mutate(
      { id: workflow.id, data: { instructions: editInstructions } },
      { onSuccess: () => setShowEditInstructions(false) }
    );
  };

  const activateSkill = () => {
    if (!workflow || !selectedSkillId) return;
    activateSkills.mutate(
      { workflowId: workflow.id, skillIds: [...workflow.activatedSkillIds, selectedSkillId] },
      {
        onSuccess: () => {
          setShowAddSkill(false);
          setSelectedSkillId('');
        },
      }
    );
  };

  const deactivate = (workflowId: string, skillId: string) => deactivateSkill.mutate({ workflowId, skillId });

  return {
    workflowVariables,
    showEdit, setShowEdit, editForm, setEditForm, openEdit, update,
    showDelete, setShowDelete, remove, deleting: deleteWorkflow.isPending,
    showExecuteDialog, setShowExecuteDialog, execute, executeWithContext, executing: executeWorkflow.isPending,
    showOptimizeResult, setShowOptimizeResult, optimizeResult, optimize, applyOptimize,
    optimizing: optimizeWorkflow.isPending,
    repair, repairing: repairWorkflow.isPending,
    showClone, setShowClone, cloneForm, setCloneForm, openClone, clone, cloning: createWorkflow.isPending,
    showEditInstructions, setShowEditInstructions, editInstructions, setEditInstructions, openInstructions,
    saveInstructions,
    showAddSkill, setShowAddSkill, selectedSkillId, setSelectedSkillId, activateSkill,
    activating: activateSkills.isPending, deactivateSkill: deactivate,
    saving: updateWorkflow.isPending,
  };
}
