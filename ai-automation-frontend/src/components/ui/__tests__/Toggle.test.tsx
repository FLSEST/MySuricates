import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Toggle } from '../Toggle';

describe('Toggle', () => {
  it('renders a switch element', () => {
    render(<Toggle enabled={false} onChange={vi.fn()} />);
    expect(screen.getByRole('switch')).toBeInTheDocument();
  });

  it('has aria-checked false when disabled', () => {
    render(<Toggle enabled={false} onChange={vi.fn()} />);
    expect(screen.getByRole('switch')).toHaveAttribute('aria-checked', 'false');
  });

  it('has aria-checked true when enabled', () => {
    render(<Toggle enabled={true} onChange={vi.fn()} />);
    expect(screen.getByRole('switch')).toHaveAttribute('aria-checked', 'true');
  });

  it('calls onChange with true when clicked while off', () => {
    const onChange = vi.fn();
    render(<Toggle enabled={false} onChange={onChange} />);
    fireEvent.click(screen.getByRole('switch'));
    expect(onChange).toHaveBeenCalledWith(true);
  });

  it('calls onChange with false when clicked while on', () => {
    const onChange = vi.fn();
    render(<Toggle enabled={true} onChange={onChange} />);
    fireEvent.click(screen.getByRole('switch'));
    expect(onChange).toHaveBeenCalledWith(false);
  });

  it('renders label when provided', () => {
    render(<Toggle enabled={false} onChange={vi.fn()} label="Dark mode" />);
    expect(screen.getByText('Dark mode')).toBeInTheDocument();
  });

  it('does not render label when not provided', () => {
    const { container } = render(<Toggle enabled={false} onChange={vi.fn()} />);
    expect(container.querySelectorAll('span.text-sm')).toHaveLength(0);
  });

  it('is disabled when disabled prop is true', () => {
    render(<Toggle enabled={false} onChange={vi.fn()} disabled />);
    expect(screen.getByRole('switch')).toBeDisabled();
  });

  it('does not call onChange when disabled', () => {
    const onChange = vi.fn();
    render(<Toggle enabled={false} onChange={onChange} disabled />);
    fireEvent.click(screen.getByRole('switch'));
    expect(onChange).not.toHaveBeenCalled();
  });

  it('applies accent background when enabled', () => {
    render(<Toggle enabled={true} onChange={vi.fn()} />);
    expect(screen.getByRole('switch').className).toContain('bg-accent');
  });
});
