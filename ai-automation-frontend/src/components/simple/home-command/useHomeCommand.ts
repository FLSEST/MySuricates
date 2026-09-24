import { useMemo } from 'react';
import { useDebounced } from '../../../hooks/useDebounced';
import { parseHomeQuery } from '../../../utils/homeCommand/query';
import { arrangeGroups } from '../../../utils/homeCommand/arrange';
import {
  useApprovalSource, useProjectSource, useRunSource, useScheduleSource, useTemplateSource, useTodoSource,
  useWorkflowSource, type SourceInput,
} from './dataSources';
import { useActionSource, useExplanationSource } from './staticSources';
import type { HomeGroup, ParsedQuery } from '../../../utils/homeCommand/types';

/** Pause after the last key before sources fetch what the home does not load anyway. */
export const FETCH_DEBOUNCE_MS = 200;

/**
 * The command field's hits for `input`, grouped and trimmed — every source, no AI. Matching runs on
 * every key; requests (templates, pool, task search, schedules) wait for the debounced query.
 * New source ⇒ a hook returning `HomeSource` in `dataSources`/`staticSources`, called here.
 */
export function useHomeCommand(input: string): { query: ParsedQuery; groups: HomeGroup[]; loading: boolean } {
  const debounced = useDebounced(input, FETCH_DEBOUNCE_MS);
  // A fresh "now" per query, so "heute" stays right across midnight
  const { now, query } = useMemo(() => {
    const at = new Date();
    return { now: at, query: parseHomeQuery(input, at.getDay()) };
  }, [input]);
  const settled = useMemo(() => parseHomeQuery(debounced, now.getDay()), [debounced, now]);
  const sourceInput: SourceInput = useMemo(
    () => ({ query, settled, now, fetch: debounced.trim().length > 0 }),
    [query, settled, now, debounced],
  );

  const sources = [
    useWorkflowSource(sourceInput),
    useApprovalSource(sourceInput),
    useRunSource(sourceInput),
    useTemplateSource(sourceInput),
    useTodoSource(sourceInput),
    useScheduleSource(sourceInput),
    useProjectSource(sourceInput),
    useExplanationSource(sourceInput),
    useActionSource(sourceInput),
  ];
  const active = query.raw.length > 0;
  const groups = active ? arrangeGroups(sources, query.intents) : [];
  return { query, groups, loading: active && sources.some((s) => s.loading) };
}
