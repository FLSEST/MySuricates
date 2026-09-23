# English — the complete base. Every other language may leave keys out; they stay English.
# Format: printf (%s = value, %% = percent sign). Keys: lowercase, digits, underscore.

# Steps
M_step_welcome="Welcome"
M_step_mode="Mode"
M_step_checks="Checks"
M_step_modules="Modules"
M_step_ai="AI"
M_step_ports="Ports"
M_step_build="Build"
M_step_done="Done"
M_step_of="Step %s of %s"

# Header
M_header_setup="Setup"
M_header_language="Language"
M_header_language_value="English  (--lang de for Deutsch)"
M_header_ai="AI"
M_header_ports="Ports"
M_header_time="Time"
M_header_time_value="about %s min"

# Keys
M_keys_select="↑/↓ choose · Enter confirm · b back · q quit"
M_keys_checklist="↑/↓ move · Space toggle · Enter next · b back"
M_keys_confirm="Enter = %s"
M_recommended="recommended"
M_confirm_default_yes="[Y/n]"
M_confirm_default_no="[y/N]"
M_yes="yes"
M_no="no"

# Mode
M_mode_title="How do you want to run MySuricates?"
M_mode_container="One instance — in a container"
M_mode_container_hint="only Docker needed"
M_mode_host="One instance — on this machine"
M_mode_host_hint="installs Java and Node if needed"
M_mode_staged="Staged — Dev · Test · Prod"
M_mode_staged_hint="Dev builds, Test checks, Prod runs"

# Running commands
M_run_failed="%s failed (exit %s) — last lines:"
M_run_log="Full log: %s"

# Welcome / re-run
M_welcome_intro="Sets up MySuricates on this machine in %s steps. b goes back, q quits."
M_rerun_title="There is an existing setup"
M_rerun_change="Change settings of %s"
M_rerun_change_hint="port, modules, AI provider"
M_rerun_fresh="Set up again"
M_rerun_fresh_hint="rewrites the configuration"
M_rerun_quit="Quit"
M_quit="Setup cancelled."

# Checks
M_checks_system="%s · package manager: %s"
M_checks_all_ok="All prerequisites present"
M_checks_plan="Missing or too old — will run:"
M_checks_install_q="Install the missing tools now? (system packages need sudo)"
M_checks_manual="Install by hand: %s"
M_checks_still_missing="Still missing: %s — install it and run scripts/setup.sh again."
M_checks_docker_group="Docker works for this run through 'sg docker'; log out and back in once so it works everywhere."
M_checks_docker_down="Docker is installed but not running — start it (Linux: sudo systemctl start docker, macOS: open Docker Desktop)."
M_checks_summary_ok="Everything present — run scripts/setup.sh in a terminal, or with --yes."
M_checks_summary_missing="Missing: %s — run scripts/setup.sh in a terminal, or with --yes, to install it."
M_status_ok="ok"
M_status_old="too old"
M_status_missing="missing"

# Modules
M_modules_title="Which features should this instance have?"
M_modules_standard="Standard"
M_modules_standard_hint="core + dashboard report — smallest surface"
M_modules_research="Research"
M_modules_research_hint="+ chains, assistant, insights, memory, audit"
M_modules_full="Full"
M_modules_full_hint="everything, incl. webhooks, remote access, terminal"
M_modules_custom="Custom…"
M_modules_custom_done="custom selection"
M_modules_custom_hint="pick single modules and hardening"
M_modules_pick_title="Modules"
M_modules_hardening_title="Hardening"
M_modules_ui_editable_q="Allow switching modules later in the UI? (anyone with the API key could then turn on network and host features)"
M_modules_staged="Dev: all modules · Test and Prod: standard, sealed workflows only when unattended"

