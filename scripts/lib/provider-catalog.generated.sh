#!/usr/bin/env bash
# generiert von ProviderCatalogTest — nicht von Hand ändern
#
# Quelle: AiProvider#setup() jedes KI-Anbieters im Backend. Der Installer nennt damit alle
# Anbieter gleichrangig, ohne einen davon fest zu verdrahten (2255f2ee).
# Neu erzeugen:
#   cd ai-automation-backend && ./mvnw test -Dtest=ProviderCatalogTest -Dprovider.catalog.write=true
#
# Reine Daten, keine Funktionen; Skalare statt assoziativer Arrays (bash 3.2, macOS).
# login_files: relativ zu $HOME, durch Leerzeichen getrennt; "datei:muster" heisst, die Datei
# muss das Muster enthalten. Die erste zutreffende zaehlt als angemeldet.
# login_env: Umgebungsvariablen, durch Leerzeichen getrennt; eine gesetzte, nicht leere
# zaehlt ebenfalls als angemeldet (der Wert wird nie ausgegeben).

PROVIDER_IDS=(
  claude
  codex
  pi
)

PROVIDER__claude__cli="claude"
PROVIDER__claude__install="curl -fsSL https://claude.ai/install.sh | bash   (or: npm install -g @anthropic-ai/claude-code)"
PROVIDER__claude__login="claude auth login"
PROVIDER__claude__login_files=".claude/.credentials.json .claude.json:oauthAccount"
PROVIDER__claude__login_env="CLAUDE_CODE_OAUTH_TOKEN ANTHROPIC_API_KEY"

PROVIDER__codex__cli="codex"
PROVIDER__codex__install="npm install -g @openai/codex"
PROVIDER__codex__login="codex login"
PROVIDER__codex__login_files=".codex/auth.json"
PROVIDER__codex__login_env="OPENAI_API_KEY CODEX_API_KEY"

PROVIDER__pi__cli="pi"
PROVIDER__pi__install="npm install -g @earendil-works/pi-coding-agent"
PROVIDER__pi__login="pi, then /login   (or an API key as environment variable, see pi --help)"
PROVIDER__pi__login_files=".pi/agent/auth.json:\""
PROVIDER__pi__login_env="ANTHROPIC_API_KEY ANTHROPIC_OAUTH_TOKEN OPENAI_API_KEY GEMINI_API_KEY OPENROUTER_API_KEY"
