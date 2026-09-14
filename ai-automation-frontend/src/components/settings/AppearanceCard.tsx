import { Card, CardHeader, Button } from '../ui';
import { useThemeStore } from '../../stores/themeStore';
import { useT } from '../../i18n';
import { Check, Sun, Moon, Paintbrush, RotateCcw } from 'lucide-react';
import type { ThemeTokens } from '../../theme/tokens';
import { THEMES, getTheme, type ThemeAppearance, type ThemeDefinition } from '../../theme/themes';

const COLOR_KEYS: Array<{ key: keyof ThemeTokens; label: string }> = [
  { key: '--color-accent', label: 'Accent' },
  { key: '--color-accent-hover', label: 'Accent Hover' },
  { key: '--color-success', label: 'Success' },
  { key: '--color-warning', label: 'Warning' },
  { key: '--color-danger', label: 'Danger' },
  { key: '--color-ai', label: 'AI' },
  { key: '--color-human', label: 'Human' },
  { key: '--color-info', label: 'Info' },
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

function ThemeSwatch({ theme, selected, onSelect }: { theme: ThemeDefinition; selected: boolean; onSelect: () => void }) {
  const c = theme.tokens;
  return (
    <button
      type="button"
      onClick={onSelect}
      aria-pressed={selected}
      title={theme.name}
      className={`text-left rounded-lg p-1.5 border transition-colors focus-ring ${
        selected ? 'border-accent ring-1 ring-accent' : 'border-border hover:border-border-strong'
      }`}
    >
      {/* Mini preview: sidebar strip, two text lines, accent pill and status dots */}
      <div
        className="flex h-12 rounded-md overflow-hidden"
        style={{ background: c['--color-surface'], boxShadow: `inset 0 0 0 1px ${c['--color-border']}` }}
      >
        <div className="w-1/4" style={{ background: c['--color-sidebar'] }} />
        <div className="flex-1 p-1.5 flex flex-col gap-1">
          <div className="h-1.5 w-3/4 rounded-full" style={{ background: c['--color-text'] }} />
          <div className="h-1.5 w-1/2 rounded-full" style={{ background: c['--color-text-secondary'] }} />
          <div className="mt-auto flex items-center gap-1">
            <span className="h-2 w-5 rounded-full" style={{ background: c['--color-accent'] }} />
            <span className="h-2 w-2 rounded-full" style={{ background: c['--color-success'] }} />
            <span className="h-2 w-2 rounded-full" style={{ background: c['--color-warning'] }} />
            <span className="h-2 w-2 rounded-full" style={{ background: c['--color-danger'] }} />
          </div>
        </div>
      </div>
      <div className="mt-1.5 flex items-center justify-between gap-1 px-0.5">
        <span className="text-xs text-text truncate">{theme.name}</span>
        {selected && <Check className="w-3.5 h-3.5 text-accent shrink-0" />}
      </div>
    </button>
  );
}

export function AppearanceCard() {
  const t = useT();
  const { themeId, setTheme, overrides, setOverride, resetOverrides } = useThemeStore();
  const activeTheme = getTheme(themeId);
  const themeOverrides = overrides[activeTheme.id] ?? {};

  const groups: Array<{ appearance: ThemeAppearance; label: string; icon: React.ReactNode }> = [
    { appearance: 'dark', label: t.settings.themeDark, icon: <Moon className="w-3.5 h-3.5" /> },
    { appearance: 'light', label: t.settings.themeLight, icon: <Sun className="w-3.5 h-3.5" /> },
  ];

  return (
    <Card>
      <CardHeader title={t.settings.appearance} />
      <div className="space-y-5">
        <div>
          <p className="text-sm font-medium text-text mb-3">{t.settings.theme}</p>
          <div className="space-y-4">
            {groups.map(({ appearance, label, icon }) => (
              <div key={appearance}>
                <div className="flex items-center gap-1.5 mb-2 text-xs font-medium text-text-secondary">
                  {icon}
                  {label}
                </div>
                <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
                  {THEMES.filter((theme) => theme.appearance === appearance).map((theme) => (
                    <ThemeSwatch
                      key={theme.id}
                      theme={theme}
                      selected={theme.id === activeTheme.id}
                      onSelect={() => setTheme(theme.id)}
                    />
                  ))}
                </div>
              </div>
            ))}
          </div>
        </div>

        <div>
          <div className="flex items-center justify-between mb-1">
            <div className="flex items-center gap-3">
              <Paintbrush className="w-5 h-5 text-text-secondary" />
              <p className="text-sm font-medium text-text">{t.settings.colors}</p>
            </div>
            {Object.keys(themeOverrides).length > 0 && (
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
          <p className="text-xs text-text-tertiary mb-3">{t.settings.colorsHint}</p>
          <div className="grid grid-cols-2 gap-3">
            {COLOR_KEYS.map(({ key, label }) => (
              <div key={key} className="flex items-center gap-2">
                <input
                  type="color"
                  value={themeOverrides[key] ?? activeTheme.tokens[key]}
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
