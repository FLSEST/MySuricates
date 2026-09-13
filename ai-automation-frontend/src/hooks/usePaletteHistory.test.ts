import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { usePaletteHistory } from './usePaletteHistory';

describe('usePaletteHistory', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    localStorage.clear();
  });

  describe('initial state', () => {
    it('should_return_empty_lists_when_localStorage_is_empty', () => {
      const { result } = renderHook(() => usePaletteHistory('workflow'));
      expect(result.current.recents).toEqual([]);
      expect(result.current.pins).toEqual([]);
    });

    it('should_hydrate_recents_from_localStorage_on_mount_for_workflow_context', () => {
      localStorage.setItem('workflows-recents', JSON.stringify(['a', 'b', 'c']));
      localStorage.setItem('workflows-pins', JSON.stringify(['x']));
      const { result } = renderHook(() => usePaletteHistory('workflow'));
      expect(result.current.recents).toEqual(['a', 'b', 'c']);
      expect(result.current.pins).toEqual(['x']);
    });

    it('should_hydrate_from_namespaced_keys_for_non_workflow_context', () => {
      localStorage.setItem('palette-recents-chains', JSON.stringify(['c1']));
      localStorage.setItem('palette-pins-chains', JSON.stringify(['p1']));
      const { result } = renderHook(() => usePaletteHistory('chains'));
      expect(result.current.recents).toEqual(['c1']);
      expect(result.current.pins).toEqual(['p1']);
    });

    it('should_return_empty_list_when_localStorage_has_invalid_json', () => {
      localStorage.setItem('workflows-recents', '{not-json');
      const { result } = renderHook(() => usePaletteHistory('workflow'));
      expect(result.current.recents).toEqual([]);
    });

    it('should_filter_non_string_entries_from_localStorage', () => {
      localStorage.setItem('workflows-recents', JSON.stringify(['a', 1, null, 'b', { x: 1 }]));
      const { result } = renderHook(() => usePaletteHistory('workflow'));
      expect(result.current.recents).toEqual(['a', 'b']);
    });

    it('should_return_empty_list_when_stored_value_is_not_an_array', () => {
      localStorage.setItem('workflows-recents', JSON.stringify({ foo: 'bar' }));
      const { result } = renderHook(() => usePaletteHistory('workflow'));
      expect(result.current.recents).toEqual([]);
    });
  });

  describe('pushRecent', () => {
    it('should_add_id_to_recents_when_pushRecent_called', () => {
      const { result } = renderHook(() => usePaletteHistory('workflow'));
      act(() => result.current.pushRecent('wf-1'));
      expect(result.current.recents).toEqual(['wf-1']);
    });

    it('should_persist_recents_to_localStorage_when_pushRecent_called', () => {
      const { result } = renderHook(() => usePaletteHistory('workflow'));
      act(() => result.current.pushRecent('wf-1'));
      expect(JSON.parse(localStorage.getItem('workflows-recents') || '[]')).toEqual(['wf-1']);
    });

    it('should_move_id_to_front_when_already_present_in_recents', () => {
      const { result } = renderHook(() => usePaletteHistory('workflow'));
      act(() => result.current.pushRecent('a'));
      act(() => result.current.pushRecent('b'));
      act(() => result.current.pushRecent('c'));
      act(() => result.current.pushRecent('a'));
      expect(result.current.recents).toEqual(['a', 'c', 'b']);
    });

    it('should_not_change_state_when_id_is_already_first', () => {
      const { result } = renderHook(() => usePaletteHistory('workflow'));
      act(() => result.current.pushRecent('a'));
      const before = result.current.recents;
      act(() => result.current.pushRecent('a'));
      expect(result.current.recents).toBe(before);
    });

    it('should_enforce_max_length_of_10_recents', () => {
      const { result } = renderHook(() => usePaletteHistory('workflow'));
      for (let i = 0; i < 15; i++) {
        act(() => result.current.pushRecent(`id-${i}`));
      }
      expect(result.current.recents).toHaveLength(10);
      // newest at front
      expect(result.current.recents[0]).toBe('id-14');
      // oldest 5 dropped
      expect(result.current.recents).not.toContain('id-0');
      expect(result.current.recents).not.toContain('id-4');
    });

    it('should_use_namespaced_storage_key_for_non_workflow_context', () => {
      const { result } = renderHook(() => usePaletteHistory('chains'));
      act(() => result.current.pushRecent('chain-1'));
      expect(JSON.parse(localStorage.getItem('palette-recents-chains') || '[]')).toEqual(['chain-1']);
      expect(localStorage.getItem('workflows-recents')).toBeNull();
    });
  });

  describe('togglePin', () => {
    it('should_add_id_to_pins_when_not_pinned', () => {
      const { result } = renderHook(() => usePaletteHistory('workflow'));
      act(() => result.current.togglePin('wf-1'));
      expect(result.current.pins).toEqual(['wf-1']);
      expect(result.current.isPinned('wf-1')).toBe(true);
    });

    it('should_remove_id_from_pins_when_already_pinned', () => {
      const { result } = renderHook(() => usePaletteHistory('workflow'));
      act(() => result.current.togglePin('wf-1'));
      act(() => result.current.togglePin('wf-1'));
      expect(result.current.pins).toEqual([]);
      expect(result.current.isPinned('wf-1')).toBe(false);
    });

    it('should_persist_pins_to_localStorage_when_toggled', () => {
      const { result } = renderHook(() => usePaletteHistory('workflow'));
      act(() => result.current.togglePin('wf-1'));
      expect(JSON.parse(localStorage.getItem('workflows-pins') || '[]')).toEqual(['wf-1']);
    });

    it('should_enforce_max_length_of_8_pins', () => {
      const { result } = renderHook(() => usePaletteHistory('workflow'));
      for (let i = 0; i < 12; i++) {
        act(() => result.current.togglePin(`p-${i}`));
      }
      expect(result.current.pins).toHaveLength(8);
      // newest at front
      expect(result.current.pins[0]).toBe('p-11');
    });

    it('should_prepend_new_pins_so_newest_is_first', () => {
      const { result } = renderHook(() => usePaletteHistory('workflow'));
      act(() => result.current.togglePin('a'));
      act(() => result.current.togglePin('b'));
      act(() => result.current.togglePin('c'));
      expect(result.current.pins).toEqual(['c', 'b', 'a']);
    });
  });

  describe('isPinned', () => {
    it('should_return_false_when_id_not_in_pins', () => {
      const { result } = renderHook(() => usePaletteHistory('workflow'));
      expect(result.current.isPinned('missing')).toBe(false);
    });

    it('should_return_true_when_id_in_pins', () => {
      localStorage.setItem('workflows-pins', JSON.stringify(['x', 'y']));
      const { result } = renderHook(() => usePaletteHistory('workflow'));
      expect(result.current.isPinned('y')).toBe(true);
    });
  });

  describe('cross-instance synchronization', () => {
    it('should_sync_pushRecent_across_two_hook_instances_via_event', () => {
      const { result: a } = renderHook(() => usePaletteHistory('workflow'));
      const { result: b } = renderHook(() => usePaletteHistory('workflow'));
      act(() => a.current.pushRecent('shared-id'));
      expect(b.current.recents).toEqual(['shared-id']);
    });

    it('should_sync_togglePin_across_two_hook_instances_via_event', () => {
      const { result: a } = renderHook(() => usePaletteHistory('workflow'));
      const { result: b } = renderHook(() => usePaletteHistory('workflow'));
      act(() => a.current.togglePin('pinned'));
      expect(b.current.pins).toEqual(['pinned']);
      expect(b.current.isPinned('pinned')).toBe(true);
    });

    it('should_isolate_state_between_different_contexts', () => {
      const { result: wf } = renderHook(() => usePaletteHistory('workflow'));
      const { result: ch } = renderHook(() => usePaletteHistory('chains'));
      act(() => wf.current.pushRecent('w-1'));
      act(() => ch.current.pushRecent('c-1'));
      expect(wf.current.recents).toEqual(['w-1']);
      expect(ch.current.recents).toEqual(['c-1']);
    });
  });

  describe('contextId change', () => {
    it('should_swap_lists_in_place_when_contextId_prop_changes', () => {
      localStorage.setItem('workflows-recents', JSON.stringify(['w']));
      localStorage.setItem('workflows-pins', JSON.stringify(['wp']));
      localStorage.setItem('palette-recents-chains', JSON.stringify(['c']));

      const { result, rerender } = renderHook(({ ctx }) => usePaletteHistory(ctx), {
        initialProps: { ctx: 'workflow' },
      });
      expect(result.current.recents).toEqual(['w']);

      rerender({ ctx: 'chains' });
      expect(result.current.recents).toEqual(['c']);
      expect(result.current.pins).toEqual([]);
      // the old context's storage must not be overwritten with the new context's lists
      expect(JSON.parse(localStorage.getItem('workflows-recents') || '[]')).toEqual(['w']);
      expect(JSON.parse(localStorage.getItem('workflows-pins') || '[]')).toEqual(['wp']);
      // and the new context's storage must not receive the old context's lists
      expect(JSON.parse(localStorage.getItem('palette-recents-chains') || '[]')).toEqual(['c']);
    });

    it('should_re_hydrate_when_contextId_changes', () => {
      // The hook relies on React key-prop remount for contextId changes (see hook comment).
      // Simulate this by unmounting and remounting with the new contextId.
      localStorage.setItem('workflows-recents', JSON.stringify(['w']));
      localStorage.setItem('palette-recents-chains', JSON.stringify(['c']));

      const { result, unmount } = renderHook(() => usePaletteHistory('workflow'));
      expect(result.current.recents).toEqual(['w']);

      unmount();

      const { result: result2 } = renderHook(() => usePaletteHistory('chains'));
      expect(result2.current.recents).toEqual(['c']);
    });
  });
});
