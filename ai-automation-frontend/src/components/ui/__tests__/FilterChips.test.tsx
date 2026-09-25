import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { FilterChips, type FilterChipOption } from '../FilterChips';

type Fruit = 'apple' | 'pear' | 'plum';

const options: FilterChipOption<Fruit>[] = [
  { value: 'apple', label: 'Apple', count: 3 },
  { value: 'pear', label: 'Pear', count: 0 },
  { value: 'plum', label: 'Plum' },
];

describe('FilterChips', () => {
  it('is a labelled group of toggle buttons that tell whether they are on', () => {
    render(<FilterChips options={options} selected={['pear']} onChange={vi.fn()} ariaLabel="Filter fruit" />);

    expect(screen.getByRole('group', { name: 'Filter fruit' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Apple/ })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByRole('button', { name: /Pear/ })).toHaveAttribute('aria-pressed', 'true');
  });

  it('shows the count next to the label, also a zero, and nothing without one', () => {
    render(<FilterChips options={options} selected={[]} onChange={vi.fn()} ariaLabel="Filter fruit" />);

    expect(screen.getByRole('button', { name: /Apple/ })).toHaveTextContent('Apple3');
    expect(screen.getByRole('button', { name: /Pear/ })).toHaveTextContent('Pear0');
    expect(screen.getByRole('button', { name: /Plum/ })).toHaveTextContent(/^Plum$/);
  });

  it('adds a chip to the selection and keeps the order of the options', () => {
    const onChange = vi.fn();
    render(<FilterChips options={options} selected={['plum']} onChange={onChange} ariaLabel="Filter fruit" />);

    fireEvent.click(screen.getByRole('button', { name: /Apple/ }));

    expect(onChange).toHaveBeenCalledWith(['apple', 'plum']);
  });

  it('removes a chip that is on, down to an empty selection', () => {
    const onChange = vi.fn();
    render(<FilterChips options={options} selected={['pear']} onChange={onChange} ariaLabel="Filter fruit" />);

    fireEvent.click(screen.getByRole('button', { name: /Pear/ }));

    expect(onChange).toHaveBeenCalledWith([]);
  });
});
