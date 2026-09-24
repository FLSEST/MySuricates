import { LayoutDashboard, Plus, Trash2 } from 'lucide-react';
import { Input, Select } from '../ui';
import { useT } from '../../i18n';
import {
  configJsonWithReport, formFromConfig, hasPlaceholder,
  reportWarnings, REPORT_STATUSES, type ReportForm, type ReportKpiForm, type ReportTrend,
} from './dashboardReport';
import { DashboardReportAssist } from './DashboardReportAssist';
import { WORKFLOW_RESULT_CAPS } from '../dashboard/workflowWidgetContract';
import type { BlockType } from '../../types';

interface DashboardReportFieldsProps {
  blockType: BlockType;
  /** The block config as edited in the JSON editor — this form owns its five contract keys. */
  configJson: string;
  onConfigChange: (configJson: string) => void;
  /** Keeps element ids unique when both the add and the edit modal are mounted. */
  idSuffix?: string;
  /** Only a saved workflow can be the source of a proposal. */
  workflowId?: string;
}

const CUSTOM_STATUS = '__custom__';

/**
 * The dashboard-report block's config as a form: the five contract fields, each of them
 * template-capable. Every value here ends up in the run's dashboard result, so the same rule as on
 * the dashboard applies — text nodes only, nothing rendered as markup, no link built from a value.
 *
 * Warnings are a courtesy, not a gate: the backend refuses whatever it refuses, and a value holding
 * a placeholder cannot be judged before the run.
 */
