import { useCallback, useState } from 'react';
import { useCreateSchedule, useUpdateSchedule } from './useSchedules';
import type { ScheduleFormState } from '../components/schedule/ScheduleFormModal';
import type { CreateScheduleRequest, ScheduledWorkflow, UpdateScheduleRequest } from '../types';

const DELAY_PATTERN = /^\d+(s|m|h|d)$/;

const defaultForm: ScheduleFormState = {
  targetType: 'WORKFLOW',
  workflowId: '',
  chainId: '',
  cronExpression: '',
  scheduleType: 'CRON',
  delayExpression: '',
};

/**
 * Create/edit form state for the schedule modal: form fields, modal visibility,
 * variable editing, validation and submit (create vs. update) logic.
 */
export function useScheduleForm() {
  const createSchedule = useCreateSchedule();
  const updateSchedule = useUpdateSchedule();

  const [form, setForm] = useState<ScheduleFormState>(defaultForm);
  const [showCreate, setShowCreate] = useState(false);
  const [editingSchedule, setEditingSchedule] = useState<ScheduledWorkflow | null>(null);
  const [showVariables, setShowVariables] = useState(false);
  const [showEndConditions, setShowEndConditions] = useState(false);

  const openCreate = () => setShowCreate(true);

  const closeModal = () => {
    setShowCreate(false);
    setEditingSchedule(null);
    setForm(defaultForm);
    setShowVariables(false);
    setShowEndConditions(false);
  };

  const setVariable = useCallback((key: string, value: string, oldKey?: string) => {
    setForm((prev) => {
      const ctx = { ...(prev.inputContext ?? {}) } as Record<string, unknown>;
      if (oldKey && oldKey !== key) delete ctx[oldKey];
      ctx[key] = value;
      return { ...prev, inputContext: ctx };
    });
  }, []);

  const removeVariable = useCallback((key: string) => {
    setForm((prev) => {
      const ctx = { ...(prev.inputContext ?? {}) } as Record<string, unknown>;
      delete ctx[key];
      return { ...prev, inputContext: ctx };
    });
  }, []);

  const handleEdit = (schedule: ScheduledWorkflow) => {
    setEditingSchedule(schedule);
    setForm({
      targetType: schedule.targetType,
      workflowId: schedule.workflowId ?? '',
      chainId: schedule.chainId ?? '',
      cronExpression: schedule.cronExpression ?? '',
      description: schedule.description ?? '',
      inputContext: schedule.inputContext,
      scheduleType: schedule.scheduleType ?? 'CRON',
      scheduledAt: schedule.scheduledAt ?? undefined,
      endDate: schedule.endDate ?? null,
      maxRuns: schedule.maxRuns ?? undefined,
      conflictBehavior: schedule.conflictBehavior ?? 'SKIP',
      catchUpOnResume: schedule.catchUpOnResume ?? false,
    });
    const hasEndConditions = !!(schedule.endDate || (schedule.maxRuns && schedule.maxRuns > 0));
    setShowEndConditions(hasEndConditions);
    setShowCreate(true);
  };

  const handleSubmit = () => {
    if (editingSchedule) {
      const data: UpdateScheduleRequest = {};
      if (editingSchedule.scheduleType === 'CRON') {
        data.cronExpression = form.cronExpression;
      } else {
        data.scheduledAt = form.scheduledAt;
      }
      data.description = form.description;
      data.inputContext = form.inputContext;
      data.catchUpOnResume = form.catchUpOnResume ?? false;
      data.conflictBehavior = form.conflictBehavior ?? 'SKIP';
      if (editingSchedule.scheduleType === 'CRON' && showEndConditions) {
        data.endDate = form.endDate ?? null;
        data.maxRuns = form.maxRuns ?? null;
      }
      updateSchedule.mutate({ id: editingSchedule.id, data }, { onSuccess: closeModal });
    } else {
      const payload: CreateScheduleRequest = {
        targetType: form.targetType,
        cronExpression: form.scheduleType === 'CRON' ? form.cronExpression : undefined,
        description: form.description,
        inputContext: form.inputContext,
        scheduleType: form.scheduleType,
        scheduledAt: form.scheduleType === 'ONCE' ? form.scheduledAt : undefined,
        delayExpression: form.scheduleType === 'DELAYED' ? form.delayExpression : undefined,
        endDate: form.scheduleType === 'CRON' && showEndConditions ? (form.endDate ?? null) : null,
        maxRuns: form.scheduleType === 'CRON' && showEndConditions ? (form.maxRuns ?? null) : null,
        conflictBehavior: form.conflictBehavior ?? 'SKIP',
        catchUpOnResume: form.catchUpOnResume ?? false,
      };
      if (form.targetType === 'CHAIN') {
        payload.chainId = form.chainId;
      } else {
        payload.workflowId = form.workflowId;
      }
      createSchedule.mutate(payload, { onSuccess: closeModal });
    }
  };

  const isDelayValid = !form.delayExpression || DELAY_PATTERN.test(form.delayExpression.trim());

  const isFormValid = (
    form.targetType === 'CHAIN' ? !!form.chainId : !!form.workflowId
  ) && (
    form.scheduleType === 'ONCE' ? !!form.scheduledAt
    : form.scheduleType === 'DELAYED' ? (!!form.delayExpression && isDelayValid)
    : !!form.cronExpression
  );

  return {
    form, setForm,
    showCreate, openCreate, closeModal,
    editingSchedule,
    showVariables, setShowVariables,
    showEndConditions, setShowEndConditions,
    setVariable, removeVariable,
    handleEdit, handleSubmit,
    isFormValid,
    isSubmitting: editingSchedule ? updateSchedule.isPending : createSchedule.isPending,
  };
}
