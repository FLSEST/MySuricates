import type { RefObject } from 'react';
import { Plus } from 'lucide-react';
import { Card, Button, TagInput } from '../ui';
import { PRIORITIES } from './todoConstants';
import { useT } from '../../i18n';
import type { TodoQuickAddForm } from '../../hooks/useTodoQuickAdd';
import type { Project, TodoPriority } from '../../types';

/** Quick-add bar of the TODO page; state and the `n` shortcut come from `useTodoQuickAdd`. */
export function TodoQuickAdd({ form, inputRef, projects, allTags }: {
  form: TodoQuickAddForm;
  inputRef: RefObject<HTMLInputElement | null>;
  projects: Project[] | undefined;
  allTags: string[];
}) {
  const t = useT();
  return (
    <Card>
      <div className="flex items-center gap-3">
        {/* Title */}
        <input
          ref={inputRef}
          type="text"
          className="input-base flex-1 min-w-0"
          placeholder={`${t.todos.create}... (n)`}
          value={form.title}
          onChange={(e) => form.setTitle(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && !e.nativeEvent.isComposing) form.submit(); }}
        />
        {/* Description */}
        <input
          type="text"
          className="input-base flex-1 min-w-0"
          placeholder={`${t.todos.description}...`}
          value={form.description}
          onChange={(e) => form.setDescription(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && !e.nativeEvent.isComposing) form.submit(); }}
        />
        <div className="w-48">
          <TagInput
            tags={form.tags}
            onChange={form.setTags}
            suggestions={allTags}
            placeholder={t.todos.tags}
            compact
          />
        </div>
        <select
          className="input-base w-40"
          value={form.projectId}
          onChange={(e) => form.setProjectId(e.target.value)}
        >
          <option value="">{t.common.selectProject}</option>
          {projects?.map((p) => (
            <option key={p.id} value={p.id}>{p.name}</option>
          ))}
        </select>
        <select
          className="input-base w-28"
          value={form.priority}
          onChange={(e) => form.setPriority(e.target.value as TodoPriority)}
        >
          {PRIORITIES.map((p) => (
            <option key={p} value={p}>{t.todos.priorities[p]}</option>
          ))}
        </select>
        <input
          type="date"
          className="input-base w-36"
          value={form.dueDate}
          onChange={(e) => form.setDueDate(e.target.value)}
          title={t.todos.dueDate}
        />
        <Button
          icon={<Plus className="w-4 h-4" />}
          onClick={form.submit}
          loading={form.isPending}
          disabled={!form.title.trim()}
        >
          {t.common.create}
        </Button>
      </div>
    </Card>
  );
}
