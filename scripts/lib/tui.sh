#!/usr/bin/env bash
# Terminal-Oberflaeche des Installers (504e5038): Kopf, Schrittleiste, gerahmte Panels, Menues,
# Befehle mit Fortschrittszeile. Aufbau „A+B“ der Spec: die Ausgabe laeuft nach unten, nur das
# aktive Panel wird an seiner Stelle neu gezeichnet — kein Vollbild, Logs bleiben stehen.
#
# Ohne Terminal (Pipe, CI, Installer-Probe ohne PTY): keine Farben, keine Abfragen — jede Abfrage
# nimmt ihren Default. Ohne UTF-8-Locale: ASCII-Rahmen, weil bash dann Bytes statt Zeichen zaehlt.
# bash 3.2 (macOS): kein declare -A, read -t nur ganze Sekunden, sed ohne \x-Escapes.

_TUI_LIB_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
_TUI_ESC="$(printf '\033')"
TUI_TTY=0
TUI_COLOR=none
TUI_UTF8=1
TUI_WIDTH=78
TUI_LAST_DRAWN=0

tui_init() {
  TUI_TTY=0
  if [ -z "${TUI_FORCE_PLAIN:-}" ] && [ -t 0 ] && [ -t 1 ]; then TUI_TTY=1; fi
  TUI_COLOR=none
  if [ -n "${TUI_FORCE_COLOR:-}" ]; then
    TUI_COLOR="$TUI_FORCE_COLOR"
  elif [ "$TUI_TTY" = 1 ] && [ -z "${NO_COLOR:-}" ]; then
    case "${COLORTERM:-}" in truecolor|24bit) TUI_COLOR=24 ;; *) TUI_COLOR=256 ;; esac
  fi
  case "${LC_ALL:-${LC_CTYPE:-${LANG:-}}}" in
    *UTF-8*|*utf-8*|*UTF8*|*utf8*) TUI_UTF8=1 ;;
    *) TUI_UTF8=0 ;;
  esac
  if [ "$TUI_UTF8" = 1 ]; then
    T_H='─'; T_V='│'; T_TL='┌'; T_TR='┐'; T_BL='└'; T_BR='┘'; T_OK='✓'; T_BAD='✗'
    T_CUR='●'; T_TODO='○'; T_PTR='▸'; T_CUT='…'; T_SPIN='⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏'; T_WARN='!'
  else
    T_H='-'; T_V='|'; T_TL='+'; T_TR='+'; T_BL='+'; T_BR='+'; T_OK='v'; T_BAD='x'
    T_CUR='*'; T_TODO='o'; T_PTR='>'; T_CUT='~'; T_SPIN='|/-\'; T_WARN='!'
  fi
  C_RESET=''; C_BOLD=''; C_DIM=''; C_AMBER=''; C_GREEN=''; C_RED=''; C_GREY=''; C_FRAME=''; C_TEXT=''
  case "$TUI_COLOR" in
    24)
      C_RESET=$'\033[0m'; C_BOLD=$'\033[1m'; C_DIM=$'\033[2m'
      C_AMBER=$'\033[38;2;222;150;52m'; C_GREEN=$'\033[38;2;111;208;140m'; C_RED=$'\033[38;2;240;119;108m'
      C_GREY=$'\033[38;2;150;144;136m'; C_FRAME=$'\033[38;2;120;96;64m'; C_TEXT=$'\033[38;2;232;227;218m' ;;
    256)
      C_RESET=$'\033[0m'; C_BOLD=$'\033[1m'; C_DIM=$'\033[2m'
      C_AMBER=$'\033[38;5;172m'; C_GREEN=$'\033[38;5;114m'; C_RED=$'\033[38;5;203m'
      C_GREY=$'\033[38;5;245m'; C_FRAME=$'\033[38;5;94m'; C_TEXT=$'\033[38;5;254m' ;;
  esac
}

# --- Hilfen -----------------------------------------------------------------------------------------

