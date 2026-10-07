import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ChainStatusBadge } from '../ChainStatusBadge';

describe('ChainStatusBadge', () => {
  it('uses the same labels and shows a chain with errors as a warning', () => {
    render(<><ChainStatusBadge status="COMPLETED" /><ChainStatusBadge status="COMPLETED_WITH_ERRORS" /></>);

    expect(screen.getByText('Completed')).toHaveClass('bg-success-subtle');
    expect(screen.getByText('Completed with errors')).toHaveClass('bg-warning-subtle');
  });
});
