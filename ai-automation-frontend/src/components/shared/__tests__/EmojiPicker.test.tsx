import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { useI18nStore } from '../../../i18n';
import { EmojiPicker } from '../EmojiPicker';

beforeEach(() => useI18nStore.setState({ locale: 'en' }));

describe('EmojiPicker', () => {
  it('marks the chosen emoji and reports a new choice', () => {
    const onChange = vi.fn();
    render(<EmojiPicker label="Icon" value="📝" onChange={onChange} />);

    expect(screen.getByRole('button', { name: '📝' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: '🚀' })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByRole('button', { name: 'None' })).toHaveAttribute('aria-pressed', 'false');

    fireEvent.click(screen.getByRole('button', { name: '🚀' }));
    expect(onChange).toHaveBeenCalledWith('🚀');
  });

  it('clears the icon with "None"', () => {
    const onChange = vi.fn();
    render(<EmojiPicker label="Icon" value="📝" onChange={onChange} />);

    fireEvent.click(screen.getByRole('button', { name: 'None' }));
    expect(onChange).toHaveBeenCalledWith(null);
  });

  it('shows "None" as chosen without an icon', () => {
    render(<EmojiPicker label="Icon" value={null} onChange={() => {}} />);
    expect(screen.getByRole('button', { name: 'None' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByLabelText('Custom')).toHaveValue('');
  });

  it('keeps an icon outside the grid in the custom field', () => {
    const onChange = vi.fn();
    render(<EmojiPicker label="Icon" value="🦦" onChange={onChange} />);

    const custom = screen.getByLabelText('Custom');
    expect(custom).toHaveValue('🦦');
    expect(screen.getAllByRole('button').filter((b) => b.getAttribute('aria-pressed') === 'true')).toHaveLength(0);

    fireEvent.change(custom, { target: { value: '🐙' } });
    expect(onChange).toHaveBeenLastCalledWith('🐙');
    fireEvent.change(custom, { target: { value: '  ' } });
    expect(onChange).toHaveBeenLastCalledWith(null);
  });

  it('offers every icon the built-in skills ship with', () => {
    render(<EmojiPicker label="Icon" value={null} onChange={() => {}} />);
    const builtinIcons = ['🧪', '⚠️', '✅', '✏️', '🌍', '🏛️', '📄', '📊', '📋', '📝', '📧', '📱', '🔍', '🔬', '🗒️', '🛠️', '🛡️'];
    for (const icon of builtinIcons) {
      expect(screen.getByRole('button', { name: icon })).toBeInTheDocument();
    }
  });
});
