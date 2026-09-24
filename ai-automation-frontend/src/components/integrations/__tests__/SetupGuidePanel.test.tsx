import { describe, it, expect } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../../../test/utils';
import { SetupGuidePanel } from '../SetupGuidePanel';

describe('SetupGuidePanel — credential fields', () => {
  it('prefills the server URL for GitLab, so gitlab.com works without typing it', () => {
    renderWithProviders(<SetupGuidePanel providerId="gitlab" displayName="GitLab" onRefresh={() => {}} />);

    expect(screen.getByDisplayValue('https://gitlab.com')).toBeInTheDocument();
  });

  it('leaves the first field empty when the guide has no default (Gitea has no public default server)', () => {
    renderWithProviders(<SetupGuidePanel providerId="gitea" displayName="Gitea / Forgejo" onRefresh={() => {}} />);

    expect(screen.queryByDisplayValue(/^https?:/)).not.toBeInTheDocument();
    expect(screen.getByPlaceholderText(/git\.example\.org/)).toHaveValue('');
  });
});
