# Section — Self-Evolution Guide

You are reading the CLAUDE.md for **Section**, a self-evolving autonomous development platform built in Babashka. When you receive an issue against this repo, you are modifying Section itself.

## Architecture

Section is organized by role, named after characters from La Femme Nikita (1997):

- `birkoff.bb` — Entry point. Orchestrates the full cycle: recovery → capability check → dispatch → housekeeping.
- `src/section/config.clj` — Central configuration. Secrets from macOS Keychain. No hardcoded secrets ever.
- `src/section/operations.clj` — **Operations**: scheduler, thread pool, lock management, mission dispatch.
- `src/section/comm.clj` — **Comm**: GitHub polling (gh CLI), PR creation, issue comments, email bridge.
- `src/section/briefing.clj` — **Briefing**: assembles prompts for operatives with full context.
- `src/section/operative.clj` — **Operative**: runs `claude -p`, handles the full mission lifecycle.
- `src/section/walter.clj` — **Walter**: capability registry. Reads `walter/capabilities.edn`.
- `src/section/madeline.clj` — **Madeline**: memory system. Reads/writes `madeline/memory.edn`.
- `src/section/oversight.clj` — **Oversight**: self-healing, recovery, housekeeping, status reports.
- `src/section/registry.clj` — **Registry**: repo list + relationships. CLI dispatcher for `bb repo`.
- `src/section/util.clj` — Shared utilities. `atomic-spit` is used by anything that persists shared state.
- `src/section/perch.clj` — **The Perch**: httpkit web dashboard on `:8080`. Read-only view of Madeline's files + two action endpoints. Lives in its own launchd service (`com.section.perch.plist`).

## Conventions

- **Language**: Babashka (Clojure). Source files use `.clj` extension in `src/`.
- **No compilation**: Everything is interpreted. Changes take effect on next `bb run`.
- **Config via env vars**: All tunables come from environment variables with `SECTION_` prefix, with sensible defaults.
- **Secrets via Keychain**: Never store secrets in files. Use `security add-generic-password -a section -s SERVICE -w VALUE`.
- **Tests**: Run `bb test`. All new features must include tests in `test/section/sim_test.clj`.
- **Capabilities**: To add a new tool, add an entry to `walter/capabilities.edn`. Walter handles the rest.
- **Memory**: Cross-run state goes through Madeline (`section.madeline` namespace). Don't use ad-hoc files.

## Safety Rules

1. **Always run `bb test` before committing.**
2. **Never modify birkoff.bb without also testing the change.** A broken entry point kills Section.
3. **Never store secrets in source files.**
4. **Never remove the oversight recovery sequence.** It's what brings Section back after failures.
5. **Backward compatibility**: If you change config keys or memory format, migrate existing data.
6. **Lock discipline**: Always unlock in a `finally` block. Leaked locks block missions.
7. **Atomic writes for shared state**: Anything persisted by Madeline, the registry, or Birkoff's heartbeat must use `section.util/atomic-spit` — The Perch reads these files concurrently and cannot tolerate partial writes.

## Adding a New Capability

1. Add an entry to `walter/capabilities.edn` with `:check`, `:install`, and `:description`.
2. If the capability needs integration code, add it to the relevant namespace.
3. Update the briefing system prompt in `briefing.clj` if operatives need to know about it.
4. Add a test.

## Adding a New Feature to Section

1. Identify which namespace it belongs to (or create a new one if it's genuinely new).
2. Implement with tests.
3. Wire it into `birkoff.bb` if it needs to run on every cycle.
4. Update this CLAUDE.md if the architecture changed.

## Running

```bash
bb run          # Single execution cycle
bb perch [port] # Start The Perch web dashboard
bb test         # Run sims (tests)
bb status       # Terminal status report
bb walter       # Capability check
bb repo ...     # Manage the repo registry
bb housekeeping # Clean locks and old logs
bb install      # Install both launchd plists (Birkoff + Perch)
bb uninstall    # Remove both launchd plists
```
