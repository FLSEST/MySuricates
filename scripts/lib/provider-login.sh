#!/usr/bin/env bash
# Login commands of the three AI CLIs, for every container way in which an instance logs in on its
# own: scripts/tryout.sh, and scripts/instance.sh / scripts/staging.sh with
# MYSURICATES_AI_FROM_HOST=programs (262948a4). bash 3.2 (macOS).

# provider_login_argv PROVIDER — sets PROVIDER_LOGIN_ARGV to the login command and prints what happens
# next (German, like the scripts that call it). Returns 2 for an unknown provider.
provider_login_argv() {
  case "$1" in
    claude) PROVIDER_LOGIN_ARGV=(claude); echo "Claude startet — beim ersten Start fuehrt es durch den Login (sonst /login)." ;;
    codex)  PROVIDER_LOGIN_ARGV=(codex login --device-auth) ;;
    pi)     PROVIDER_LOGIN_ARGV=(pi); echo "pi startet — dort /login." ;;
    *)      return 2 ;;
  esac
}
