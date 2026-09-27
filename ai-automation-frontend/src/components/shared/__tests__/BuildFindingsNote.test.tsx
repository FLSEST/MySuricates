import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { BuildFindingsNote } from '../BuildFindingsNote';
import { useI18nStore } from '../../../i18n';

describe('BuildFindingsNote', () => {
  beforeEach(() => {
    useI18nStore.setState({ locale: 'en' });
  });

  it('renders nothing for an older backend without the field', () => {
    const { container } = render(<BuildFindingsNote findings={undefined} />);
    expect(container.firstChild).toBeNull();
  });

  it('renders nothing for a clean definition', () => {
    const { container } = render(<BuildFindingsNote findings={[]} />);
    expect(container.firstChild).toBeNull();
  });

  it('lists the findings in the given order', () => {
    render(<BuildFindingsNote findings={[
      'HIGH missing-required-config-key — Block 1: command fehlt',
      'MEDIUM unknown-config-key — Block 1: prompt',
    ]} />);
    expect(screen.getByText('Build check')).toBeInTheDocument();
    const items = screen.getAllByRole('listitem').map((li) => li.textContent);
    expect(items).toEqual([
      'HIGH missing-required-config-key — Block 1: command fehlt',
      'MEDIUM unknown-config-key — Block 1: prompt',
    ]);
  });
});
