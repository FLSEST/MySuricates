import { useEffect } from 'react';
import { Card, CardHeader, Toggle, Select } from '../ui';
import { useSettingsStore } from '../../stores/settingsStore';
import { useI18nStore, type Locale } from '../../i18n';
import { useT } from '../../i18n';
import { useUpdateSystemSetting, useAiProviders, useAssistantModelSetting, useSuggestionsModelSetting, useAutoRepairModeSetting } from '../../hooks/useSystem';
import { Globe, Bot, Sparkles } from 'lucide-react';

function modelOptionsForProvider(provider: string): { value: string; label: string }[] {
  switch (provider) {
    case 'CODEX':
      return [
        { value: '', label: 'Standard' },
        { value: 'o3', label: 'o3' },
        { value: 'o4-mini', label: 'o4-mini' },
      ];
    default:
      return [
        { value: '', label: 'Standard (Provider-Standard)' },
        { value: 'claude-haiku-4-5-20251001', label: 'Haiku (schnell)' },
        { value: 'claude-sonnet-4-6', label: 'Sonnet (präzise)' },
        { value: 'claude-opus-4-6', label: 'Opus (komplex)' },
      ];
  }
}

export function GeneralSettingsCard() {
  const t = useT();
  const { simpleMode, setSimpleMode, advancedMode, setAdvancedMode, autoTodoDefault, setAutoTodoDefault, autoRepairDefault, setAutoRepairDefault, autoRepairMode, setAutoRepairMode, defaultAiProvider, setDefaultAiProvider, assistantModel, setAssistantModel, suggestionsModel, setSuggestionsModel, suggestionsEnabled, setSuggestionsEnabled } = useSettingsStore();
  const updateSetting = useUpdateSystemSetting();
  const { locale, setLocale } = useI18nStore();
  const { data: aiProviders } = useAiProviders();
  const { data: fetchedAssistantModel } = useAssistantModelSetting();
  const { data: fetchedSuggestionsModel } = useSuggestionsModelSetting();
  const { data: fetchedRepairMode } = useAutoRepairModeSetting();

  useEffect(() => {
    if (aiProviders && !defaultAiProvider) {
      setDefaultAiProvider(aiProviders.default);
    }
  }, [aiProviders, defaultAiProvider, setDefaultAiProvider]);

  useEffect(() => {
    if (fetchedAssistantModel !== undefined && fetchedAssistantModel !== assistantModel) {
      setAssistantModel(fetchedAssistantModel);
    }
  }, [fetchedAssistantModel]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (fetchedSuggestionsModel !== undefined && fetchedSuggestionsModel !== suggestionsModel) {
      setSuggestionsModel(fetchedSuggestionsModel);
    }
  }, [fetchedSuggestionsModel]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (fetchedRepairMode !== undefined && fetchedRepairMode !== autoRepairMode) {
      setAutoRepairMode(fetchedRepairMode);
    }
  }, [fetchedRepairMode]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <Card>
      <CardHeader title={t.settings.general} />
      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <div>
            <p className="text-sm font-medium text-text">{t.settings.simpleMode}</p>
            <p className="text-xs text-text-tertiary">{t.settings.simpleModeHint}</p>
          </div>
          <Toggle enabled={simpleMode} onChange={setSimpleMode} />
        </div>

        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <Globe className="w-5 h-5 text-text-secondary" />
            <div>
              <p className="text-sm font-medium text-text">{t.settings.language}</p>
            </div>
          </div>
          <Select
            value={locale}
            onChange={(e) => setLocale(e.target.value as Locale)}
            options={[
              { value: 'en', label: 'English' },
              { value: 'de', label: 'Deutsch' },
            ]}
          />
        </div>

        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <Bot className="w-5 h-5 text-text-secondary" />
            <div>
              <p className="text-sm font-medium text-text">{t.settings.defaultAiProvider}</p>
              <p className="text-xs text-text-tertiary">{t.settings.defaultAiProviderHint}</p>
            </div>
          </div>
          <Select
            value={defaultAiProvider || aiProviders?.default || ''}
            onChange={(e) => {
              setDefaultAiProvider(e.target.value);
              updateSetting.mutate({ key: 'default-ai-provider', value: e.target.value });
            }}
            options={(aiProviders?.providers || []).map((p: string) => ({
              value: p,
              label: p === 'CLAUDE' ? 'Claude CLI' : p === 'CODEX' ? 'Codex CLI' : p,
            }))}
          />
        </div>

        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <Bot className="w-5 h-5 text-text-secondary" />
            <div>
              <p className="text-sm font-medium text-text">{t.settings.assistantModel}</p>
              <p className="text-xs text-text-tertiary">{t.settings.assistantModelHint}</p>
            </div>
          </div>
          <Select
            value={assistantModel}
            onChange={(e) => {
              setAssistantModel(e.target.value);
              updateSetting.mutate({ key: 'assistant-model', value: e.target.value });
            }}
            options={modelOptionsForProvider(defaultAiProvider || aiProviders?.default || 'CLAUDE')}
          />
        </div>

        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <Sparkles className="w-5 h-5 text-text-secondary" />
            <div>
              <p className="text-sm font-medium text-text">{t.settings.suggestionsEnabled}</p>
              <p className="text-xs text-text-tertiary">{t.settings.suggestionsEnabledHint}</p>
            </div>
          </div>
          <Toggle
            enabled={suggestionsEnabled}
            onChange={(enabled) => {
              setSuggestionsEnabled(enabled);
              updateSetting.mutate({ key: 'suggestions-enabled', value: String(enabled) });
            }}
          />
        </div>

        {suggestionsEnabled && (
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <Sparkles className="w-5 h-5 text-text-secondary" />
              <div>
                <p className="text-sm font-medium text-text">{t.settings.suggestionsModel}</p>
                <p className="text-xs text-text-tertiary">{t.settings.suggestionsModelHint}</p>
              </div>
            </div>
            <Select
              value={suggestionsModel}
              onChange={(e) => {
                setSuggestionsModel(e.target.value);
                updateSetting.mutate({ key: 'suggestions-model', value: e.target.value });
              }}
              options={modelOptionsForProvider(defaultAiProvider || aiProviders?.default || 'CLAUDE')}
            />
          </div>
        )}

        {!simpleMode && (
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm font-medium text-text">{t.settings.advanced}</p>
              <p className="text-xs text-text-tertiary">{t.settings.advancedHint}</p>
            </div>
            <Toggle enabled={advancedMode} onChange={setAdvancedMode} />
          </div>
        )}

        {!simpleMode && (
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm font-medium text-text">{t.settings.autoTodoDefault}</p>
              <p className="text-xs text-text-tertiary">{t.settings.autoTodoDefaultHint}</p>
            </div>
            <Toggle
              enabled={autoTodoDefault}
              onChange={(enabled) => {
                setAutoTodoDefault(enabled);
                updateSetting.mutate({ key: 'auto-todo-default', value: String(enabled) });
              }}
            />
          </div>
        )}

        {!simpleMode && (
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm font-medium text-text">{t.settings.autoRepairDefault}</p>
              <p className="text-xs text-text-tertiary">{t.settings.autoRepairDefaultHint}</p>
            </div>
            <Toggle
              enabled={autoRepairDefault}
              onChange={(enabled) => {
                setAutoRepairDefault(enabled);
                updateSetting.mutate({ key: 'auto-repair-default', value: String(enabled) });
              }}
            />
          </div>
        )}

        {!simpleMode && autoRepairDefault && (
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm font-medium text-text">{t.settings.autoRepairMode}</p>
              <p className="text-xs text-text-tertiary">{t.settings.autoRepairModeHint}</p>
            </div>
            <Select
              value={autoRepairMode}
              onChange={(e) => {
                setAutoRepairMode(e.target.value);
                updateSetting.mutate({ key: 'auto-repair-mode', value: e.target.value });
              }}
              options={[
                { value: 'auto', label: t.settings.autoRepairModeAuto },
                { value: 'test', label: t.settings.autoRepairModeTest },
                { value: 'local', label: t.settings.autoRepairModeLocal },
              ]}
            />
          </div>
        )}

        {!simpleMode && autoRepairDefault && (
          <div className="grid grid-cols-2 gap-4">
            <div>
              <p className="text-sm font-medium text-text">{t.workflows.autoRepairMaxRetries}</p>
              <p className="text-xs text-text-tertiary mb-1">{t.workflows.autoRepairMaxRetriesHint}</p>
              <input
                type="number"
                min={1}
                max={5}
                defaultValue={2}
                onBlur={(e) => {
                  const val = Math.max(1, Math.min(5, parseInt(e.target.value) || 2));
                  updateSetting.mutate({ key: 'auto-repair-max-retries', value: String(val) });
                }}
                className="w-full text-sm bg-surface border border-border rounded-lg px-3 py-2 text-text focus:outline-none focus:ring-1 focus:ring-accent"
              />
            </div>
            <div>
              <p className="text-sm font-medium text-text">{t.workflows.autoRepairTimeout}</p>
              <p className="text-xs text-text-tertiary mb-1">{t.workflows.autoRepairTimeoutHint}</p>
              <input
                type="number"
                min={60}
                max={3600}
                step={60}
                defaultValue={600}
                onBlur={(e) => {
                  const val = Math.max(60, Math.min(3600, parseInt(e.target.value) || 600));
                  updateSetting.mutate({ key: 'auto-repair-timeout-seconds', value: String(val) });
                }}
                className="w-full text-sm bg-surface border border-border rounded-lg px-3 py-2 text-text focus:outline-none focus:ring-1 focus:ring-accent"
              />
            </div>
          </div>
        )}
      </div>
    </Card>
  );
}