_tui_plain() { printf '%s' "$1" | sed "s/${_TUI_ESC}\[[0-9;?]*[A-Za-z]//g"; }
_tui_len() { local s; s="$(_tui_plain "$1")"; printf '%s' "${#s}"; }
_tui_repeat() { local s="" i=0; while [ "$i" -lt "$2" ]; do s="$s$1"; i=$((i + 1)); done; printf '%s' "$s"; }
# TEXT auf BREITE Zeichen auffuellen — printf '%-Ns' zaehlt Bytes, „ä“ verschoebe die Spalte
_tui_pad() { local n; n="$(_tui_len "$1")"; printf '%s' "$1"; [ "$n" -lt "$2" ] && _tui_repeat ' ' $(($2 - n)); return 0; }

# Text aus den Sprachdateien, falls geladen (scripts/lib/i18n.sh), sonst der englische Default
_tui_msg() {
  local key="$1" def="$2" name
  shift 2
  name="M_$key"
  if declare -F msg >/dev/null 2>&1 && [ -n "${!name-}" ]; then msg "$key" "$@"; else
    # shellcheck disable=SC2059
    printf -- "$def" "$@"
  fi
}

_tui_cursor() { [ "$TUI_TTY" = 1 ] && { [ "$1" = off ] && printf '\033[?25l' || printf '\033[?25h'; }; return 0; }

# Loescht die letzten N gezeichneten Zeilen (fuer das Neuzeichnen an Ort und Stelle)
tui_clear_lines() { [ "$TUI_TTY" = 1 ] && [ "${1:-0}" -gt 0 ] && printf '\033[%dA\033[J' "$1"; return 0; }

# --- Bausteine --------------------------------------------------------------------------------------

# tui_box TITEL [ZEILE…] — Rahmen in fester Breite; zu lange Zeilen werden gekuerzt, nicht umbrochen
tui_box() {
  local title="$1" inner=$((TUI_WIDTH - 4)) len line fill
  shift
  len="$(_tui_len "$title")"
  fill=$((TUI_WIDTH - 5 - len)); [ "$fill" -lt 0 ] && fill=0
  printf '%s%s%s %s%s%s%s %s%s%s%s\n' "$C_FRAME" "$T_TL" "$T_H" "$C_RESET" "$C_BOLD" "$C_AMBER" "$title" \
    "$C_RESET" "$C_FRAME" "$(_tui_repeat "$T_H" "$fill")" "$T_TR$C_RESET"
  for line in "$@"; do
    len="$(_tui_len "$line")"
    if [ "$len" -gt "$inner" ]; then
      line="$(_tui_plain "$line")"
      line="${line:0:$((inner - 1))}$T_CUT"
      len="$inner"
    fi
    printf '%s%s%s %s%*s %s%s%s\n' "$C_FRAME" "$T_V" "$C_RESET" "$line" $((inner - len)) "" "$C_FRAME" "$T_V" "$C_RESET"
  done
  printf '%s%s%s%s%s\n' "$C_FRAME" "$T_BL" "$(_tui_repeat "$T_H" $((TUI_WIDTH - 2)))" "$T_BR" "$C_RESET"
  TUI_LAST_DRAWN=$(( $# + 2 ))
}

# tui_rail AKTUELL NAME… — Schrittleiste; zu breit ⇒ erledigte Schritte nur noch als ✓
tui_rail() {
  local cur="$1" out mode
  shift
  for mode in full collapsed; do
    out="$(_tui_rail_build "$cur" "$mode" "$@")"
    [ "$(_tui_len "$out")" -le $((TUI_WIDTH)) ] && break
  done
  printf '  %s\n' "$out"
}
_tui_rail_build() {
  local cur="$1" mode="$2" i=0 out="" name sep=" ${C_FRAME}${T_H}${C_RESET} "
  shift 2
  for name in "$@"; do
    if [ "$i" -lt "$cur" ]; then
      if [ "$mode" = collapsed ]; then out="$out${C_GREEN}${T_OK}${C_RESET}"; [ $((i + 1)) -eq "$cur" ] && out="$out$sep"; i=$((i + 1)); continue; fi
      out="$out${C_GREEN}${T_OK}${C_RESET} ${C_TEXT}${name}${C_RESET}"
    elif [ "$i" -eq "$cur" ]; then
      out="$out${C_BOLD}${C_AMBER}${T_CUR} ${name}${C_RESET}"
    else
      out="$out${C_GREY}${T_TODO} ${name}${C_RESET}"
    fi
    i=$((i + 1))
    [ "$i" -lt "$#" ] && out="$out$sep"
  done
  printf '%s' "$out"
}

# tui_done LABEL DETAIL [DAUER] — ein erledigter Schritt als Einzeiler
tui_done() {
  printf '  %s%s%s %s%s%s %s%s%s%s\n' "$C_GREEN" "$T_OK" "$C_RESET" "$C_TEXT" "$(_tui_pad "$1" 10)" "$C_RESET" \
    "$C_GREY" "${2:-}" "${3:+   $3}" "$C_RESET"
}

# tui_header INFOZEILE… — Erdmaennchen links, Infoblock rechts (ab Zeile 4), darunter der Schriftzug
tui_header() {
  local name i=0 row info
  # shellcheck source=art.generated.sh
  [ -n "${ART_WIDTH:-}" ] || . "$_TUI_LIB_DIR/art.generated.sh"
  case "$TUI_COLOR" in 24) name=ART_ROWS_24 ;; 256) name=ART_ROWS_256 ;; *) name=ART_ROWS_PLAIN ;; esac
  eval "set -- \"\$@\"; _tui_rows=(\"\${${name}[@]}\")"
  printf '\n'
  for row in "${_tui_rows[@]}"; do
    info=""
    if [ "$i" -ge 3 ] && [ $((i - 3)) -lt $# ]; then eval "info=\"\${$((i - 2))}\""; fi
    printf ' %s  %s\n' "$row" "$info"
    i=$((i + 1))
  done
  printf '\n'
  for row in "${ART_BANNER[@]}"; do printf ' %s%s%s%s\n' "$C_BOLD" "$C_AMBER" "$row" "$C_RESET"; done
  printf '\n'
}

