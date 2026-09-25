import { useState, type Dispatch, type SetStateAction } from 'react';
import {
  type AuditFinding, type AuditFindingStatus, useCreateAuditTodo, useLinkAuditTodo, useUpdateAuditFinding,
} from './useAuditCenter';
import { useToast } from './useToast';
import { useT } from '../i18n';
import { filterLabel, filterOf, withFilter, type FindingFilter } from '../utils/auditFindingFilter';

/** Dispositions a person has to justify: the finding stays, but nobody acts on it. */
const NEEDS_REASON = new Set<AuditFindingStatus>(['ACCEPTED_RISK', 'DISMISSED']);

/**
 * What a person does with the findings in the Audit Center: file one under another status (with a
 * reason where one is due), create a TODO or link one. The reason form and the link form are open
 * for one finding at a time, across the list.
 */
export function useFindingActions(filter: FindingFilter[], setFilter: Dispatch<SetStateAction<FindingFilter[]>>) {
  const t = useT();
  const toast = useToast();
  const update = useUpdateAuditFinding();
  const createTodo = useCreateAuditTodo();
  const linkTodo = useLinkAuditTodo();
  const [linking, setLinking] = useState<string | null>(null);
  const [todoId, setTodoId] = useState('');
  // Accepting a risk or dismissing a finding asks for the reason first — it stays with the finding
  // (dispositionNote) and is what a later review reads (d75fbfef).
  const [disposing, setDisposing] = useState<{ findingId: string; status: AuditFindingStatus; note: string } | null>(null);

  // A finding filed under a status whose chip is off leaves the list — the toast says where it went.
  const announceMove = (updated: AuditFinding) => {
    const target = filterOf(updated.status);
    if (filter.includes(target)) return;
    toast.success(t.auditCenter.movedTo.replace('{filter}', filterLabel(t, target)), {
      label: t.auditCenter.showFilter,
      onClick: () => setFilter((current) => withFilter(current, target)),
    });
  };

  return {
    disposing,
    saving: update.isPending,
    chooseStatus: (finding: AuditFinding, status: AuditFindingStatus) => {
      if (NEEDS_REASON.has(status)) {
        setDisposing({ findingId: finding.id, status, note: finding.dispositionNote ?? '' });
        return;
      }
      setDisposing(null);
      update.mutate({ id: finding.id, status }, { onSuccess: announceMove });
    },
    setDispositionNote: (note: string) => setDisposing((current) => current && { ...current, note }),
    saveDisposition: () => {
      if (!disposing) return;
      update.mutate({ id: disposing.findingId, status: disposing.status, dispositionNote: disposing.note.trim() },
        { onSuccess: (updated) => { setDisposing(null); announceMove(updated); } });
    },
    cancelDisposition: () => setDisposing(null),
    linking,
    linkPending: linkTodo.isPending,
    toggleLinking: (findingId: string) => setLinking(linking === findingId ? null : findingId),
    todoId,
    setTodoId,
    submitLink: (findingId: string) => {
      if (!todoId.trim()) return;
      linkTodo.mutate({ findingId, todoId: todoId.trim() }, { onSuccess: () => { setTodoId(''); setLinking(null); } });
    },
    createTodo: (findingId: string) => createTodo.mutate(findingId),
    creatingTodo: createTodo.isPending,
  };
}

export type FindingActions = ReturnType<typeof useFindingActions>;
