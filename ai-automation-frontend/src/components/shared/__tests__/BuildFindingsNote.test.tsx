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

  // TODO 81773d7b: newer backends send the lines in English too
  it('shows the English lines in the English UI', () => {
    render(<BuildFindingsNote findings={['HIGH r — Block 1: Pflichtschlüssel command fehlt']}
      findingsEn={['HIGH r — Block 1: required key command is missing']} />);
    expect(screen.getByRole('listitem')).toHaveTextContent('required key command is missing');
  });

  it('keeps the German lines in the German UI', () => {
    useI18nStore.setState({ locale: 'de' });
    render(<BuildFindingsNote findings={['HIGH r — Block 1: Pflichtschlüssel command fehlt']}
      findingsEn={['HIGH r — Block 1: required key command is missing']} />);
    expect(screen.getByRole('listitem')).toHaveTextContent('Pflichtschlüssel command fehlt');
  });

  it('falls back to the German lines when the English ones are empty (older backend)', () => {
    render(<BuildFindingsNote findings={['HIGH r — Block 1: Pflichtschlüssel command fehlt']} findingsEn={[]} />);
    expect(screen.getByRole('listitem')).toHaveTextContent('Pflichtschlüssel command fehlt');
  });
});
