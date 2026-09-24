import { useEffect, useRef, useState } from 'react';
import { useCreateTodo } from './useTodos';
import type { CreateTodoRequest, TodoPriority } from '../types';

/**
 * Zustand der Quick-Add-Leiste auf der TODO-Seite. Liegt in der Seite (nicht in `TodoQuickAdd`),
 * damit Eingaben einen Lade-Spinner beim Filterwechsel überstehen und das Kürzel `n` ab dem
 * Seitenaufruf greift: außerhalb von Eingabefeldern fokussiert es den Titel.
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
