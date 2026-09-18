import { Save, Loader2 } from 'lucide-react';
import { Card, CardHeader, Button, Toggle, Spinner } from '../ui';
import { useSettingsStore, clampPageSize, PAGE_SIZE_MIN, PAGE_SIZE_MAX } from '../../stores/settingsStore';
import { useSyncedState } from '../../hooks/useSyncedState';
import { useExecutionConfig, useUpdateExecutionConfig } from '../../hooks/useSystem';
import { useT } from '../../i18n';

const inputClass = 'w-full text-sm bg-surface border border-border rounded-lg px-3 py-2 text-text focus:outline-none focus:ring-1 focus:ring-accent';

/** Bounds of the hygiene settings — mirror of ExecutionSettingKeys on the backend. */
const STALE_DAYS_MIN = 7;
const STALE_DAYS_MAX = 365;
const MAX_PER_RUN_MIN = 10;
const MAX_PER_RUN_MAX = 2000;

interface HygieneForm {
  enabled: boolean;
  staleDays: number;
  maxPerRun: number;
}

/** Defaults of TodoHygieneConfig — only used until the server config has arrived. */
const HYGIENE_DEFAULTS: HygieneForm = { enabled: false, staleDays: 30, maxPerRun: 200 };

type Config = ReturnType<typeof useExecutionConfig>['data'];

function toHygieneForm(config: NonNullable<Config>): HygieneForm {
  return {
    enabled: (config.todoHygieneEnabled ?? 0) === 1,
    staleDays: config.todoStaleDays ?? HYGIENE_DEFAULTS.staleDays,
    maxPerRun: config.todoHygieneMaxPerRun ?? HYGIENE_DEFAULTS.maxPerRun,
  };
}

/**
 * Everything that steers the todo views in one place: the page sizes (a local preference) and
 * the hygiene thresholds of the nightly auto-archival (a runtime setting on the server).
 */
export function TodoSettingsCard() {
  const t = useT();
  const listPageSize = useSettingsStore((s) => s.listPageSize);
  const kanbanColumnSize = useSettingsStore((s) => s.kanbanColumnSize);
  const setListPageSize = useSettingsStore((s) => s.setListPageSize);
  const setKanbanColumnSize = useSettingsStore((s) => s.setKanbanColumnSize);

  const { data: config, isLoading } = useExecutionConfig();
  const updateConfig = useUpdateExecutionConfig();

  // Form follows the server config; local edits survive until a new config object arrives.
  const [hygiene, setHygiene] = useSyncedState<HygieneForm, Config>(
    config, (c, prev) => (c ? toHygieneForm(c) : prev ?? HYGIENE_DEFAULTS),
  );

  const saved = config ? toHygieneForm(config) : null;
  const hasChanges = !!saved && (
    hygiene.enabled !== saved.enabled ||
    hygiene.staleDays !== saved.staleDays ||
    hygiene.maxPerRun !== saved.maxPerRun
  );

  const handleSave = () => {
    updateConfig.mutate({
      todoHygieneEnabled: hygiene.enabled ? 1 : 0,
      todoStaleDays: hygiene.staleDays,
      todoHygieneMaxPerRun: hygiene.maxPerRun,
    });
  };

  return (
    <Card>
      <CardHeader title={t.settings.todoSettings} />
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
        <div>
          <label htmlFor="todo-list-page-size" className="text-sm font-medium text-text">
            {t.settings.todoListPageSize}
          </label>
          <p className="text-xs text-text-tertiary mb-1">{t.settings.todoListPageSizeHint}</p>
          <input
            id="todo-list-page-size"
            type="number"
            min={PAGE_SIZE_MIN}
            max={PAGE_SIZE_MAX}
            defaultValue={listPageSize}
            onBlur={(e) => {
              const size = clampPageSize(Number(e.target.value));
              setListPageSize(size);
              e.target.value = String(size);
            }}
            className={inputClass}
          />
        </div>
        <div>
          <label htmlFor="todo-kanban-column-size" className="text-sm font-medium text-text">
            {t.settings.todoKanbanColumnSize}
          </label>
          <p className="text-xs text-text-tertiary mb-1">{t.settings.todoKanbanColumnSizeHint}</p>
          <input
            id="todo-kanban-column-size"
            type="number"
            min={PAGE_SIZE_MIN}
            max={PAGE_SIZE_MAX}
            defaultValue={kanbanColumnSize}
            onBlur={(e) => {
              const size = clampPageSize(Number(e.target.value));
              setKanbanColumnSize(size);
              e.target.value = String(size);
            }}
            className={inputClass}
          />
        </div>
      </div>

      {/* Nightly auto-archival — a runtime setting, shared by every client */}
      <div className="mt-6 pt-5 border-t border-border">
        <p className="text-sm font-medium text-text">{t.settings.todoHygiene}</p>
        <p className="text-xs text-text-tertiary mb-3">{t.settings.todoHygieneHint}</p>

        {isLoading ? (
          <div className="flex justify-center py-6"><Spinner /></div>
        ) : (
          <div className="space-y-4">
            <div className="flex items-center justify-between gap-4">
              <div>
                <span className="block text-sm font-medium text-text">{t.settings.todoHygieneEnabled}</span>
                <p className="text-xs text-text-tertiary mt-0.5">{t.settings.todoHygieneEnabledHint}</p>
              </div>
              <Toggle
                enabled={hygiene.enabled}
                ariaLabel={t.settings.todoHygieneEnabled}
                onChange={(v) => setHygiene((prev) => ({ ...prev, enabled: v }))}
              />
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div>
                <label htmlFor="todo-stale-days" className="text-sm font-medium text-text">
                  {t.settings.todoStaleDays}
                </label>
                <p className="text-xs text-text-tertiary mb-1">{t.settings.todoStaleDaysHint}</p>
                <input
                  id="todo-stale-days"
                  type="number"
                  min={STALE_DAYS_MIN}
                  max={STALE_DAYS_MAX}
                  value={hygiene.staleDays}
                  onChange={(e) => {
                    const days = parseInt(e.target.value, 10);
                    if (!isNaN(days)) setHygiene((prev) => ({ ...prev, staleDays: days }));
                  }}
                  className={inputClass}
                />
              </div>
              <div>
                <label htmlFor="todo-hygiene-max-per-run" className="text-sm font-medium text-text">
                  {t.settings.todoHygieneMaxPerRun}
                </label>
                <p className="text-xs text-text-tertiary mb-1">{t.settings.todoHygieneMaxPerRunHint}</p>
                <input
                  id="todo-hygiene-max-per-run"
                  type="number"
                  min={MAX_PER_RUN_MIN}
                  max={MAX_PER_RUN_MAX}
                  value={hygiene.maxPerRun}
                  onChange={(e) => {
                    const max = parseInt(e.target.value, 10);
                    if (!isNaN(max)) setHygiene((prev) => ({ ...prev, maxPerRun: max }));
                  }}
                  className={inputClass}
                />
              </div>
            </div>

            <div className="flex justify-end">
              <Button size="sm" disabled={!hasChanges || updateConfig.isPending} onClick={handleSave}>
                {updateConfig.isPending
                  ? <Loader2 className="w-4 h-4 animate-spin" />
                  : <Save className="w-4 h-4 mr-1.5" />}
                {t.settings.executionSave}
              </Button>
            </div>
          </div>
        )}
      </div>
    </Card>
  );
}
