import { describe, it, expect, beforeEach } from 'vitest';
import { useSettingsStore, PAGE_SIZE_MIN, PAGE_SIZE_MAX } from '../settingsStore';

describe('settingsStore', () => {
  beforeEach(() => {
    useSettingsStore.setState({ apiKey: '', advancedMode: false, autoTodoDefault: false, listPageSize: 30, kanbanColumnSize: 50 });
  });

  it('has correct initial state', () => {
    const state = useSettingsStore.getState();
    expect(state.apiKey).toBe('');
    expect(state.advancedMode).toBe(false);
    expect(state.autoTodoDefault).toBe(false);
  });

  it('setApiKey updates the api key', () => {
    useSettingsStore.getState().setApiKey('test-key-123');
    expect(useSettingsStore.getState().apiKey).toBe('test-key-123');
  });

  it('setApiKey can set empty string', () => {
    useSettingsStore.getState().setApiKey('some-key');
    useSettingsStore.getState().setApiKey('');
    expect(useSettingsStore.getState().apiKey).toBe('');
  });

  it('setAdvancedMode enables advanced mode', () => {
    useSettingsStore.getState().setAdvancedMode(true);
    expect(useSettingsStore.getState().advancedMode).toBe(true);
  });

  it('setAdvancedMode disables advanced mode', () => {
    useSettingsStore.getState().setAdvancedMode(true);
    useSettingsStore.getState().setAdvancedMode(false);
    expect(useSettingsStore.getState().advancedMode).toBe(false);
  });

  it('setAutoTodoDefault toggles auto todo default', () => {
    useSettingsStore.getState().setAutoTodoDefault(true);
    expect(useSettingsStore.getState().autoTodoDefault).toBe(true);
    useSettingsStore.getState().setAutoTodoDefault(false);
    expect(useSettingsStore.getState().autoTodoDefault).toBe(false);
  });

  it('todo page sizes default to 30 (list) and 50 (kanban column)', () => {
    const state = useSettingsStore.getState();
    expect(state.listPageSize).toBe(30);
    expect(state.kanbanColumnSize).toBe(50);
  });

  it('clamps the list page size to 10–200', () => {
    const { setListPageSize } = useSettingsStore.getState();
    setListPageSize(5);
    expect(useSettingsStore.getState().listPageSize).toBe(PAGE_SIZE_MIN);
    setListPageSize(5000);
    expect(useSettingsStore.getState().listPageSize).toBe(PAGE_SIZE_MAX);
    setListPageSize(75);
    expect(useSettingsStore.getState().listPageSize).toBe(75);
  });

  it('clamps the kanban column size to 10–200 and ignores garbage input', () => {
    const { setKanbanColumnSize } = useSettingsStore.getState();
    setKanbanColumnSize(1);
    expect(useSettingsStore.getState().kanbanColumnSize).toBe(PAGE_SIZE_MIN);
    setKanbanColumnSize(201);
    expect(useSettingsStore.getState().kanbanColumnSize).toBe(PAGE_SIZE_MAX);
    setKanbanColumnSize(Number.NaN);
    expect(useSettingsStore.getState().kanbanColumnSize).toBe(PAGE_SIZE_MAX);
    setKanbanColumnSize(80.6);
    expect(useSettingsStore.getState().kanbanColumnSize).toBe(80);
  });

  it('multiple setters do not interfere with each other', () => {
    useSettingsStore.getState().setApiKey('my-key');
    useSettingsStore.getState().setAdvancedMode(true);
    useSettingsStore.getState().setAutoTodoDefault(true);

    const state = useSettingsStore.getState();
    expect(state.apiKey).toBe('my-key');
    expect(state.advancedMode).toBe(true);
    expect(state.autoTodoDefault).toBe(true);
  });
});
