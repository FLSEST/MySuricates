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
M_yes="yes"
M_no="no"

# Mode
M_mode_title="How do you want to run MySuricates?"
M_mode_container="One instance — in a container"
M_mode_container_hint="only Docker needed"
M_mode_host="One instance — on this machine"
M_mode_host_hint="installs Java and Node if needed"
M_mode_staged="Staged — Dev · Test · Prod"
M_mode_staged_hint="plan and build in Dev, check in Test, run in Prod"

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

# Ports and folders
M_ports_title="Ports and folders"
M_ports_q="Port for %s"
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
M_done_key="API key for the login: %s"
M_done_manage="Start / stop:"
M_done_change="Change:      "
M_done_not_started="configured, not started yet"
M_done_key_cmd="Full API key:"
