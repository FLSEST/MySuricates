import { Card, CardHeader, Button } from '../ui';
import { useThemeStore } from '../../stores/themeStore';
import { useT } from '../../i18n';
import { Sun, Moon, Paintbrush, RotateCcw } from 'lucide-react';
import { lightTheme, darkTheme, type ThemeTokens } from '../../theme/tokens';

const COLOR_KEYS: Array<{ key: keyof ThemeTokens; label: string }> = [
  { key: '--color-accent', label: 'Accent' },
  { key: '--color-accent-hover', label: 'Accent Hover' },
  { key: '--color-success', label: 'Success' },
  { key: '--color-warning', label: 'Warning' },
  { key: '--color-danger', label: 'Danger' },
  { key: '--color-surface', label: 'Surface' },
  { key: '--color-surface-secondary', label: 'Surface Secondary' },
  { key: '--color-surface-hover', label: 'Surface Hover' },
  { key: '--color-surface-raised', label: 'Surface Raised' },
  { key: '--color-border', label: 'Border' },
  { key: '--color-sidebar', label: 'Sidebar' },
  { key: '--color-sidebar-active', label: 'Sidebar Active' },
  { key: '--color-sidebar-text', label: 'Sidebar Text' },
  { key: '--color-text', label: 'Text' },
  { key: '--color-text-secondary', label: 'Text Secondary' },
];

export function AppearanceCard() {
  const t = useT();
  const { mode, setMode, overrides, setOverride, resetOverrides } = useThemeStore();

  return (
    <Card>
      <CardHeader title={t.settings.appearance} />
      <div className="space-y-5">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            {mode === 'dark' ? (
              <Moon className="w-5 h-5 text-text-secondary" />
            ) : (
              <Sun className="w-5 h-5 text-text-secondary" />
            )}
            <div>
              <p className="text-sm font-medium text-text">{t.settings.theme}</p>
            </div>
          </div>
          <div className="flex items-center rounded-lg border border-border overflow-hidden">
            <button
              onClick={() => setMode('light')}
              className={`px-3 py-1.5 text-xs font-medium transition-colors ${
                mode === 'light' ? 'bg-accent text-text-inverse' : 'text-text-secondary hover:text-text'
              }`}
            >
              {t.settings.themeLight}
            </button>
            <button
              onClick={() => setMode('dark')}
              className={`px-3 py-1.5 text-xs font-medium transition-colors ${
                mode === 'dark' ? 'bg-accent text-text-inverse' : 'text-text-secondary hover:text-text'
              }`}
            >
              {t.settings.themeDark}
            </button>
          </div>
        </div>

        <div>
          <div className="flex items-center justify-between mb-3">
            <div className="flex items-center gap-3">
              <Paintbrush className="w-5 h-5 text-text-secondary" />
              <p className="text-sm font-medium text-text">{t.settings.colors}</p>
            </div>
            {Object.keys(overrides).length > 0 && (
              <Button
                variant="ghost"
                size="sm"
                icon={<RotateCcw className="w-3.5 h-3.5" />}
                onClick={resetOverrides}
              >
                {t.common.reset}
              </Button>
            )}
          </div>
          <div className="grid grid-cols-2 gap-3">
            {COLOR_KEYS.map(({ key, label }) => (
              <div key={key} className="flex items-center gap-2">
                <input
                  type="color"
                  value={overrides[key] ?? (mode === 'light' ? lightTheme[key] : darkTheme[key])}
                  onChange={(e) => setOverride(key, e.target.value)}
                  className="w-8 h-8 rounded-lg border border-border cursor-pointer bg-transparent"
                />
                <span className="text-xs text-text-secondary">{label}</span>
              </div>
            ))}
          </div>
        </div>
      </div>
    </Card>
  );
}
