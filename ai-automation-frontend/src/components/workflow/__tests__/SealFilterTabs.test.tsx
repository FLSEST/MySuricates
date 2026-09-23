import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { en } from '../../../i18n/en';
import { SealFilterTabs } from '../SealFilterTabs';
import { SealStateIcon } from '../SealStateIcon';

describe('SealFilterTabs', () => {
  it('offers sealed and not sealed with counts, also when nothing is sealed', () => {
    const onChange = vi.fn();
    render(<SealFilterTabs t={en} value="all" onChange={onChange} sealedCount={0} unsealedCount={12} />);

    expect(screen.getByTestId('filter-seal-sealed')).toHaveTextContent(`${en.workflows.sealed}0`);
    expect(screen.getByTestId('filter-seal-unsealed')).toHaveTextContent(`${en.workflows.sealView.notSealed}12`);
    expect(screen.getByTestId('filter-seal-all')).toHaveAttribute('aria-pressed', 'true');

    fireEvent.click(screen.getByTestId('filter-seal-unsealed'));
    expect(onChange).toHaveBeenCalledWith('unsealed');
  });
});

describe('SealStateIcon', () => {
  it('shows an open lock for an unsealed workflow instead of nothing', () => {
    render(<SealStateIcon sealed={false} />);
    expect(screen.getByTestId('seal-unsealed')).toBeInTheDocument();
  });

  it('shows the lock for a sealed workflow', () => {
    render(<SealStateIcon sealed />);
    expect(screen.getByTestId('seal-intact')).toBeInTheDocument();
  });
});

describe('SealStateIcon for runs', () => {
  it('marks a run without recorded seal state instead of hiding it', () => {
    render(<SealStateIcon sealed={null} unrecorded />);
    expect(screen.getByTestId('seal-unrecorded')).toHaveAttribute('title', en.workflows.sealView.unrecordedHint);
  });
});
