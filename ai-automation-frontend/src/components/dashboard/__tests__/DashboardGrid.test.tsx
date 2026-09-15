import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { useI18nStore } from '../../../i18n';
import type { ResolvedWidget } from '../../../hooks/useDashboardLayout';
import { DashboardGrid } from '../DashboardGrid';

vi.mock('../../../utils/errorReporter', () => ({ reportErrorToBackend: vi.fn() }));

function Boom(): never {
  throw new Error('boom');
}
function Fine() {
  return <p>Fine widget</p>;
}

const widgets: ResolvedWidget[] = [
  { def: { id: 'boom', label: () => 'Broken widget', size: 'chart', component: Boom }, instance: { instanceId: 'boom', type: 'boom' } },
  { def: { id: 'fine', label: () => 'Fine', size: 'chart', component: Fine }, instance: { instanceId: 'fine', type: 'fine' } },
];

beforeAll(() => {
  vi.spyOn(console, 'error').mockImplementation(() => {});
});
beforeEach(() => {
  useI18nStore.setState({ locale: 'en' });
});

describe('DashboardGrid', () => {
  it.each([false, true])('isolates a crashing widget (editMode=%s)', (editMode) => {
    render(<DashboardGrid widgets={widgets} editMode={editMode} onMove={vi.fn()} onHide={vi.fn()} />);
    expect(screen.getByRole('alert')).toHaveTextContent('Broken widget');
    expect(screen.getByText('Fine widget')).toBeInTheDocument();
  });
});
