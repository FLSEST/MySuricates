import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { BlockSkillPicker } from '../BlockSkillPicker';
import { useI18nStore } from '../../../i18n';

const skills = [
  { id: 'builtin-code-review', name: 'Code Review' },
  { id: 'builtin-security', name: 'Security' },
];

describe('BlockSkillPicker', () => {
  beforeEach(() => useI18nStore.setState({ locale: 'en' }));

  it('offers every skill on an AI block and toggles the selection', () => {
    const onChange = vi.fn();
    render(<BlockSkillPicker blockType="simple" configJson="{}" skills={skills} selected={[]} onChange={onChange} />);
    fireEvent.click(screen.getByRole('button', { name: 'Security' }));
    expect(onChange).toHaveBeenCalledWith(['builtin-security']);
    expect(screen.getByRole('button', { name: 'Code Review' })).toBeInTheDocument();
  });

  it('is hidden on a shell block without skills', () => {
    render(<BlockSkillPicker blockType="shell" configJson="{}" skills={skills} selected={[]} onChange={vi.fn()} />);
    expect(screen.queryByTestId('block-skill-picker')).not.toBeInTheDocument();
  });

  it('is hidden on a wrapper block in shell mode', () => {
    render(<BlockSkillPicker blockType="foreach" configJson='{"execution_mode": "shell"}' skills={skills} selected={[]} onChange={vi.fn()} />);
    expect(screen.queryByTestId('block-skill-picker')).not.toBeInTheDocument();
  });

  it('keeps skills a shell block already carries visible, with a hint, so they can be removed', () => {
    const onChange = vi.fn();
    render(<BlockSkillPicker blockType="shell" configJson="{}" skills={skills} selected={['builtin-security']} onChange={onChange} />);
    expect(screen.getByText(/no effect/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Code Review' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Security' }));
    expect(onChange).toHaveBeenCalledWith([]);
  });
});
