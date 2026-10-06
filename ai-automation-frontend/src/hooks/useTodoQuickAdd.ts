import { useEffect, useRef, useState } from 'react';
import { useCreateTodo } from './useTodos';
import type { CreateTodoRequest, TodoPriority } from '../types';

/**
 * State of the quick-add bar on the TODO page. Lives in the page (not in `TodoQuickAdd`),
 * so input survives a loading spinner on filter change and the `n` shortcut works from the
 * first page load: outside input fields it focuses the title.
 */
export function useTodoQuickAdd() {
  const createTodo = useCreateTodo();
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [projectId, setProjectId] = useState('');
  const [priority, setPriority] = useState<TodoPriority>('MEDIUM');
  const [tags, setTags] = useState<string[]>([]);
  const [dueDate, setDueDate] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);

  // Keyboard shortcut: 'n' focuses quick-add
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'n' && !e.ctrlKey && !e.metaKey && !e.altKey) {
        const tag = (e.target as HTMLElement).tagName;
        if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
        e.preventDefault();
        inputRef.current?.focus();
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  const submit = () => {
    if (!title.trim()) return;
    const req: CreateTodoRequest = {
      title: title.trim(),
      description: description.trim() || undefined,
      priority,
      projectId: projectId || undefined,
      tags: tags.length > 0 ? tags : undefined,
      dueDate: dueDate ? new Date(dueDate + 'T23:59:59').toISOString() : undefined,
    };
    createTodo.mutate(req, {
      onSuccess: () => {
        setTitle('');
        setDescription('');
        setPriority('MEDIUM');
        setTags([]);
        setDueDate('');
        inputRef.current?.focus();
      },
    });
  };

  const form = {
    title, setTitle,
    description, setDescription,
    projectId, setProjectId,
    priority, setPriority,
    tags, setTags,
    dueDate, setDueDate,
    submit,
    isPending: createTodo.isPending,
  };
  return { form, inputRef };
}

export type TodoQuickAddForm = ReturnType<typeof useTodoQuickAdd>['form'];
