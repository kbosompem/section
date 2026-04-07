# Section

> *"There are no rules in Section — only missions."*

**Section** is a self-evolving autonomous development platform that runs on a single Mac Mini. File a GitHub issue, and an operative handles it — builds, tests, documents, and PRs the solution. Need a new capability? File an issue against Section itself.

Named after the covert organization in [*La Femme Nikita*](https://en.wikipedia.org/wiki/La_Femme_Nikita_(TV_series)) (1997–2001), because like Section One, it operates autonomously, maintains its own infrastructure, and the operatives never sleep.

## How It Works

1. **Birkoff** wakes up every 5 minutes (via macOS `launchd`)
2. **Oversight** runs recovery checks — heals broken capabilities, cleans stale locks
3. **Comm** polls your GitHub repos for issues labeled `section` and assigned to the bot
4. **Operations** dispatches missions to a pool of **Operatives** (concurrent `claude -p` sessions)
5. Each operative gets a **Briefing** — the issue, repo context, capability manifest, and memory from prior attempts
6. The operative implements the solution, commits, pushes, and opens a PR
7. **Madeline** records what happened for next time

## Terminology

| Term | Meaning |
|------|---------|
| **Section** | The platform — this repo |
| **Birkoff** | The orchestrator (nerve center) |
| **Operatives** | Claude Code worker sessions |
| **Missions** | GitHub issues to be worked on |
| **Briefing** | The assembled prompt + context for an operative |
| **Walter** | Capability registry — he builds the gadgets |
| **Madeline** | Memory system — she knows everything |
| **Operations** | Scheduler and dispatcher — directs from The Perch |
| **The Perch** | Status view / logs |
| **Oversight** | Self-healing watchdog |
| **Comm** | GitHub / email communication layer |
| **Abeyance** | Issues queued and waiting for dispatch |
| **Sim** | Tests — run before any mission is considered complete |
| **Egress** | PR submission — the exit point of a mission |
| **Housekeeping** | Lock cleanup, log pruning, branch maintenance |

## Quick Start

### Prerequisites

- macOS (Apple Silicon Mac Mini recommended)
- [Babashka](https://github.com/babashka/babashka) (`brew install borkdude/brew/babashka`)
- [GitHub CLI](https://cli.github.com/) (`brew install gh && gh auth login`)
- [Claude Code](https://claude.ai/product/claude-code) (`npm install -g @anthropic-ai/claude-code`)

### Setup

```bash
# 1. Clone
git clone git@github.com:kbosompem/section.git ~/Sources/KB/section
cd ~/Sources/KB/section

# 2. Store your Anthropic API key in macOS Keychain
security add-generic-password -a section -s anthropic-api-key -w "YOUR_KEY_HERE"

# 3. Register the repos you want Section to monitor
bb repo add kbosompem/section --description "The forge itself" --role "orchestrator"
bb repo add kbosompem/my-app  --description "Main product"     --role "frontend"
bb repo add kbosompem/api     --description "REST API"         --role "backend"

# 4. Optionally describe how they relate to each other
bb repo link kbosompem/my-app kbosompem/api --type depends-on --note "Uses /v1 endpoints"

# 5. Test it
bb walter    # Check capabilities
bb status    # View Section status
bb test      # Run sims
bb run       # Execute one cycle

# 6. Install for continuous operation
bb install   # Installs launchd plist — runs every 5 minutes, restarts on crash
```

### Managing Repos

The **repo registry** (managed by Madeline, stored in `madeline/repos.edn`) is how Section knows what to monitor and how repos relate to each other. Relationship context is automatically included in every mission briefing so operatives understand the broader system.

```bash
bb repo add OWNER/NAME [--description "..."] [--role "..."]
bb repo list                                  # Show all registered repos
bb repo show OWNER/NAME                       # Details + relationships (both directions)
bb repo remove OWNER/NAME                     # Stops monitoring and cleans incoming links
bb repo link  FROM/REPO TO/REPO [--type TYPE] [--note "..."]
bb repo unlink FROM/REPO TO/REPO [--type TYPE]
bb repo help                                  # Full usage
```

**Relationship types:** `depends-on`, `used-by`, `monitors`, `deploys-to`, `tests-for`, `forks-from`, `parent-of`, `child-of`, `integrates-with`, `sibling-of`.

### Creating a Mission

On any registered repo:

1. Create an issue describing the work
2. Add the label `section`
3. Assign it to the bot user
4. Section picks it up on the next cycle, works on it, and opens a PR

### Configuration

All config via environment variables (set in the launchd plist or shell):

| Variable | Default | Description |
|----------|---------|-------------|
| `SECTION_REPOS` | `[]` | Bootstrap-only fallback. Prefer `bb repo add` instead. |
| `SECTION_BOT_USER` | `kbosompem` | GitHub user to check issue assignments |
| `SECTION_LABEL` | `section` | Issue label that triggers a mission |
| `SECTION_POOL_SIZE` | `4` | Max concurrent operatives |
| `SECTION_MAX_TURNS` | `25` | Max Claude tool-use turns per mission |
| `SECTION_TIMEOUT_MS` | `1800000` | Mission timeout (30 min default) |
| `SECTION_WORKDIR` | `~/section-workspace` | Working directory for clones, logs, locks |

Monitored repos live in the registry (`madeline/repos.edn`), managed with `bb repo` subcommands. The `SECTION_REPOS` env var is only used when the registry is empty, to help with initial bootstrap.

## Self-Evolution

Section monitors itself. To add a feature or fix a bug in Section:

1. File an issue on `kbosompem/section` with the `section` label
2. Assign it to the bot user
3. Section will read its own source, implement the change, and PR it back
4. You review and merge
5. The change is live on the next cycle (Babashka is interpreted — no build step)

## Commands

```bash
bb run          # Run one cycle (poll → dispatch → housekeeping)
bb test         # Run sims (tests)
bb status       # The Perch — missions, capabilities, locks
bb walter       # Capability report
bb housekeeping # Clean stale locks and old logs
bb repo ...     # Manage the repo registry (see above)
bb install      # Install launchd plist for continuous operation
bb uninstall    # Remove launchd plist
```

## Architecture

```
launchd (every 5 min, restarts on crash)
  └── birkoff.bb
        ├── oversight/recover!     — self-heal
        ├── walter/check           — verify capabilities
        ├── comm/find-all-missions — poll GitHub (via registry)
        ├── operations/dispatch!   — thread pool
        │     └── operative/execute!
        │           ├── briefing/assemble  — build prompt
        │           │     ├── walter/capability-manifest
        │           │     ├── madeline/mission-context
        │           │     └── registry/relationship-context
        │           ├── claude -p          — do the work
        │           ├── git push           — egress
        │           └── gh pr create       — report
        ├── madeline/save!         — remember
        └── oversight/housekeeping — clean up
```

## License

MIT
