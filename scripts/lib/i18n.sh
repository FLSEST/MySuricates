#!/usr/bin/env bash
# Installer languages. One language = one file scripts/lib/i18n/<lang>.sh of lines
# M_<key>="text" (printf format: %s for values, %% for a percent sign). English is the
# complete base and is always loaded first; another language overrides only what it
# knows — a missing key stays English instead of empty (145d1f30). Default is English;
# German only with --lang de. bash 3.2 (macOS): scalars, ${!var}.

I18N_DIR="${I18N_DIR:-$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/i18n}"
I18N_LANG=en

# Available languages, one per line
i18n_languages() {
  local f
  for f in "$I18N_DIR"/*.sh; do [ -f "$f" ] && basename "$f" .sh; done
}

# i18n_load [LANG] — English, with LANG on top (lowercase letters only, otherwise ignored)
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

# msg KEY [VALUES…] — text of the loaded language; unknown key ⇒ the key itself
msg() {
  local name="M_${1:-}" fmt
  shift
  fmt="${!name-}"
  [ -n "$fmt" ] || fmt="${name#M_}"
  # shellcheck disable=SC2059
  printf -- "$fmt" "$@"
}