export function DashboardReportFields({
  blockType, configJson, onConfigChange, idSuffix = '', workflowId,
}: DashboardReportFieldsProps) {
  const t = useT().workflows.dashboardReport;
  if (blockType !== 'dashboard-report') return null;

  const form = formFromConfig(configJson);

  const write = (next: ReportForm) => onConfigChange(configJsonWithReport(configJson, next));
  const patch = (partial: Partial<ReportForm>) => {
    if (form) write({ ...form, ...partial });
  };
  const patchKpi = (index: number, partial: Partial<ReportKpiForm>) => {
    if (!form) return;
    write({ ...form, kpis: form.kpis.map((kpi, i) => (i === index ? { ...kpi, ...partial } : kpi)) });
  };

  const status = form?.status ?? '';
  const isKnownStatus = REPORT_STATUSES.some((known) => known === status);
  const warnings = form ? reportWarnings(form) : [];

  return (
    <div data-testid="dashboard-report-fields" className="rounded-lg border border-border p-3 space-y-3">
      <div>
        <p className="text-sm font-medium text-text flex items-center gap-1.5">
          <LayoutDashboard className="w-3.5 h-3.5 text-accent" />
          {t.heading}
        </p>
        <p className="text-xs text-text-tertiary mt-0.5">{t.hint}</p>
      </div>

      {workflowId && form && (
        <DashboardReportAssist workflowId={workflowId} onApply={(proposed) => write(proposed)} />
      )}

      {!form ? (
        <p data-testid="dashboard-report-invalid-json" className="text-xs text-warning">{t.invalidJson}</p>
      ) : (
        <>
          <div className="grid gap-3 sm:grid-cols-2">
            <Select
              data-testid="dashboard-report-status"
              aria-label={t.status}
              label={t.status}
              value={isKnownStatus ? status : CUSTOM_STATUS}
              onChange={(e) => patch({ status: e.target.value === CUSTOM_STATUS ? '' : e.target.value })}
              options={[
                ...REPORT_STATUSES.map((value) => ({ value, label: t.statuses[value] })),
                { value: CUSTOM_STATUS, label: t.statusCustom },
              ]}
            />
            {!isKnownStatus && (
              <Input
                data-testid="dashboard-report-status-custom"
                aria-label={t.statusCustom}
                label={t.statusCustom}
                value={status}
                onChange={(e) => patch({ status: e.target.value })}
                placeholder="{{ block.<name>.output }}"
                hint={t.statusCustomHint}
              />
            )}
          </div>

          <Input
            data-testid="dashboard-report-title"
            aria-label={t.title}
            label={t.title}
            value={form.title}
            onChange={(e) => patch({ title: e.target.value })}
            placeholder={t.titlePlaceholder}
            hint={t.optionalMax.replace('{n}', () => String(WORKFLOW_RESULT_CAPS.title))}
          />

          <Input
            data-testid="dashboard-report-summary"
            aria-label={t.summary}
            label={t.summary}
            value={form.summary}
            onChange={(e) => patch({ summary: e.target.value })}
            placeholder={t.summaryPlaceholder}
            hint={t.optionalMax.replace('{n}', () => String(WORKFLOW_RESULT_CAPS.summary))}
          />

          <div className="space-y-2">
            <p className="text-sm font-medium text-text-secondary">
              {t.kpis} ({form.kpis.length}/{WORKFLOW_RESULT_CAPS.kpis})
            </p>
            {form.kpis.map((kpi, index) => (
              <div key={`kpi-${idSuffix}-${index}`} className="flex flex-wrap items-end gap-2">
                <div className="min-w-[8rem] flex-1">
                  <Input
                    data-testid={`dashboard-report-kpi-label-${index}`}
                    aria-label={`${t.kpiLabel} ${index + 1}`}
                    value={kpi.label}
                    onChange={(e) => patchKpi(index, { label: e.target.value })}
                    placeholder={t.kpiLabel}
                  />
                </div>
                <div className="min-w-[8rem] flex-1">
                  <Input
                    data-testid={`dashboard-report-kpi-value-${index}`}
                    aria-label={`${t.kpiValue} ${index + 1}`}
                    value={kpi.value}
                    onChange={(e) => patchKpi(index, { value: e.target.value })}
                    placeholder={t.kpiValue}
                  />
                </div>
                <div className="min-w-[7rem]">
                  <Select
                    data-testid={`dashboard-report-kpi-trend-${index}`}
                    aria-label={`${t.kpiTrend} ${index + 1}`}
                    value={kpi.trend}
                    onChange={(e) => patchKpi(index, { trend: e.target.value as ReportTrend })}
                    options={[
                      { value: '', label: t.kpiTrendNone },
                      { value: 'UP', label: t.trends.UP },
                      { value: 'DOWN', label: t.trends.DOWN },
                      { value: 'FLAT', label: t.trends.FLAT },
                    ]}
                  />
                </div>
                <button
                  type="button"
                  data-testid={`dashboard-report-remove-kpi-${index}`}
                  aria-label={`${t.removeKpi} ${index + 1}`}
                  className="mb-1 rounded-md p-1.5 text-text-tertiary hover:text-danger"
                  onClick={() => patch({ kpis: form.kpis.filter((_, i) => i !== index) })}
                >
                  <Trash2 className="h-4 w-4" />
                </button>
              </div>
            ))}
            <button
              type="button"
              data-testid="dashboard-report-add-kpi"
              className="inline-flex items-center gap-1 rounded-md border border-border px-2 py-1 text-xs text-text-secondary hover:border-accent"
              onClick={() => patch({ kpis: [...form.kpis, { label: '', value: '', trend: '' }] })}
            >
              <Plus className="h-3 w-3" />
              {t.addKpi}
            </button>
          </div>

          <div className="space-y-2">
            <p className="text-sm font-medium text-text-secondary">
              {t.items} ({form.items.length}/{WORKFLOW_RESULT_CAPS.items})
            </p>
            {form.items.map((item, index) => (
              <div key={`item-${idSuffix}-${index}`} className="flex items-end gap-2">
                <div className="flex-1">
                  <Input
                    data-testid={`dashboard-report-item-${index}`}
                    aria-label={`${t.item} ${index + 1}`}
                    value={item}
                    onChange={(e) => patch({
                      items: form.items.map((current, i) => (i === index ? e.target.value : current)),
                    })}
                    placeholder={t.itemPlaceholder}
                  />
                </div>
                <button
                  type="button"
                  data-testid={`dashboard-report-remove-item-${index}`}
                  aria-label={`${t.removeItem} ${index + 1}`}
                  className="mb-1 rounded-md p-1.5 text-text-tertiary hover:text-danger"
                  onClick={() => patch({ items: form.items.filter((_, i) => i !== index) })}
                >
                  <Trash2 className="h-4 w-4" />
                </button>
              </div>
            ))}
            <button
              type="button"
              data-testid="dashboard-report-add-item"
              className="inline-flex items-center gap-1 rounded-md border border-border px-2 py-1 text-xs text-text-secondary hover:border-accent"
              onClick={() => patch({ items: [...form.items, ''] })}
            >
              <Plus className="h-3 w-3" />
              {t.addItem}
            </button>
          </div>

          {warnings.length > 0 && (
            <ul data-testid="dashboard-report-warnings" className="space-y-0.5 text-xs text-warning">
              {warnings.map((warning) => (
                <li key={`${warning.field}-${warning.rule}`}>
                  {warning.field}: {t.warnings[warning.rule]}
                </li>
              ))}
            </ul>
          )}
          {form.status !== '' && hasPlaceholder(form.status) && (
            <p className="text-xs text-text-tertiary">{t.placeholderNote}</p>
          )}
        </>
      )}
    </div>
  );
}