# AI
M_ai_title="AI provider"
M_ai_logged_in="installed, logged in"
M_ai_logged_out="installed, not logged in"
M_ai_missing="not installed"
M_ai_choose="Which provider should workflows use by default?"
M_ai_none="No AI CLI found. MySuricates starts anyway; AI blocks need one of these:"
M_ai_install="%s — install: %s   log in: %s"
M_ai_login_hint="Log in to %s before the first AI workflow: %s"
M_ai_access_q="What of this machine's AI providers goes into the container?"
M_ai_access_login="Just the login|programs plus the logins (recommended)"
M_ai_access_programs="Just the programs|every instance logs in on its own"
M_ai_access_all="Everything, as before|the whole provider directories, writable"
M_ai_providers_q="Which providers go into the container?"
M_ai_access_done="AI in the container"
M_ai_token_q="Claude needs its own token in a container (claude setup-token, valid for a year). Create it now?"
M_ai_token_paste="Paste the token (not shown)"
M_ai_token_saved="Token saved in %s (readable only by you)"
M_ai_token_hint="Claude in a container: run claude setup-token once, then put CLAUDE_CODE_OAUTH_TOKEN=… into %s (chmod 600)"

# Ports and folders
M_ports_title="Ports and folders"
M_ports_q="Port for %s (interface and API — one port)"
M_ports_busy="Port %s is in use — taking %s"
M_projects_q="Folder with your projects (mounted into the container)"
M_socket_q="Allow shell blocks with container_image? (mounts the Docker socket — full control over Docker)"

# Build and done
M_build_q="Build and start now?"
M_build_run="Build and start %s"
M_build_failed="Starting %s failed — details: %s"
M_dry_run="(dry run) would run: %s"
M_kept_existing="Keeping the existing %s (--reconfigure rewrites it)"
M_done_open="→  %s"
M_done_network="→  %s   (from the network)"
M_done_network_unknown="→  http://<address of this machine>:%s   (from the network; address not found)"
M_done_network_vpn="From the network only with a VPN in front (e.g. WireGuard, Tailscale) — never open to the internet."
M_done_key="API key for the login: %s"
M_done_manage="Start / stop:"
M_done_change="Change:"
M_done_not_started="configured, not started yet"
M_done_key_cmd="Full API key:"

# Help (--help) and argument errors
M_help="MySuricates setup — from a bare machine to the login in one pass.

  ./scripts/setup.sh                 in a terminal: guided, step by step
  ./scripts/setup.sh --yes           no questions: defaults, install missing tools, start
  ./scripts/setup.sh --check         only check and show the plan (exit 1 if something is missing)

  --mode one|staged            one instance (default) or Dev · Test · Prod
  --runtime container|host     in a container (default, needs only Docker) or on this machine
  --preset core|research|full  modules (default core); --modules a,b adds single modules
  --provider claude|codex|pi   default AI provider (default: the first one logged in)
  --port N                     port of the instance (default 9090, busy ports are skipped)
  --bind ADDR                  address (default 127.0.0.1; 0.0.0.0 = from the network, VPN in front)
  --projects DIR               projects folder for the container (default: the folder above the clone)
  --docker-socket              container: allow shell blocks with container_image (full Docker control)
  --ai-from-host MODE          container: what of the AI providers goes in — login (default), programs, all
  --ai-providers LIST          container: only these providers, e.g. claude,codex (default: all)
  --name NAME                  name of the instance (default main)
  --lang de                    Deutsch (default English)
  --reconfigure                rewrite an existing instance file
  --preset core --force        in an existing setup change only the preset
  --dry-run                    install, build and start nothing — only show and write the files
  --show-key                   show the full API key at the end (default: masked)
  --from-source                for contributors: source setup with Vite (docs/entwicklung.md)
  --install / --start          older spelling: install without questions / start at the end

Without a terminal and without --yes/--install/--start it only checks. Every instance is a file
deploy/instances/<name>.env; start, stop and inspect it with scripts/instance.sh.
Everything else (hardening, ui-editable, AUTOMATION_… settings) goes into that file."
M_err_option="Unknown option: %s (see --help)"
M_err_preset="Unknown preset: %s (allowed: %s)"
M_err_force="--force only works together with --preset <%s> (see --help)"
M_err_mode="--mode one|staged, not '%s'"
M_err_runtime="--runtime container|host, not '%s'"
M_err_name="--name only from a-z, 0-9 and -"
M_err_bind="--bind needs an IP address"
M_err_port="--port needs a number"
M_err_provider="Unknown provider: %s (known: %s)"
M_err_ai_from_host="Unknown value for --ai-from-host: %s (programs|login|all)"
M_err_ai_providers="Unknown provider in --ai-providers: %s (known: %s)"
M_err_module="Unknown module: %s"
