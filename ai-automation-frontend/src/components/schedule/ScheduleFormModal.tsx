import { Plus, X, ChevronDown } from 'lucide-react';
import { Modal, Button, Input, Select, CronPicker } from '../ui';
import { useT } from '../../i18n';
import { CatchUpOnResumeField } from './CatchUpOnResumeField';
import type { ScheduledWorkflow, CreateScheduleRequest, Workflow, WorkflowChain } from '../../types';

type TargetType = 'WORKFLOW' | 'CHAIN';

const DELAY_PATTERN = /^\d+(s|m|h|d)$/;

function resolveDelayExpression(expr: string): Date | null {
  const match = expr.trim().match(/^(\d+)(s|m|h|d)$/);
  if (!match) return null;
  const value = parseInt(match[1]!, 10);
  const unit = match[2]!;
  const now = Date.now();
  const ms = unit === 's' ? value * 1000
    : unit === 'm' ? value * 60_000
    : unit === 'h' ? value * 3_600_000
    : value * 86_400_000;
  return new Date(now + ms);
}

export interface ScheduleFormState extends CreateScheduleRequest {
  targetType: TargetType;
}

interface ScheduleFormModalProps {
  open: boolean;
  editingSchedule: ScheduledWorkflow | null;
  form: ScheduleFormState;
  showEndConditions: boolean;
  showVariables: boolean;
  workflows?: Workflow[];
  chains?: WorkflowChain[];
  /** False when the backend runs without the chains module: only workflows can be scheduled. */
  chainsEnabled?: boolean;
  /** Global setting "catch up missed runs"; undefined while unknown. */
  globalCatchUpOnResume?: boolean;
  isLoading: boolean;
  isFormValid: boolean;
  onClose: () => void;
  onFormChange: (form: ScheduleFormState) => void;
  onShowEndConditionsChange: (show: boolean) => void;
  onShowVariablesChange: (show: boolean) => void;
  onVariableSet: (key: string, value: string, oldKey?: string) => void;
  onVariableRemove: (key: string) => void;
  onSubmit: () => void;
}

