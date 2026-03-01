import { Card, CardHeader, Toggle, Select } from '../ui';
import { useSettingsStore } from '../../stores/settingsStore';
import { useI18nStore, type Locale } from '../../i18n';
import { useT } from '../../i18n';
import { useUpdateSystemSetting } from '../../hooks/useSystem';
import { Globe } from 'lucide-react';

export function GeneralSettingsCard() {
  const t = useT();
  const { advancedMode, setAdvancedMode, autoTodoDefault, setAutoTodoDefault, autoRepairDefault, setAutoRepairDefault } = useSettingsStore();
  const updateSetting = useUpdateSystemSetting();
  const { locale, setLocale } = useI18nStore();

  return (
    <Card>
      <CardHeader title={t.settings.general} />
      <div className="space-y-4">
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
          <div>
            <p className="text-sm font-medium text-text">{t.settings.advanced}</p>
            <p className="text-xs text-text-tertiary">{t.settings.advancedHint}</p>
          </div>
          <Toggle enabled={advancedMode} onChange={setAdvancedMode} />
        </div>

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
      </div>
    </Card>
  );
}
