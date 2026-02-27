import { describe, it, expect, beforeEach } from 'vitest';
import { useSettingsStore } from '../settingsStore';

describe('settingsStore', () => {
  beforeEach(() => {
    useSettingsStore.setState({ apiKey: '', advancedMode: false, autoTodoDefault: false });
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
