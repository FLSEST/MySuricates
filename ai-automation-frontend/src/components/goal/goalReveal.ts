import { useEffect, useRef, type RefObject } from 'react';
import { create } from 'zustand';

/**
 * „Zeigen“ after adding a TODO to the goal (85889063, Flo: „Ich habe + gedrückt und sehe es
 * aber nicht in der Timeline oben“): the button in the toast remembers here which TODO is to be shown.
 * The goal page switches from list and workflow assignment to the outline; outline and
 * timeline unfold what hides the TODO, scroll to it and briefly highlight it. A store
 * instead of a callback, because the toast outlives the page it came from.
 */
interface RevealRequest {
  goalId: string;
  todoId: string;
  /** Every click a new order - even twice the same TODO. */
  seq: number;
}

interface RevealState {
  request: RevealRequest | null;
  reveal: (goalId: string, todoId: string) => void;
  /** Done, provided `seq` is still the open order. */
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

/** Attribute of the row or chip of a TODO - this is how „Zeigen“ finds it. */
export const TODO_ID_ATTRIBUTE = 'data-todo-id';

/**
 * Scrolls to the TODO and highlights it (`[data-goal-revealed]`, index.css); with „reduced motion“
 * it jumps without gliding and glows without pulsing. False as long as it is not visibly in the DOM.
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
 * For a view of the goal: if an order for this goal is pending and the view knows the TODO,
 * `open` unfolds what hides it; as soon as it is rendered visibly, it is shown and the
 * order is done. A TODO that arrives only after reloading is shown as soon as it is there.
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
  // After every render: is it visible now? (unfolding needs one pass)
  useEffect(() => {
    if (seq === null || !todoId || !rootRef.current) return;
    if (revealElement(rootRef.current, todoId)) done(seq);
  });
}
