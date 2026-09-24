import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Button } from '../Button';

describe('Button', () => {
  it('renders children text', () => {
    render(<Button>Click me</Button>);
    expect(screen.getByRole('button')).toHaveTextContent('Click me');
  });

  it('calls onClick when clicked', () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>Click</Button>);
    fireEvent.click(screen.getByRole('button'));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('is disabled when loading is true', () => {
    render(<Button loading>Loading</Button>);
    expect(screen.getByRole('button')).toBeDisabled();
  });

  it('is disabled when disabled prop is true', () => {
    render(<Button disabled>Disabled</Button>);
    expect(screen.getByRole('button')).toBeDisabled();
  });

  // An explicit disabled={false} (e.g. disabled={!dirty}) overrode the loading state: Save stayed
  // clickable while saving and could send the PUT twice (660706aa).
  it('stays disabled while loading even when disabled is false', () => {
    const onClick = vi.fn();
    render(<Button loading disabled={false} onClick={onClick}>Save</Button>);
    expect(screen.getByRole('button')).toBeDisabled();
    fireEvent.click(screen.getByRole('button'));
    expect(onClick).not.toHaveBeenCalled();
  });

  it('is enabled again once loading ends and disabled is false', () => {
    const { rerender } = render(<Button loading disabled={false}>Save</Button>);
    rerender(<Button loading={false} disabled={false}>Save</Button>);
    expect(screen.getByRole('button')).toBeEnabled();
  });

  it('shows spinner when loading', () => {
    const { container } = render(<Button loading>Saving</Button>);
    expect(container.querySelector('.animate-spin')).toBeInTheDocument();
  });

  it('does not show spinner when not loading', () => {
    const { container } = render(<Button>Save</Button>);
    expect(container.querySelector('.animate-spin')).not.toBeInTheDocument();
  });

  it('renders icon when provided', () => {
    render(<Button icon={<span data-testid="icon">★</span>}>Star</Button>);
    expect(screen.getByTestId('icon')).toBeInTheDocument();
  });

  it('does not call onClick when disabled', () => {
    const onClick = vi.fn();
    render(<Button disabled onClick={onClick}>Click</Button>);
    fireEvent.click(screen.getByRole('button'));
    expect(onClick).not.toHaveBeenCalled();
  });

  it('applies primary variant class by default', () => {
    render(<Button>Primary</Button>);
    expect(screen.getByRole('button').className).toContain('bg-accent');
  });

  it('applies danger variant class', () => {
    render(<Button variant="danger">Delete</Button>);
    expect(screen.getByRole('button').className).toContain('bg-danger');
  });

  it('applies size classes', () => {
    render(<Button size="sm">Small</Button>);
    expect(screen.getByRole('button').className).toContain('text-xs');
  });

  it('applies custom className', () => {
    render(<Button className="my-custom">Custom</Button>);
    expect(screen.getByRole('button').className).toContain('my-custom');
  });
});
