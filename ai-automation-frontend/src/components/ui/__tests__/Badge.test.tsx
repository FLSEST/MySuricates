import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Badge } from '../Badge';

describe('Badge', () => {
  it('renders children text', () => {
    render(<Badge>Active</Badge>);
    expect(screen.getByText('Active')).toBeInTheDocument();
  });

  it('applies default variant styles', () => {
    render(<Badge>Default</Badge>);
    expect(screen.getByText('Default').className).toContain('bg-surface-raised');
  });

  it('applies success variant styles', () => {
    render(<Badge variant="success">Success</Badge>);
    expect(screen.getByText('Success').className).toContain('text-success');
  });

  it('applies warning variant styles', () => {
    render(<Badge variant="warning">Warning</Badge>);
    expect(screen.getByText('Warning').className).toContain('text-warning');
  });

  it('applies danger variant styles', () => {
    render(<Badge variant="danger">Error</Badge>);
    expect(screen.getByText('Error').className).toContain('text-danger');
  });

  it('applies accent variant styles', () => {
    render(<Badge variant="accent">Accent</Badge>);
    expect(screen.getByText('Accent').className).toContain('text-accent');
  });

  it('renders dot when dot prop is true', () => {
    const { container } = render(<Badge dot>With Dot</Badge>);
    const dot = container.querySelector('.rounded-full.w-1\\.5');
    expect(dot).toBeInTheDocument();
  });

  it('does not render dot by default', () => {
    const { container } = render(<Badge>No Dot</Badge>);
    const dot = container.querySelector('.w-1\\.5.h-1\\.5');
    expect(dot).not.toBeInTheDocument();
  });

  it('dot color matches variant', () => {
    const { container } = render(<Badge variant="success" dot>OK</Badge>);
    const dot = container.querySelector('.w-1\\.5');
    expect(dot?.className).toContain('bg-success');
  });
});
