import { useId, useState, type KeyboardEvent } from 'react';
import { Plus, Search, X } from 'lucide-react';
import { Spinner } from '../ui';
import { useT } from '../../i18n';
import { useDebounced } from '../../hooks/useDebounced';
import { useGoalCandidates } from '../../hooks/useGoals';
import { useAddTodoToGoal } from '../../hooks/useGoalAddTodo';
import type { GoalWorkPackage, Todo } from '../../types';

/** So viele Treffer zeigt die Suche höchstens. */
const MAX_HITS = 6;

type Option = { kind: 'hit'; todo: Todo } | { kind: 'new'; title: string };

/**
 * „+ TODO“ direkt am Arbeitspaket (85889063): ein Feld, das TODOs ohne Ziel aus dem Projekt des
 * Pakets findet (`/goals/{id}/candidates`, hier aufs Projekt eingegrenzt), und darunter „Neu anlegen:
 * <Titel>“. Enter nimmt den ersten Treffer – oder legt an, wenn keiner passt –, die Pfeiltasten
 * wählen, Escape schließt. Treffer zählen nur, wenn sie zum Getippten passen: Solange die Suche
 * nachlädt, bleiben nur Titel stehen, die das Getippte enthalten, damit Enter nie etwas Fremdes nimmt.
 */
export function PackageTodoAdder({ goalId, workPackage, projectName, onClose }: {
  goalId: string;
  workPackage: GoalWorkPackage;
  projectName: string | null;
  onClose: () => void;
}) {
  const t = useT();
  const ta = t.goals.add;
  const inputId = useId();
  const listId = useId();
  const helpId = useId();
  const [text, setText] = useState('');
  const [picked, setPicked] = useState<number | null>(null);
  const typed = text.trim();
  const query = useDebounced(typed, 250);
  const candidates = useGoalCandidates(goalId, { q: query, tag: '' }, false);
  const add = useAddTodoToGoal(goalId);

  const fresh = query === typed && !candidates.isPlaceholderData && !candidates.isFetching;
  const needle = typed.toLowerCase();
  const hits = (candidates.data?.content ?? [])
    .filter((todo) => !workPackage.projectId || todo.projectId === workPackage.projectId)
    .filter((todo) => fresh || !needle || todo.title.toLowerCase().includes(needle))
    .slice(0, MAX_HITS);
  const options: Option[] = [...hits.map((todo): Option => ({ kind: 'hit', todo })), ...(typed ? [{ kind: 'new', title: typed } as const] : [])];
  // Ohne Getipptes ist nichts vorgewählt – Enter nimmt dann nur, was per Pfeil gewählt ist
  const active = picked !== null ? Math.min(picked, options.length - 1) : typed ? 0 : -1;
  const place = { id: workPackage.id, title: workPackage.title };

  const choose = (option: Option | undefined) => {
    if (!option || add.isPending) return;
    if (option.kind === 'hit') {
      add.addExisting({ todoId: option.todo.id, projectName: option.todo.projectName ?? projectName, workPackage: place }, { onSuccess: onClose });
    } else {
      add.addNew({ title: option.title, projectId: workPackage.projectId, projectName, workPackage: place }, { onSuccess: onClose });
    }
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      onClose();
    } else if (event.key === 'Enter') {
      event.preventDefault();
      choose(options[active]);
    } else if ((event.key === 'ArrowDown' || event.key === 'ArrowUp') && options.length > 0) {
      event.preventDefault();
      const step = event.key === 'ArrowDown' ? 1 : -1;
      setPicked((active + step + options.length) % options.length);
    }
  };

  const optionId = (index: number) => `${listId}-${index}`;
  return (
    <div className="mt-1 rounded-md border border-border bg-surface p-2 shadow-sm" data-testid={`package-todo-adder-${workPackage.id}`}>
      <div className="flex items-center gap-1.5">
        <label htmlFor={inputId} className="relative min-w-0 flex-1">
          <span className="sr-only">{ta.inputLabel}</span>
          <Search className="absolute left-2 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-text-tertiary" aria-hidden="true" />
          <input id={inputId} autoFocus type="text" value={text} placeholder={ta.placeholder} disabled={add.isPending}
            role="combobox" aria-autocomplete="list" aria-expanded={options.length > 0} aria-controls={listId}
            aria-activedescendant={active >= 0 ? optionId(active) : undefined} aria-describedby={helpId}
            onChange={(event) => {
              setText(event.target.value);
              setPicked(null);
            }}
            onKeyDown={onKeyDown}
            className="input-base w-full py-1 pl-7 text-sm" />
        </label>
        {add.isPending && <Spinner size="sm" />}
        <button type="button" onClick={onClose} aria-label={ta.close} title={ta.close}
          className="rounded p-1 text-text-tertiary hover:bg-surface-hover hover:text-text focus-ring">
          <X className="h-3.5 w-3.5" aria-hidden="true" />
        </button>
      </div>
      <p id={helpId} className="sr-only">{ta.help}</p>
      <ul id={listId} role="listbox" aria-label={ta.hits.replace('{project}', () => projectName ?? t.goals.outline.noProject)}
        className="mt-1 space-y-px">
        {options.map((option, index) => (
          <li key={option.kind === 'hit' ? option.todo.id : 'new'} id={optionId(index)} role="option" aria-selected={index === active}
            // Maus: wählen, ohne dem Feld den Fokus zu nehmen
            onMouseDown={(event) => event.preventDefault()}
            onClick={() => choose(option)}
            className={`flex cursor-pointer items-center gap-1.5 rounded px-1.5 py-1 text-sm ${
              index === active ? 'bg-accent-subtle text-accent' : 'text-text hover:bg-surface-hover'}`}>
            {option.kind === 'hit' ? (
              <span className="min-w-0 truncate" title={option.todo.title}>{option.todo.title}</span>
            ) : (
              <>
                <Plus className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                <span className="min-w-0 truncate">{ta.createNew.replace('{title}', () => option.title)}</span>
              </>
            )}
          </li>
        ))}
      </ul>
      {hits.length === 0 && fresh && !candidates.isLoading && (
        <p className="px-1.5 pt-0.5 text-xs text-text-tertiary">{ta.noHits}</p>
      )}
    </div>
  );
}
