import { Card, CardHeader } from '../ui';
import { useSettingsStore, clampPageSize, PAGE_SIZE_MIN, PAGE_SIZE_MAX } from '../../stores/settingsStore';
import { useT } from '../../i18n';

const inputClass = 'w-full text-sm bg-surface border border-border rounded-lg px-3 py-2 text-text focus:outline-none focus:ring-1 focus:ring-accent';

/** Page sizes of the todos page — values that steer behaviour belong in the settings, not in the code. */
export function TodoSettingsCard() {
  const t = useT();
  const listPageSize = useSettingsStore((s) => s.listPageSize);
  const kanbanColumnSize = useSettingsStore((s) => s.kanbanColumnSize);
  const setListPageSize = useSettingsStore((s) => s.setListPageSize);
  const setKanbanColumnSize = useSettingsStore((s) => s.setKanbanColumnSize);

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
    </Card>
  );
}
