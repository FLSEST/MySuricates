import { useState } from 'react';
import { FolderOpen, FolderUp, Folder, Loader2, ChevronRight } from 'lucide-react';
import { Modal } from './Modal';
import { Button } from './Button';
import { useBrowseDirectory } from '../../hooks/useSystem';
import { useT } from '../../i18n';

interface DirectoryPickerProps {
  label?: string;
  value: string;
  onChange: (path: string) => void;
  placeholder?: string;
  error?: string;
}

export function DirectoryPicker({ label, value, onChange, placeholder, error }: DirectoryPickerProps) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const [browsePath, setBrowsePath] = useState<string | null>(null);

  const { data, isLoading, isError } = useBrowseDirectory(browsePath);

  const handleOpen = () => {
    setBrowsePath(value || '');
    setOpen(true);
  };

  const handleSelect = () => {
    if (data?.current) {
      onChange(data.current);
    }
    setOpen(false);
  };

  // Split current path into breadcrumb segments
  const breadcrumbs = data?.current
    ? data.current.split('/').filter(Boolean).map((segment, i, arr) => ({
        name: segment,
        path: '/' + arr.slice(0, i + 1).join('/'),
      }))
    : [];

  return (
    <div className="space-y-1.5">
      {label && (
        <label className="block text-sm font-medium text-text-secondary">{label}</label>
      )}
      <div className="flex gap-2">
        <input
          className={`input-base flex-1 ${error ? 'border-danger focus:ring-danger/40' : ''}`}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
        />
        <button
          type="button"
          onClick={handleOpen}
          className="btn-base bg-surface-raised border border-border text-text-secondary hover:text-text hover:bg-surface-overlay hover:border-border-strong px-3"
          title={t.projects.browse}
        >
          <FolderOpen className="w-4 h-4" />
        </button>
      </div>
      {error && <p className="text-xs text-danger">{error}</p>}

      <Modal
        open={open}
        onClose={() => setOpen(false)}
        title={t.projects.selectDirectory}
        size="lg"
        footer={
          <>
            <Button variant="secondary" onClick={() => setOpen(false)}>
              {t.common.cancel}
            </Button>
            <Button onClick={handleSelect} disabled={!data?.current}>
              {t.projects.selectDirectory}
            </Button>
          </>
        }
      >
        {/* Breadcrumb navigation */}
        <div className="flex items-center gap-1 text-sm mb-4 flex-wrap min-h-[28px]">
          <button
            onClick={() => setBrowsePath(data?.parent || '')}
            className="text-text-tertiary hover:text-text transition-colors shrink-0"
          >
            /
          </button>
          {breadcrumbs.map((crumb, i) => (
            <span key={crumb.path} className="flex items-center gap-1">
              <ChevronRight className="w-3 h-3 text-text-tertiary shrink-0" />
              <button
                onClick={() => setBrowsePath(crumb.path)}
                className={`hover:text-accent transition-colors truncate max-w-[150px] ${
                  i === breadcrumbs.length - 1
                    ? 'text-text font-medium'
                    : 'text-text-secondary'
                }`}
              >
                {crumb.name}
              </button>
            </span>
          ))}
        </div>

        {/* Current path display */}
        {data?.current && (
          <div className="text-xs font-mono text-text-tertiary bg-surface rounded-lg px-3 py-2 mb-3 truncate">
            {data.current}
          </div>
        )}

        {/* Directory listing */}
        <div className="border border-border rounded-lg overflow-hidden">
          {/* Parent directory button */}
          {data?.parent && (
            <button
              onClick={() => setBrowsePath(data.parent!)}
              className="flex items-center gap-3 w-full px-4 py-2.5 text-sm text-text-secondary hover:bg-surface-raised transition-colors border-b border-border"
            >
              <FolderUp className="w-4 h-4 text-text-tertiary" />
              <span>{t.projects.parentDirectory}</span>
            </button>
          )}

          {isLoading ? (
            <div className="flex items-center justify-center py-8">
              <Loader2 className="w-5 h-5 animate-spin text-accent" />
            </div>
          ) : isError ? (
            <div className="px-4 py-8 text-center text-sm text-danger">
              {t.common.error}
            </div>
          ) : data?.directories.length === 0 ? (
            <div className="px-4 py-8 text-center text-sm text-text-tertiary">
              {t.common.noResults}
            </div>
          ) : (
            <div className="max-h-[300px] overflow-y-auto">
              {data?.directories.map((dir) => (
                <button
                  key={dir.path}
                  onClick={() => setBrowsePath(dir.path)}
                  className="flex items-center gap-3 w-full px-4 py-2.5 text-sm text-text hover:bg-surface-raised transition-colors border-b border-border last:border-b-0"
                >
                  <Folder className="w-4 h-4 text-accent shrink-0" />
                  <span className="truncate">{dir.name}</span>
                </button>
              ))}
            </div>
          )}
        </div>
      </Modal>
    </div>
  );
}
