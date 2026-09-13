import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { TagInput } from '../TagInput';

const SUGGESTIONS = ['alpha', 'beta', 'gamma'];

function highlighted(): string | undefined {
  return screen
    .getAllByRole('button')
    .find((b) => SUGGESTIONS.includes(b.textContent ?? '') && b.className.includes('text-accent'))
    ?.textContent ?? undefined;
}

describe('TagInput', () => {
  it('renders existing tags and calls onChange when a tag is removed', () => {
    const onChange = vi.fn();
    render(<TagInput tags={['one', 'two']} onChange={onChange} />);
    expect(screen.getByText('one')).toBeInTheDocument();
    fireEvent.click(screen.getByText('two').querySelector('button')!);
    expect(onChange).toHaveBeenCalledWith(['one']);
  });

  it('shows suggestions on focus with the first one highlighted', () => {
    render(<TagInput tags={[]} onChange={vi.fn()} suggestions={SUGGESTIONS} />);
    fireEvent.focus(screen.getByRole('textbox'));
    expect(highlighted()).toBe('alpha');
  });

  it('moves the highlight with ArrowDown and resets it to the first match when typing', () => {
    render(<TagInput tags={[]} onChange={vi.fn()} suggestions={SUGGESTIONS} />);
    const input = screen.getByRole('textbox');
    fireEvent.focus(input);
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    expect(highlighted()).toBe('beta');

    fireEvent.change(input, { target: { value: 'a' } });
    // 'a' matches alpha, beta and gamma — highlight is back on the first match
    expect(highlighted()).toBe('alpha');
  });

  it('adds the highlighted suggestion on Enter', () => {
    const onChange = vi.fn();
    render(<TagInput tags={[]} onChange={onChange} suggestions={SUGGESTIONS} />);
    const input = screen.getByRole('textbox');
    fireEvent.focus(input);
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    fireEvent.change(input, { target: { value: 'g' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onChange).toHaveBeenCalledWith(['gamma']);
  });

  it('adds the typed text (trimmed, lowercased) on Enter when nothing matches', () => {
    const onChange = vi.fn();
    render(<TagInput tags={[]} onChange={onChange} suggestions={SUGGESTIONS} />);
    const input = screen.getByRole('textbox');
    fireEvent.change(input, { target: { value: '  NewTag ' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onChange).toHaveBeenCalledWith(['newtag']);
  });
});
