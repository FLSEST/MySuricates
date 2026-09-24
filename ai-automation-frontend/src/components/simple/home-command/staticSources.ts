import { useMemo } from 'react';
import { useT, useI18nStore } from '../../../i18n';
import { hintText } from '../../../i18n/hints';
import { HINT_IDS } from '../../../data/hints';
import { useThemeStore } from '../../../stores/themeStore';
import { getTheme } from '../../../theme/themes';
import { useSystemModules } from '../../../hooks/useModules';
import { matchActions, type ActionId } from '../../../utils/homeCommand/actions';
import { matchHints } from '../../../utils/homeCommand/explanations';
import type { HomeAction, HomeIcon, HomeItem, HomeSource } from '../../../utils/homeCommand/types';
import type { SourceInput } from './dataSources';

/** Language, light/dark, create, settings, hints, Pro — a fixed list matched by de/en keywords. */
export function useActionSource({ query }: SourceInput): HomeSource {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const themeId = useThemeStore((s) => s.themeId);
  return useMemo(() => {
    const c = t.simple.home.command;
    const target = locale === 'de' ? 'en' : 'de';
    const dark = getTheme(themeId).appearance === 'dark';
    const defs: Record<ActionId, { label: string; icon: HomeIcon; action: HomeAction; button: string; pro?: boolean }> = {
      locale: {
        label: c.actions.locale.replace('{lang}', c.languages[target]),
        icon: 'language', action: { kind: 'set-locale', locale: target }, button: c.apply,
      },
      theme: {
        label: dark ? c.actions.themeLight : c.actions.themeDark,
        icon: 'theme', action: { kind: 'toggle-theme' }, button: c.apply,
      },
      'create-workflow': {
        label: c.actions.createWorkflow, icon: 'workflow-add', action: { kind: 'navigate', to: '/workflows?new=1' }, button: c.open,
      },
      'create-project': {
        label: c.actions.createProject, icon: 'project-add', action: { kind: 'navigate', to: '/projects?new=1' }, button: c.open,
      },
      settings: { label: c.actions.settings, icon: 'settings', action: { kind: 'navigate', to: '/settings' }, button: c.open },
      hints: { label: c.actions.hints, icon: 'hint', action: { kind: 'reset-hints' }, button: c.apply },
      pro: { label: c.actions.pro, icon: 'pro', action: { kind: 'open-in-pro' }, button: c.apply, pro: true },
    };
    const labels = Object.fromEntries(Object.entries(defs).map(([id, d]) => [id, d.label])) as Record<ActionId, string>;
    const items: HomeItem[] = matchActions(query, labels).map((id) => ({
      id: `action:${id}`,
      icon: defs[id].icon,
      label: defs[id].label,
      pro: defs[id].pro,
      primary: { label: defs[id].button, action: defs[id].action },
    }));
    return { id: 'actions', items, loading: false };
  }, [t, locale, themeId, query]);
}

/** The hints in place, searched in the UI language — "was ist ein Projekt?" answered without AI. */
export function useExplanationSource({ query }: SourceInput): HomeSource {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const { data: systemModules } = useSystemModules();
  return useMemo(() => {
    const disabled = new Set((systemModules?.modules ?? []).filter((m) => !m.enabled).map((m) => m.name));
    const texts = HINT_IDS.map((id) => ({ id, ...hintText(id, locale) }));
    const byId = new Map(texts.map((h) => [h.id, h]));
    const items: HomeItem[] = matchHints(query, texts, disabled).map((id) => ({
      id: `hint:${id}`,
      icon: 'explanation',
      label: byId.get(id)!.title,
      points: byId.get(id)!.points,
      primary: { label: t.simple.home.command.show, action: { kind: 'toggle-explanation', hintId: id } },
    }));
    return { id: 'explanations', items, loading: false };
  }, [t, locale, query, systemModules]);
}