export function ScheduleFormModal({
  open,
  editingSchedule,
  form,
  showEndConditions,
  showVariables,
  workflows,
  chains,
  chainsEnabled = true,
  globalCatchUpOnResume,
  isLoading,
  isFormValid,
  onClose,
  onFormChange,
  onShowEndConditionsChange,
  onShowVariablesChange,
  onVariableSet,
  onVariableRemove,
  onSubmit,
}: ScheduleFormModalProps) {
  const t = useT();

  const isDelayValid = !form.delayExpression || DELAY_PATTERN.test(form.delayExpression.trim());

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={editingSchedule ? t.schedules.edit : t.schedules.create}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            {t.common.cancel}
          </Button>
          <Button
            onClick={onSubmit}
            loading={isLoading}
            disabled={!isFormValid}
          >
            {editingSchedule ? t.common.save : t.common.create}
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        {/* Target Type Toggle */}
        <div>
          <label className="block text-sm font-medium text-text mb-1.5">{t.schedules.targetType}</label>
          {editingSchedule ? (
            <div className="px-3 py-2 rounded-lg border border-border bg-surface-secondary text-text text-sm">
              {form.targetType === 'CHAIN'
                ? (editingSchedule.chainName ?? t.schedules.targetChain)
                : (editingSchedule.workflowName ?? t.schedules.targetWorkflow)}
            </div>
          ) : (
            <div className="flex items-center bg-surface-secondary rounded-lg p-0.5 border border-border">
              <button
                type="button"
                onClick={() => onFormChange({ ...form, targetType: 'WORKFLOW', chainId: '' })}
                className={`flex-1 px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
                  form.targetType === 'WORKFLOW'
                    ? 'bg-surface text-text shadow-xs'
                    : 'text-text-secondary hover:text-text'
                }`}
              >
                {t.schedules.targetWorkflow}
              </button>
              {chainsEnabled && (
                <button
                  type="button"
                  onClick={() => onFormChange({ ...form, targetType: 'CHAIN', workflowId: '' })}
                  className={`flex-1 px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
                    form.targetType === 'CHAIN'
                      ? 'bg-surface text-text shadow-xs'
                      : 'text-text-secondary hover:text-text'
                  }`}
                >
                  {t.schedules.targetChain}
                </button>
              )}
            </div>
          )}
        </div>

        {/* Workflow or Chain selector (only in create mode) */}
        {!editingSchedule && (
          form.targetType === 'CHAIN' ? (
            <Select
              label={t.schedules.targetChain}
              value={form.chainId ?? ''}
              onChange={(e) => onFormChange({ ...form, chainId: e.target.value })}
              options={[
                { value: '', label: t.schedules.selectChain },
                ...(chains?.map((c) => ({
                  value: c.id,
                  label: c.name,
                })) ?? []),
              ]}
            />
          ) : (
            <Select
              label={t.common.workflow}
              value={form.workflowId ?? ''}
              onChange={(e) => onFormChange({ ...form, workflowId: e.target.value })}
              options={[
                { value: '', label: t.common.selectWorkflow },
                ...(workflows?.map((w) => ({
                  value: w.id,
                  label: w.projectName ? `${w.name} (${w.projectName})` : w.name,
                })) ?? []),
              ]}
            />
          )
        )}

        {/* Schedule Type Toggle */}
        <div>
          <label className="block text-sm font-medium text-text mb-1.5">{t.schedules.scheduleType}</label>
          {editingSchedule ? (
            <div className="px-3 py-2 rounded-lg border border-border bg-surface-secondary text-text text-sm">
              {form.scheduleType === 'ONCE' ? t.schedules.once
                : form.scheduleType === 'DELAYED' ? t.schedules.delayed
                : t.schedules.recurring}
            </div>
          ) : (
            <div className="flex items-center bg-surface-secondary rounded-lg p-0.5 border border-border">
              <button
                type="button"
                onClick={() => onFormChange({ ...form, scheduleType: 'CRON', scheduledAt: undefined, delayExpression: '' })}
                className={`flex-1 px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
                  form.scheduleType === 'CRON'
                    ? 'bg-surface text-text shadow-xs'
                    : 'text-text-secondary hover:text-text'
                }`}
              >
                {t.schedules.recurring}
              </button>
              <button
                type="button"
                onClick={() => onFormChange({ ...form, scheduleType: 'ONCE', cronExpression: '', delayExpression: '' })}
                className={`flex-1 px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
                  form.scheduleType === 'ONCE'
                    ? 'bg-surface text-text shadow-xs'
                    : 'text-text-secondary hover:text-text'
                }`}
              >
                {t.schedules.once}
              </button>
              <button
                type="button"
                onClick={() => onFormChange({ ...form, scheduleType: 'DELAYED', cronExpression: '', scheduledAt: undefined })}
                className={`flex-1 px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
                  form.scheduleType === 'DELAYED'
                    ? 'bg-surface text-text shadow-xs'
                    : 'text-text-secondary hover:text-text'
                }`}
              >
                {t.schedules.delayed}
              </button>
            </div>
          )}
        </div>

        {/* Cron, DateTime or Delay picker */}
        {form.scheduleType === 'ONCE' ? (
          <div>
            <label className="block text-sm font-medium text-text mb-1.5">{t.schedules.scheduledAt}</label>
            <input
              type="datetime-local"
              value={form.scheduledAt ? form.scheduledAt.slice(0, 16) : ''}
              onChange={(e) => {
                const val = e.target.value;
                onFormChange({ ...form, scheduledAt: val ? new Date(val).toISOString() : undefined });
              }}
              className="w-full px-3 py-2 rounded-lg border border-border bg-surface text-text text-sm focus:outline-hidden focus:ring-2 focus:ring-accent/50"
            />
          </div>
        ) : form.scheduleType === 'DELAYED' ? (
          <div>
            <Input
              label={t.schedules.delayExpression}
              value={form.delayExpression ?? ''}
              onChange={(e) => onFormChange({ ...form, delayExpression: e.target.value })}
              placeholder={t.schedules.delayExpressionPlaceholder}
            />
            {form.delayExpression && !isDelayValid && (
              <p className="mt-1 text-xs text-danger">{t.schedules.delayExpressionInvalid}</p>
            )}
            {form.delayExpression && isDelayValid && (() => {
              const resolved = resolveDelayExpression(form.delayExpression);
              if (!resolved) return null;
              const timeStr = resolved.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
              return (
                <p className="mt-1 text-xs text-text-secondary">
                  {t.schedules.delayExpressionResolved
                    .replace('{delay}', form.delayExpression)
                    .replace('{time}', timeStr)}
                </p>
              );
            })()}
          </div>
        ) : (
          <>
            <CronPicker
              label={t.schedules.cronExpression}
              value={form.cronExpression ?? ''}
              onChange={(cron) => onFormChange({ ...form, cronExpression: cron })}
            />
            {/* End conditions — only for CRON */}
            <div className="border border-border rounded-lg overflow-hidden">
              <label className="flex items-center gap-3 px-3 py-2.5 cursor-pointer hover:bg-surface-secondary transition-colors">
                <input
                  type="checkbox"
                  checked={showEndConditions}
                  onChange={(e) => {
                    onShowEndConditionsChange(e.target.checked);
                    if (!e.target.checked) {
                      onFormChange({ ...form, endDate: null, maxRuns: undefined });
                    }
                  }}
                  className="w-4 h-4 accent-accent"
                />
                <span className="text-sm font-medium text-text">{t.schedules.limitExecutions}</span>
              </label>
              {showEndConditions && (
                <div className="px-3 pb-3 space-y-3 border-t border-border pt-3">
                  <div>
                    <label className="block text-xs font-medium text-text-secondary mb-1">{t.schedules.endDate}</label>
                    <input
                      type="datetime-local"
                      value={form.endDate ? new Date(form.endDate).toISOString().slice(0, 16) : ''}
                      onChange={(e) => onFormChange({ ...form, endDate: e.target.value ? new Date(e.target.value).toISOString() : null })}
                      className="w-full px-3 py-2 text-sm bg-surface border border-border rounded-lg text-text focus:outline-none focus:ring-1 focus:ring-accent"
                    />
                    <p className="mt-1 text-xs text-text-tertiary">{t.schedules.endDateHint}</p>
                  </div>
                  <div>
                    <label className="block text-xs font-medium text-text-secondary mb-1">{t.schedules.maxRuns}</label>
                    <Input
                      type="number"
                      min={1}
                      placeholder={t.schedules.maxRunsPlaceholder}
                      value={form.maxRuns != null ? String(form.maxRuns) : ''}
                      onChange={(e) => onFormChange({ ...form, maxRuns: e.target.value ? parseInt(e.target.value) : undefined })}
                    />
                    <p className="mt-1 text-xs text-text-tertiary">{t.schedules.maxRunsHint}</p>
                  </div>
                </div>
              )}
            </div>
          </>
        )}

        <Input
          label={t.common.description}
          value={form.description ?? ''}
          onChange={(e) => onFormChange({ ...form, description: e.target.value })}
          placeholder="Daily code quality check"
        />

        <Select
          label={t.schedules.conflictBehavior}
          value={form.conflictBehavior ?? 'SKIP'}
          onChange={(e) => onFormChange({ ...form, conflictBehavior: e.target.value as 'SKIP' | 'OVERLAP' })}
          options={[
            { value: 'SKIP', label: t.schedules.conflictBehaviorSkip },
            { value: 'OVERLAP', label: t.schedules.conflictBehaviorOverlap },
          ]}
        />

        <CatchUpOnResumeField
          checked={form.catchUpOnResume ?? false}
          onChange={(checked) => onFormChange({ ...form, catchUpOnResume: checked })}
          globalCatchUp={globalCatchUpOnResume}
        />

        {/* Variables accordion */}
        <div className="border border-border rounded-lg overflow-hidden">
          <button
            type="button"
            onClick={() => onShowVariablesChange(!showVariables)}
            className="w-full flex items-center justify-between px-3 py-2.5 text-sm font-medium text-text hover:bg-surface-secondary transition-colors"
          >
            <span className="flex items-center gap-2">
              {t.schedules.variables}
              {Object.keys(form.inputContext ?? {}).length > 0 && (
                <span className="px-1.5 py-0.5 rounded-full bg-accent/10 text-accent text-xs font-medium">
                  {Object.keys(form.inputContext ?? {}).length}
                </span>
              )}
            </span>
            <ChevronDown className={`w-4 h-4 text-text-secondary transition-transform ${showVariables ? 'rotate-180' : ''}`} />
          </button>
          {showVariables && (
            <div className="px-3 pb-3 space-y-2 border-t border-border">
              {Object.entries(form.inputContext ?? {}).map(([k, v]) => (
                <div key={k} className="flex items-center gap-2 pt-2">
                  <Input
                    placeholder={t.schedules.variableKey}
                    value={k}
                    onChange={(e) => onVariableSet(e.target.value, String(v), k)}
                    className="flex-1"
                  />
                  <Input
                    placeholder={t.schedules.variableValue}
                    value={String(v)}
                    onChange={(e) => onVariableSet(k, e.target.value)}
                    className="flex-1"
                  />
                  <button
                    type="button"
                    onClick={() => onVariableRemove(k)}
                    className="p-1.5 text-text-tertiary hover:text-danger transition-colors shrink-0"
                  >
                    <X className="w-4 h-4" />
                  </button>
                </div>
              ))}
              <button
                type="button"
                onClick={() => onVariableSet(`var${Object.keys(form.inputContext ?? {}).length + 1}`, '')}
                className="mt-2 flex items-center gap-1.5 text-xs text-accent hover:text-accent/80 transition-colors"
              >
                <Plus className="w-3.5 h-3.5" />
                {t.schedules.addVariable}
              </button>
            </div>
          )}
        </div>
      </div>
    </Modal>
  );
}
