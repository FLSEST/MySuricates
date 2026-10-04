import { describe, it, expect } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../../../test/utils';
import { en } from '../../../i18n/en';
import type { WorkflowExportData } from '../../../types';
import { WorkflowImportModal } from '../WorkflowImportModal';

function renderModal(importData: WorkflowExportData) {
  return renderWithProviders(
    <WorkflowImportModal
      t={en}
      open
      importData={importData}
      importName={importData.name}
      importProjectId=""
      projects={[]}
      importLoading={false}
      onImportNameChange={() => {}}
      onImportProjectIdChange={() => {}}
      onImport={() => {}}
      onClose={() => {}}
    />,
  );
}

describe('WorkflowImportModal — checksum of the export file (b18939a6)', () => {
  it('warns that a file without checksum cannot be checked', () => {
    renderModal({ name: 'Old export', blocks: [] });

    expect(screen.getByTestId('import-integrity-missing')).toHaveTextContent(en.workflows.importIntegrityMissing);
  });

  it('shows no warning for a file with checksum — the server checks it', () => {
    renderModal({ name: 'New export', blocks: [], integrity: 'sha256:ab' });

    expect(screen.queryByTestId('import-integrity-missing')).toBeNull();
  });
});
