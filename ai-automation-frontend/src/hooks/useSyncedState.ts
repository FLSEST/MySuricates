import { useState, type Dispatch, type SetStateAction } from 'react';

/**
 * Local state that follows an external `source` value (prop, query data, storage key …).
 *
 * The initial value is derived lazily from the first `source`. Whenever `source` changes
 * identity (compared with `Object.is`, like a dependency array entry), the state is re-derived
 * during render — the "adjusting state when a prop changes" pattern from the React docs —
 * instead of via an effect, so there is no extra commit with stale state and no
 * `react-hooks/set-state-in-effect` violation. Local edits via the returned setter survive
 * until the source changes again.
 *
 * `derive` receives the previous state (undefined on first render) so callers can keep it,
 * e.g. `(config, prev) => config ? toForm(config) : prev ?? DEFAULTS` to ignore a source that
 * became unavailable.
 */
export function useSyncedState<S, T>(
  source: T,
  derive: (source: T, prev: S | undefined) => S,
): [S, Dispatch<SetStateAction<S>>] {
  const [state, setState] = useState<S>(() => derive(source, undefined));
  const [prevSource, setPrevSource] = useState<T>(source);
  if (!Object.is(source, prevSource)) {
    setPrevSource(source);
    setState(derive(source, state));
  }
  return [state, setState];
}
