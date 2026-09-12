import { useState, type KeyboardEvent } from 'react';
import { RefreshCw, AlertTriangle } from 'lucide-react';
import { Input } from '../ui';
import { useProviderModels, useRefreshProviderModels, usePiCatalog } from '../../hooks/useSystem';
import { useT } from '../../i18n';

interface ModelFieldProps {
  /** Provider whose catalog supplies the suggestions (CLAUDE, PI, CODEX). */
  providerId: string;
  value: string;
  /** Called with the committed value: on blur, Enter, or when a catalog entry is picked. */
  onChange: (value: string) => void;
  /** Distinguishes several fields for the same provider on one page (datalist ids must be unique). */
  idSuffix?: string;
  /** Show pi's auth/custom/default hints below the field. */
  showPiHints?: boolean;
  className?: string;
}

/**
 * Free-text model input with host-discovered suggestions.
 *
 * The suggestions come from the backend model catalog, so they stay current without code
 * changes; free text stays allowed so a concrete id (e.g. `claude-opus-5`) can be pinned or a
 * model outside the catalog used. Empty = provider default.
 */
export function ModelField({ providerId, value, onChange, idSuffix = '', showPiHints = false, className = '' }: ModelFieldProps) {
  const t = useT();
  const normalizedProvider = (providerId || 'CLAUDE').toUpperCase();
  const isPi = normalizedProvider === 'PI';
  const { data: catalog, isLoading } = useProviderModels(normalizedProvider);
  const refresh = useRefreshProviderModels();
  const { data: piCatalog } = usePiCatalog(showPiHints && isPi);
  const [draft, setDraft] = useState(value ?? '');
  // Re-sync the draft when the committed value changes from outside (e.g. provider switch
  // resets the model) — "adjust state during render" instead of an effect.
  const [syncedValue, setSyncedValue] = useState(value ?? '');
  if ((value ?? '') !== syncedValue) {
    setSyncedValue(value ?? '');
    setDraft(value ?? '');
  }

  const listId = `${normalizedProvider.toLowerCase()}-model-suggestions${idSuffix}`;
  const models = catalog?.available ? catalog.models : [];

  const commit = (next: string) => {
    const trimmed = next.trim();
    if (trimmed !== (value ?? '')) {
      onChange(trimmed);
    }
  };

  const handleChange = (next: string) => {
    setDraft(next);
    // A datalist pick arrives as a change event with the full value — commit right away.
    if (models.some((m) => m.value === next)) {
      commit(next);
    }
  };

  const handleKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      commit(draft);
    }
  };

  const notInCatalog =
    !!draft.trim() && !!catalog?.available && models.length > 0 && !models.some((m) => m.value === draft.trim());

  return (
    <div className={className}>
      <Input
        list={listId}
        value={draft}
        onChange={(e) => handleChange(e.target.value)}
        onBlur={() => commit(draft)}
        onKeyDown={handleKeyDown}
        placeholder={isPi ? t.workflows.modelPlaceholderPi : t.workflows.modelPlaceholder}
        autoComplete="off"
        spellCheck={false}
      />
      <datalist id={listId}>
        {models.map((m) => (
          <option key={m.value} value={m.value}>{m.label}</option>
        ))}
      </datalist>

      <div className="text-xs text-text-tertiary mt-1.5 flex items-center gap-2 flex-wrap">
        {isLoading && <span>{t.workflows.modelCatalogLoading}</span>}
        {catalog?.available && (
          <span>
            {t.workflows.modelCatalogSource
              .replace('{count}', String(models.length))
              .replace('{source}', catalog.source)}
          </span>
        )}
        {catalog && !catalog.available && (
          <span className="text-warning inline-flex items-center gap-1">
            <AlertTriangle className="w-3.5 h-3.5" />
            {t.workflows.modelCatalogUnavailable.replace('{reason}', catalog.reason ?? '')}
          </span>
        )}
        {catalog && (
          <button
            type="button"
            onClick={() => refresh.mutate(normalizedProvider)}
            disabled={refresh.isPending}
            className="inline-flex items-center gap-1 hover:text-text disabled:opacity-50"
            title={t.workflows.modelCatalogRefresh}
            aria-label={t.workflows.modelCatalogRefresh}
          >
            <RefreshCw className={`w-3.5 h-3.5 ${refresh.isPending ? 'animate-spin' : ''}`} />
          </button>
        )}
      </div>

      {notInCatalog && (
        <p className="text-xs text-text-tertiary mt-1">{t.workflows.modelNotInCatalog}</p>
      )}

      {showPiHints && isPi && piCatalog && piCatalog.installed && (
        <div className="text-xs text-text-tertiary mt-2 space-y-1">
          {piCatalog.authenticatedProviders.length > 0 && (
            <p>
              {t.workflows.piAuthenticated} <span className="font-mono">{piCatalog.authenticatedProviders.join(', ')}</span>
            </p>
          )}
          {piCatalog.customProviders.length > 0 && (
            <p>
              {t.workflows.piCustom} <span className="font-mono">{piCatalog.customProviders.join(', ')}</span>
            </p>
          )}
          {piCatalog.defaultProvider && (
            <p>
              {t.workflows.piDefault}{' '}
              <span className="font-mono">
                {piCatalog.defaultProvider}{piCatalog.defaultModel ? `/${piCatalog.defaultModel}` : ''}
              </span>{' '}
              {t.workflows.piDefaultUsedWhenEmpty}
            </p>
          )}
        </div>
      )}
      {showPiHints && isPi && piCatalog && !piCatalog.installed && (
        <p className="text-xs text-warning mt-2">{t.workflows.piNotInstalled}</p>
      )}
    </div>
  );
}
