import { useEffect, useRef, type RefObject } from 'react';
import { create } from 'zustand';

/**
 * „Zeigen“ nach dem Hinzufügen eines TODOs zum Ziel (85889063, Flo: „Ich habe + gedrückt und sehe es
 * aber nicht in der Timeline oben“): Der Knopf im Toast merkt sich hier, welches TODO gezeigt werden
 * soll. Die Zielseite wechselt aus Liste und Workflow-Zuordnung in die Gliederung; Gliederung und
 * Zeitstrahl klappen auf, was das TODO verdeckt, scrollen hin und heben es kurz hervor. Ein Store
 * statt eines Callbacks, weil der Toast die Seite überlebt, von der er kam.
 */
interface RevealRequest {
  goalId: string;
  todoId: string;
  /** Jeder Klick ein neuer Auftrag – auch zweimal dasselbe TODO. */
  seq: number;
}

interface RevealState {
  request: RevealRequest | null;
  reveal: (goalId: string, todoId: string) => void;
  /** Erledigt, sofern `seq` noch der offene Auftrag ist. */
  done: (seq: number) => void;
  clear: () => void;
}

export const useGoalReveal = create<RevealState>()((set) => ({
  request: null,
  reveal: (goalId, todoId) => set((state) => ({ request: { goalId, todoId, seq: (state.request?.seq ?? 0) + 1 } })),
  done: (seq) => set((state) => (state.request?.seq === seq ? { request: null } : state)),
  clear: () => set({ request: null }),
}));

/** So lange leuchtet das gezeigte TODO. */
export const REVEAL_MS = 2000;

/** Attribut der Zeile bzw. des Chips eines TODOs – daran findet „Zeigen“ es. */
export const TODO_ID_ATTRIBUTE = 'data-todo-id';

/**
 * Scrollt zum TODO und hebt es hervor (`[data-goal-revealed]`, index.css); bei „weniger Bewegung“
 * springt es ohne Gleiten und leuchtet ohne Pulsieren. Falsch, solange es nicht sichtbar im DOM steht.
 */
export function revealElement(root: ParentNode, todoId: string): boolean {
  const element = [...root.querySelectorAll<HTMLElement>(`[${TODO_ID_ATTRIBUTE}]`)]
    .find((candidate) => candidate.getAttribute(TODO_ID_ATTRIBUTE) === todoId);
  if (!element || element.closest('[hidden]')) return false;
  const reduced = typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  element.scrollIntoView?.({ block: 'center', inline: 'center', behavior: reduced ? 'auto' : 'smooth' });
  element.setAttribute('data-goal-revealed', '');
  window.setTimeout(() => element.removeAttribute('data-goal-revealed'), REVEAL_MS);
  return true;
}

/**
 * Für eine Ansicht des Ziels: Steht ein Auftrag für dieses Ziel an und kennt die Ansicht das TODO,
 * klappt `open` auf, was es verdeckt; sobald es sichtbar gerendert ist, wird es gezeigt und der
 * Auftrag ist erledigt. Ein TODO, das erst nach dem Nachladen ankommt, wird gezeigt, sobald es da ist.
 */
export function useRevealInView(goalId: string, rootRef: RefObject<HTMLElement | null>, known: (todoId: string) => boolean,
  open: (todoId: string) => void) {
  const request = useGoalReveal((state) => state.request);
  const done = useGoalReveal((state) => state.done);
  const target = request && request.goalId === goalId && known(request.todoId) ? request : null;
  const openRef = useRef(open);
  useEffect(() => {
    openRef.current = open;
  });
  const seq = target?.seq ?? null;
  const todoId = target?.todoId ?? null;
  useEffect(() => {
    if (seq !== null && todoId) openRef.current(todoId);
  }, [seq, todoId]);
  // Nach jedem Rendern: ist es jetzt sichtbar? (das Aufklappen braucht einen Durchgang)
  useEffect(() => {
    if (seq === null || !todoId || !rootRef.current) return;
    if (revealElement(rootRef.current, todoId)) done(seq);
  });
}
