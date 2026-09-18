import { lazy } from 'react';
import { describe, it, expect, vi, beforeAll } from 'vitest';
import { render, screen } from '@testing-library/react';
import { useI18nStore } from '../../../i18n';
import type { ResolvedWidget } from '../../../hooks/useDashboardLayout';
import type { DashboardWidgetDef } from '../widgetDefinition';
import { WidgetContent } from '../WidgetContent';

vi.mock('../../../utils/errorReporter', () => ({ reportErrorToBackend: vi.fn() }));

beforeAll(() => {
  useI18nStore.setState({ locale: 'en' });
  // React logs the caught chunk-load rejection; keep the test output readable.
  vi.spyOn(console, 'error').mockImplementation(() => {});
});

const def = (extra: Partial<DashboardWidgetDef> & Pick<DashboardWidgetDef, 'component'>): DashboardWidgetDef => ({
  id: 'broken', label: () => 'Broken chunk', description: () => '', category: 'overview', size: 'chart', ...extra,
});

/**
 * WidgetContent must keep an error boundary between the grid and the lazily loaded widget: a
 * chunk that fails to load ends in the widget's own error card instead of escaping into the grid.
 */
describe('WidgetContent', () => {
  it('shows the error card for a widget whose chunk fails to load, and stays isolated', async () => {
    const BrokenChunk = lazy(() => Promise.reject(new Error('chunk')));
    const widget: ResolvedWidget = {
      def: def({ component: BrokenChunk }),
      instance: { instanceId: 'broken-1', type: 'broken' },
    };

    render(
      <>
        <WidgetContent widget={widget} />
        <p>Neighbour</p>
      </>,
    );

    expect(await screen.findByRole('alert')).toHaveTextContent('Broken chunk');
    expect(screen.getByText('Neighbour')).toBeInTheDocument();
  });
});
