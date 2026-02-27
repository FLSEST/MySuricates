import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Input } from '../Input';

describe('Input', () => {
  it('renders an input element', () => {
    render(<Input />);
    expect(screen.getByRole('textbox')).toBeInTheDocument();
  });

  it('renders label when provided', () => {
    render(<Input label="Email" />);
    expect(screen.getByText('Email')).toBeInTheDocument();
  });

  it('does not render label when not provided', () => {
    const { container } = render(<Input />);
    expect(container.querySelector('label')).not.toBeInTheDocument();
  });

  it('renders error message', () => {
    render(<Input error="Required field" />);
    expect(screen.getByText('Required field')).toBeInTheDocument();
  });

  it('renders hint when no error', () => {
    render(<Input hint="Enter your email" />);
    expect(screen.getByText('Enter your email')).toBeInTheDocument();
  });

  it('shows error instead of hint when both are provided', () => {
    render(<Input error="Bad input" hint="Help text" />);
    expect(screen.getByText('Bad input')).toBeInTheDocument();
    expect(screen.queryByText('Help text')).not.toBeInTheDocument();
  });

  it('applies error styling when error is present', () => {
    render(<Input error="Error" />);
    expect(screen.getByRole('textbox').className).toContain('border-danger');
  });

  it('accepts value and onChange', () => {
    const onChange = vi.fn();
    render(<Input value="test" onChange={onChange} />);
    expect(screen.getByRole('textbox')).toHaveValue('test');
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'new' } });
    expect(onChange).toHaveBeenCalled();
  });

  it('renders icon when provided', () => {
    render(<Input icon={<span data-testid="icon">🔍</span>} />);
    expect(screen.getByTestId('icon')).toBeInTheDocument();
  });

  it('applies placeholder', () => {
    render(<Input placeholder="Type here..." />);
    expect(screen.getByPlaceholderText('Type here...')).toBeInTheDocument();
  });
});
