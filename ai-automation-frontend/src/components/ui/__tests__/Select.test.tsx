import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Select } from '../Select';

const options = [
  { value: 'a', label: 'Alpha' },
  { value: 'b', label: 'Beta' },
  { value: 'c', label: 'Gamma' },
];

describe('Select', () => {
  it('renders a select element', () => {
    render(<Select options={options} />);
    expect(screen.getByRole('combobox')).toBeInTheDocument();
  });

  it('renders all options', () => {
    render(<Select options={options} />);
    const opts = screen.getAllByRole('option');
    expect(opts).toHaveLength(3);
    expect(opts[0]).toHaveTextContent('Alpha');
    expect(opts[1]).toHaveTextContent('Beta');
    expect(opts[2]).toHaveTextContent('Gamma');
  });

  it('renders label when provided', () => {
    render(<Select options={options} label="Pick one" />);
    expect(screen.getByText('Pick one')).toBeInTheDocument();
  });

  it('does not render label when not provided', () => {
    const { container } = render(<Select options={options} />);
    expect(container.querySelector('label')).not.toBeInTheDocument();
  });

  it('calls onChange when selection changes', () => {
    const onChange = vi.fn();
    render(<Select options={options} onChange={onChange} />);
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'b' } });
    expect(onChange).toHaveBeenCalled();
  });

  it('renders error message', () => {
    render(<Select options={options} error="Selection required" />);
    expect(screen.getByText('Selection required')).toBeInTheDocument();
  });

  it('applies error styling', () => {
    render(<Select options={options} error="Oops" />);
    expect(screen.getByRole('combobox').className).toContain('border-danger');
  });

  it('sets correct option values', () => {
    render(<Select options={options} />);
    const opts = screen.getAllByRole('option') as HTMLOptionElement[];
    expect(opts[0].value).toBe('a');
    expect(opts[1].value).toBe('b');
    expect(opts[2].value).toBe('c');
  });
});
