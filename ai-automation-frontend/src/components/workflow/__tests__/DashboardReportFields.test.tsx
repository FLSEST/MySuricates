import { describe, it, expect, beforeEach, vi } from 'vitest';
import { useState } from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { DashboardReportFields } from '../DashboardReportFields';
import { useI18nStore } from '../../../i18n';
import { BLOCK_TYPES } from '../../../types';

function config(fields: Record<string, unknown>) {
  return JSON.stringify(fields, null, 2);
}

describe('DashboardReportFields', () => {
  beforeEach(() => {
    useI18nStore.setState({ locale: 'en' });
  });

  it('renders for dashboard-report only', () => {
    for (const type of BLOCK_TYPES) {
      const { unmount } = render(
        <DashboardReportFields blockType={type} configJson="{}" onConfigChange={() => {}} />,
      );
      const shown = screen.queryByTestId('dashboard-report-fields') !== null;
      expect(shown, `fields for ${type}`).toBe(type === 'dashboard-report');
      unmount();
    }
  });

  it('shows the stored values', () => {
    render(
      <DashboardReportFields
        blockType="dashboard-report"
        configJson={config({
          status: 'YELLOW', title: 'Nightly QA', summary: 'Zwei Befunde.',
          kpis: [{ label: 'Hunts', value: '1361', trend: 'UP' }],
          items: ['3 Hunts ohne Route'],
        })}
        onConfigChange={() => {}}
      />,
    );

    expect(screen.getByTestId('dashboard-report-title')).toHaveValue('Nightly QA');
    expect(screen.getByTestId('dashboard-report-summary')).toHaveValue('Zwei Befunde.');
    expect(screen.getByTestId('dashboard-report-kpi-label-0')).toHaveValue('Hunts');
    expect(screen.getByTestId('dashboard-report-kpi-value-0')).toHaveValue('1361');
    expect(screen.getByTestId('dashboard-report-item-0')).toHaveValue('3 Hunts ohne Route');
  });

  it('writes a changed field back into the config JSON', () => {
    const onConfigChange = vi.fn();
    render(
      <DashboardReportFields
        blockType="dashboard-report"
        configJson={config({ status: 'GREEN', timeout_seconds: 300 })}
        onConfigChange={onConfigChange}
      />,
    );

    fireEvent.change(screen.getByTestId('dashboard-report-title'), { target: { value: 'Nightly QA' } });

    expect(onConfigChange).toHaveBeenCalledTimes(1);
    expect(JSON.parse(onConfigChange.mock.calls[0][0] as string)).toEqual({
      status: 'GREEN', title: 'Nightly QA', timeout_seconds: 300,
    });
  });

  /**
   * The component is fully controlled: it renders the config it is given and hands the next config
   * back. The modals hold that state, so the test has to as well — otherwise a row added here would
   * never be rendered again.
   */
  it('adds, fills and removes a KPI row and an item row', () => {
    function Host({ onChange }: { onChange: (json: string) => void }) {
      const [json, setJson] = useState(config({ status: 'GREEN' }));
      return (
        <DashboardReportFields
          blockType="dashboard-report"
          configJson={json}
          onConfigChange={(next) => { setJson(next); onChange(next); }}
        />
      );
    }
    const onChange = vi.fn();
    render(<Host onChange={onChange} />);

    fireEvent.click(screen.getByTestId('dashboard-report-add-kpi'));
    expect(JSON.parse(onChange.mock.calls.at(-1)?.[0] as string).kpis)
      .toEqual([{ label: '', value: '' }]);

    fireEvent.change(screen.getByTestId('dashboard-report-kpi-label-0'), { target: { value: 'Hunts' } });
    fireEvent.change(screen.getByTestId('dashboard-report-kpi-value-0'), { target: { value: '1361' } });
    expect(JSON.parse(onChange.mock.calls.at(-1)?.[0] as string).kpis)
      .toEqual([{ label: 'Hunts', value: '1361' }]);

    fireEvent.click(screen.getByTestId('dashboard-report-add-item'));
    fireEvent.change(screen.getByTestId('dashboard-report-item-0'), { target: { value: 'finding' } });
    expect(JSON.parse(onChange.mock.calls.at(-1)?.[0] as string).items).toEqual(['finding']);

    fireEvent.click(screen.getByTestId('dashboard-report-remove-item-0'));
    expect(JSON.parse(onChange.mock.calls.at(-1)?.[0] as string)).not.toHaveProperty('items');

    fireEvent.click(screen.getByTestId('dashboard-report-remove-kpi-0'));
    expect(JSON.parse(onChange.mock.calls.at(-1)?.[0] as string)).not.toHaveProperty('kpis');
  });

  it('offers a free-text status for a placeholder', () => {
    const onConfigChange = vi.fn();
    render(
      <DashboardReportFields
        blockType="dashboard-report"
        configJson={config({ status: '{{ gate_status }}' })}
        onConfigChange={onConfigChange}
      />,
    );

    expect(screen.getByTestId('dashboard-report-status-custom')).toHaveValue('{{ gate_status }}');
  });

  it('warns about what the backend would refuse', () => {
    render(
      <DashboardReportFields
        blockType="dashboard-report"
        configJson={config({ status: 'BLUE', title: 't'.repeat(200) })}
        onConfigChange={() => {}}
      />,
    );

    const warnings = screen.getByTestId('dashboard-report-warnings');
    expect(warnings).toHaveTextContent('status');
    expect(warnings).toHaveTextContent('title');
  });

  /**
   * `Input`/`Select` do not link their label to the field, so every field of this form carries its
   * own aria-label — the status select was the one that did not, and a screen reader announced it
   * as an unnamed combo box.
   */
  it('gives every field an accessible name', () => {
    render(
      <DashboardReportFields
        blockType="dashboard-report"
        configJson={config({ status: 'GREEN', kpis: [{ label: 'Hunts', value: '1361' }], items: ['x'] })}
        onConfigChange={() => {}}
      />,
    );

    expect(screen.getByLabelText('Status')).toBe(screen.getByTestId('dashboard-report-status'));
    for (const field of ['dashboard-report-title', 'dashboard-report-summary',
      'dashboard-report-kpi-label-0', 'dashboard-report-kpi-value-0',
      'dashboard-report-kpi-trend-0', 'dashboard-report-item-0']) {
      expect(screen.getByTestId(field), field).toHaveAccessibleName();
    }
  });

  it('says so instead of guessing when the config is not valid JSON', () => {
    render(
      <DashboardReportFields blockType="dashboard-report" configJson="{oops" onConfigChange={() => {}} />,
    );

    expect(screen.getByTestId('dashboard-report-invalid-json')).toBeInTheDocument();
    expect(screen.queryByTestId('dashboard-report-title')).not.toBeInTheDocument();
  });
});
