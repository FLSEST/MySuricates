#!/usr/bin/env bash
# Sprachen des Installers. Eine Sprache = eine Datei scripts/lib/i18n/<lang>.sh aus Zeilen
# M_<schluessel>="Text" (printf-Format: %s fuer Werte, %% fuer ein Prozentzeichen). Englisch ist die
# vollstaendige Basis und wird immer zuerst geladen; eine andere Sprache ueberschreibt nur, was sie
# kennt — ein fehlender Schluessel bleibt englisch statt leer (145d1f30). Standard ist Englisch;
# Deutsch nur mit --lang de. bash 3.2 (macOS): Skalare, ${!var}.

I18N_DIR="${I18N_DIR:-$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/i18n}"
I18N_LANG=en

# Vorhandene Sprachen, eine je Zeile
i18n_languages() {
  local f
  for f in "$I18N_DIR"/*.sh; do [ -f "$f" ] && basename "$f" .sh; done
}

# i18n_load [LANG] — Englisch, darueber LANG (nur Kleinbuchstaben, sonst ignoriert)
i18n_load() {
  local lang="${1:-en}"
  # shellcheck source=i18n/en.sh
  . "$I18N_DIR/en.sh"
  I18N_LANG=en
  case "$lang" in ""|en|*[!a-z]*) return 0 ;; esac
  if [ -r "$I18N_DIR/$lang.sh" ]; then
    # shellcheck disable=SC1090
    . "$I18N_DIR/$lang.sh"
    I18N_LANG="$lang"
  fi
}

# msg SCHLUESSEL [WERTE…] — Text der geladenen Sprache; unbekannter Schluessel ⇒ der Schluessel selbst
msg() {
  local name="M_${1:-}" fmt
  shift
  fmt="${!name-}"
  [ -n "$fmt" ] || fmt="${name#M_}"
  # shellcheck disable=SC2059
  printf -- "$fmt" "$@"
}