# --- Abfragen ---------------------------------------------------------------------------------------

# tui_select VAR TITEL DEFAULT "Text|Hinweis"… — Rueckgabe: 0 gewaehlt (Index in VAR), 2 zurueck, 3 beenden
tui_select() {
  local var="$1" title="$2" sel="$3" n key rest opt label hint i width w lines
  shift 3
  n=$#
  if [ "$TUI_TTY" != 1 ]; then
    printf -v "$var" '%s' "$sel"
    eval "opt=\"\${$((sel + 1))}\""
    printf '  %s: %s\n' "$title" "${opt%%|*}"
    return 0
  fi
  width=0
  for opt in "$@"; do w="$(_tui_len "${opt%%|*}")"; [ "$w" -gt "$width" ] && width="$w"; done
  _tui_cursor off
  TUI_LAST_DRAWN=0
  while :; do
    tui_clear_lines "$TUI_LAST_DRAWN"
    lines=(); i=0
    for opt in "$@"; do
      label="${opt%%|*}"; hint=""; [ "$opt" != "$label" ] && hint="${opt#*|}"
      w="$(_tui_len "$label")"
      if [ "$i" -eq "$sel" ]; then
        lines[${#lines[@]}]="$(printf '%s%s%s %s%s%s%*s  %s%s%s' "$C_AMBER" "$T_PTR" "$C_RESET" "$C_BOLD" "$label" "$C_RESET" $((width - w)) "" "$C_GREY" "$hint" "$C_RESET")"
      else
        lines[${#lines[@]}]="$(printf '  %s%*s  %s%s%s' "$label" $((width - w)) "" "$C_GREY" "$hint" "$C_RESET")"
      fi
      i=$((i + 1))
    done
    lines[${#lines[@]}]=""
    lines[${#lines[@]}]="${C_GREY}$(_tui_msg keys_select '↑/↓ choose · Enter confirm · b back · q quit')${C_RESET}"
    tui_box "$title" "${lines[@]}"
    key=""; IFS= read -rsn1 key || key=q
    case "$key" in
      "$_TUI_ESC")
        rest=""; IFS= read -rsn2 -t 1 rest
        case "$rest" in '[A'|'OA') sel=$(( (sel - 1 + n) % n )) ;; '[B'|'OB') sel=$(( (sel + 1) % n )) ;; esac ;;
      k) sel=$(( (sel - 1 + n) % n )) ;;
      j) sel=$(( (sel + 1) % n )) ;;
      [1-9]) if [ "$key" -le "$n" ]; then sel=$((key - 1)); break; fi ;;
      "") break ;;
      b) _tui_cursor on; return 2 ;;
      q) _tui_cursor on; return 3 ;;
    esac
  done
  # Das Panel verschwindet; den erledigten Schritt schreibt der Aufrufer als Einzeiler (tui_done)
  tui_clear_lines "$TUI_LAST_DRAWN"
  TUI_LAST_DRAWN=0
  _tui_cursor on
  printf -v "$var" '%s' "$sel"
  return 0
}

# tui_checklist VAR TITEL "on|off:schluessel:Text[:Hinweis]"… — VAR = eingeschaltete Schluessel,
# durch Leerzeichen getrennt. Rueckgabe wie tui_select.
tui_checklist() {
  local var="$1" title="$2" n cur=0 key rest item i state lines keyname label hint result mark
  shift 2
  n=$#
  local states=""
  for item in "$@"; do states="$states ${item%%:*}"; done
  set -- "$@"
  if [ "$TUI_TTY" = 1 ]; then
    _tui_cursor off
    TUI_LAST_DRAWN=0
    while :; do
      tui_clear_lines "$TUI_LAST_DRAWN"
      lines=(); i=0
      for item in "$@"; do
        state="$(_tui_word "$states" "$i")"; rest="${item#*:}"; keyname="${rest%%:*}"; rest="${rest#*:}"
        label="${rest%%:*}"; hint=""; [ "$rest" != "$label" ] && hint="${rest#*:}"
        if [ "$state" = on ]; then mark="${C_AMBER}${C_BOLD}[x]${C_RESET}"; else mark="${C_GREY}[ ]${C_RESET}"; fi
        if [ "$i" -eq "$cur" ]; then
          lines[${#lines[@]}]="$(printf '%s%s%s %s %s%s%s %s%s%s' "$C_AMBER" "$T_PTR" "$C_RESET" "$mark" "$C_BOLD" "$(_tui_pad "$label" 26)" "$C_RESET" "$C_GREY" "$hint" "$C_RESET")"
        else
          lines[${#lines[@]}]="$(printf '  %s %s %s%s%s' "$mark" "$(_tui_pad "$label" 26)" "$C_GREY" "$hint" "$C_RESET")"
        fi
        i=$((i + 1))
      done
      lines[${#lines[@]}]=""
      lines[${#lines[@]}]="${C_GREY}$(_tui_msg keys_checklist '↑/↓ move · Space toggle · Enter next · b back')${C_RESET}"
      tui_box "$title" "${lines[@]}"
      key=""; IFS= read -rsn1 key || key=q
      case "$key" in
        "$_TUI_ESC")
          rest=""; IFS= read -rsn2 -t 1 rest
          case "$rest" in '[A'|'OA') cur=$(( (cur - 1 + n) % n )) ;; '[B'|'OB') cur=$(( (cur + 1) % n )) ;; esac ;;
        k) cur=$(( (cur - 1 + n) % n )) ;;
        j) cur=$(( (cur + 1) % n )) ;;
        " ") states="$(_tui_toggle "$states" "$cur")" ;;
        "") break ;;
        b) _tui_cursor on; return 2 ;;
        q) _tui_cursor on; return 3 ;;
      esac
    done
    tui_clear_lines "$TUI_LAST_DRAWN"
    TUI_LAST_DRAWN=0
    _tui_cursor on
  fi
  result=""; i=0
  for item in "$@"; do
    rest="${item#*:}"; keyname="${rest%%:*}"
    [ "$(_tui_word "$states" "$i")" = on ] && result="${result:+$result }$keyname"
    i=$((i + 1))
  done
  [ "$TUI_TTY" = 1 ] || printf '  %s: %s\n' "$title" "${result:--}"
  printf -v "$var" '%s' "$result"
  return 0
}
_tui_word() { local i=0 w; for w in $1; do [ "$i" -eq "$2" ] && { printf '%s' "$w"; return; }; i=$((i + 1)); done; }
_tui_toggle() {
  local i=0 w out=""
  for w in $1; do
    if [ "$i" -eq "$2" ]; then [ "$w" = on ] && w=off || w=on; fi
    out="$out $w"; i=$((i + 1))
  done
  printf '%s' "$out"
}

# tui_confirm FRAGE y|n — 0 = ja, 1 = nein; ohne Terminal der Default
tui_confirm() {
  local question="$1" def="${2:-y}" answer hint
  if [ "$def" = y ]; then hint="$(_tui_msg confirm_default_yes '[Y/n]')"; else hint="$(_tui_msg confirm_default_no '[y/N]')"; fi
  if [ "$TUI_TTY" != 1 ]; then
    printf '  %s %s %s\n' "$question" "$hint" "$def"
    [ "$def" = y ]; return
  fi
  printf '  %s%s%s %s ' "$C_BOLD" "$question" "$C_RESET" "$hint"
  IFS= read -r answer || answer=""
  case "$answer" in
    "") [ "$def" = y ] ;;
    y|Y|j|J|yes|ja) return 0 ;;
    *) return 1 ;;
  esac
}

# tui_input VAR FRAGE DEFAULT — freie Eingabe; leer oder ohne Terminal ⇒ Default
tui_input() {
  local var="$1" question="$2" def="$3" answer=""
  if [ "$TUI_TTY" = 1 ]; then
    printf '  %s%s%s [%s]: ' "$C_BOLD" "$question" "$C_RESET" "$def"
    IFS= read -r answer || answer=""
  else
    printf '  %s: %s\n' "$question" "$def"
  fi
  printf -v "$var" '%s' "${answer:-$def}"
}

# --- Befehle ----------------------------------------------------------------------------------------

# tui_run TITEL LOGDATEI BEFEHL… — Ausgabe ins Log; im Terminal Spinner, Dauer und die jeweils
# letzte Log-Zeile gedimmt. Bei Fehler: die letzten 15 Zeilen und der Pfad zum Log. Rueckgabe = Exit.
tui_run() {
  local title="$1" log="$2" start=$SECONDS rc pid i=0 last frame nframes dur room
  shift 2
  room=$((TUI_WIDTH - 16 - ${#title})); [ "$room" -lt 0 ] && room=0   # ${x:0:-n} bricht bash ab
  : > "$log"
  if [ "$TUI_TTY" = 1 ]; then
    "$@" > "$log" 2>&1 < /dev/null &
    pid=$!
    nframes=${#T_SPIN}
    _tui_cursor off
    while kill -0 "$pid" 2>/dev/null; do
      last="$(tail -n 1 "$log" 2>/dev/null | tr -d '\r' | sed "s/${_TUI_ESC}\[[0-9;?]*[A-Za-z]//g")"
      last="${last:0:$room}"
      frame="${T_SPIN:$((i % nframes)):1}"
      dur=$((SECONDS - start))
      printf '\r\033[K  %s%s%s %s  %s%d:%02d%s  %s%s%s' "$C_AMBER" "$frame" "$C_RESET" "$title" \
        "$C_GREY" $((dur / 60)) $((dur % 60)) "$C_RESET" "$C_DIM" "$last" "$C_RESET"
      sleep 0.2
      i=$((i + 1))
    done
    wait "$pid"; rc=$?
    printf '\r\033[K'
    _tui_cursor on
  else
    printf '  %s %s\n' "$T_TODO" "$title"
    "$@" > "$log" 2>&1 < /dev/null
    rc=$?
  fi
  dur=$((SECONDS - start))
  if [ "$rc" -eq 0 ]; then
    printf '  %s%s%s %s  %s%d:%02d%s\n' "$C_GREEN" "$T_OK" "$C_RESET" "$title" "$C_GREY" $((dur / 60)) $((dur % 60)) "$C_RESET"
  else
    printf '  %s%s%s %s  %s%d:%02d%s\n' "$C_RED" "$T_BAD" "$C_RESET" "$title" "$C_GREY" $((dur / 60)) $((dur % 60)) "$C_RESET"
    printf '    %s%s%s\n' "$C_RED" "$(_tui_msg run_failed '%s failed (exit %s) — last lines:' "$title" "$rc")" "$C_RESET"
    tail -n 15 "$log" | sed 's/^/      /'
    printf '    %s\n' "$(_tui_msg run_log 'Full log: %s' "$log")"
  fi
  return "$rc"
}
