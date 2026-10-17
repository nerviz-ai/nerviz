# Pitfalls

Primary source: `@claude-help.md`, `.claude/schemas/extensions.json` (the `settings`,
`injections`, `arguments`, `types`, `guard` and `compose` blocks), what
`java .claude/hooks/ArchHook.java schema` checks, and the decision records each item cites.

## Why this page exists

Every item below is a trap that fails **silently**, or that a hook refuses with a message the
reader has to already understand. The page has two parts:

- **Part 1 — the Claude Code runtime.** Holds for any project that uses skills, agents, hooks
  or MCP: nothing errors, nothing warns, and the piece simply does not do what its author
  believes it does.
- **Part 2 — this repository.** Facts of this `.claude/`: write territories, frozen spec
  folders, who owns `pom.xml` and the outbox, what `guard sweep` and `compose gate` do and do
  not cover.

Both used to live in `@CLAUDE.md` § Known pitfalls, which is read in every session. Part 1
left in decision `0061`, part 2 in decision `0090`. Neither needs to be permanently in
context: they are needed while someone designs or edits a piece of `.claude/`, or writes
about one — which is when `claude-code-architect-designer` reads this page. Most of part 2
also sits behind `guard`, `schema` or `compose gate`, whose block message names the rule and
the fix. `@CLAUDE.md` keeps only the three facts that bite in any session with no hook behind
them, and points here with one row of its routing table.

**A new pitfall goes here, in both languages — never back into `@CLAUDE.md`.** That route is
what refilled the section between `0061` and `0090`.

What is **not** here: the frontmatter fields the runtime recognizes, which are data with a
single owner in `.claude/schemas/extensions.json` and are described in
[01-file-types.md](01-file-types.md).

## Part 1 · The Claude Code runtime

### Skills

**A skill cannot have the name of a native slash command.** The folder name becomes the
command, and `/doctor`, `/init`, `/context`, `/memory` already exist in the runtime. That is
why this repo's diagnostic skill is called `arch-doctor`. Shadowing a native command produces
no error — it runs the wrong command. `ArchHook.java schema` fails the folder by name, against
`types.skill.native_commands` in `.claude/schemas/extensions.json` — a list that has to grow
when the runtime gains a command.

**`$ARGUMENTS` in the body of a skill is interpolated at every occurrence**, not only under
`## Target`. A sentence that talks *about* the argument reaches the model with the real value
inside it: `/new-feature`'s "invoke `test-architect` with empty `$ARGUMENTS` (setup mode)"
arrived as "with empty `UC-003-initiate-kyc-verification` (setup mode)" — an order to use
setup mode, naming the argument that means design mode. Write "the argument" or "the target
above"; `ArchHook.java schema` rejects the literal, reading `arguments` from
`.claude/schemas/extensions.json`.

**`allowed-tools` with `Bash(command:*)` checks each segment of the pipe separately.** An
injection `` !`a | b | c` `` in the body needs a rule for `a`, `b`, and `c`; miss one and the
whole command is blocked before it runs. Write injections as a single command
(`ls .claude/skills`, not `find … | sed | sort`). This only affects skills that restrict Bash:
`allowed-tools: Bash` without a filter lets the whole pipeline through.

**A frontmatter `` !`…` `` injection runs in the session's persistent shell, at whatever cwd
it currently holds.** A `cd` into a skill directory in one `Bash` call contaminates every
injection of every skill invoked afterward, and a relative `test -f` then reports a file as
absent while it exists. Read a template with `Read` at an absolute path, never `cd` + `cat`.
Every injection in this repo resolves paths from `"${CLAUDE_PROJECT_DIR:-.}"`, and
`ArchHook.java schema` blocks one that doesn't — a genuinely cwd-independent injection needs a
regex in `injections.exempt_patterns`.

**A skill with `disable-model-invocation: true` listed in an agent's `skills:` is not
preloaded — and nothing warns.** The agent starts without it, and a body saying "catalog
preloaded" tells it to apply something the model never received. That was
`gof-design-patterns` (then `java-patterns`) in `java-spring-boot-developer`. To hand a
manual skill's content to an agent, inject it at `SubagentStart` (`ArchHook.java context
subagent`, decision 0077), not through `skills:`.

**A skill's `model` and `effort` apply only when it is typed as `/name`.** When the model
invokes the skill through the `Skill` tool, the turn stays on the caller's model and effort,
with no warning. The official docs promise the override on both paths. Measured on Claude
Code 2.1.280 over 18 invocations:
- a typed `/arch-doctor` switched to `sonnet-5`;
- `git-publish`, chained by `/new-feature`, stayed on `opus-5-5` every time.

The design skills run on Opus only because `/new-feature`, which is typed, already pinned
Opus. Called directly in a Sonnet session, one of them runs on Sonnet. Open bug:
[anthropics/claude-code#98898](https://github.com/anthropics/claude-code/issues/98898), the
same as #79664, which was closed as stale. Until it is fixed, do not rely on the `model` of a
skill that is only ever chained. Before concluding that a pin holds, check `message.model` in
the transcript. Separately, in auto mode an unsupported `model` is also ignored; that behavior
is documented. Design: `.claude/decisions/0118-skill-model-pin-audit-tail-and-bsd-sed.md`.

**Everything the model must obey lives in the body of the file**, never in frontmatter.
`metadata.*` was removed from skills and agents: ownership, `reads`, `handoff` and contracts
live in the body's `## Contract` section. Do not put `metadata:` back into a `SKILL.md` — it
costs tokens on every invocation and enforces nothing.

**A personal skill named like a project skill replaces it, with no warning.** Claude Code
resolves one name as enterprise > personal (`~/.claude/skills/`) > project (`.claude/skills/`):
a `~/.claude/skills/use-case-design/` runs instead of the pipeline's, on `/use-case-design` and
on automatic invocation. `/arch-doctor`'s `Skill shadowing` line names each collision; nothing
in the project can refuse the personal directory. A plugin skill cannot do this — it is
namespaced (`/plugin:skill`) — but it competes for the trigger through its `description`.

**A plugin blocked here looks broken.** `enabledPlugins` carries `false` for the plugins of
`settings.blocked_plugins` (`superpowers`, `mattpocock-skills`): installed at user level, they
do not load in this project nor in a generated one, and nothing says so. On purpose — their
skills compete with the pipeline for automatic invocation (decision 0138). To use one anyway,
re-enable it for yourself in `.claude/settings.local.json`; removing the line from
`settings.json` fails `schema`.

### Frontmatter and validation

**The runtime silently ignores unknown frontmatter.** An invented field is decoration, not
behavior. List of native fields in `@claude-help.md`.

**`claude plugin validate` does not validate fields.** It accepts `metadata:`, accepts
camelCase in a skill, and accepts an invented field, always with `✔ Validation passed`. It
does not look at `.claude/agents/`, `.claude/rules/`, or `.claude/settings.json`. It catches
malformed YAML, nothing else. What validates fields is
`java .claude/hooks/ArchHook.java schema`.

### Hooks

**`.claude/settings.json` is only read at session startup.** Editing hooks mid-session has no
effect — `claude` must be restarted.

**A hook fails silently in four ways, none an error.** Unknown event name — never fires.
`matcher` on an event that doesn't read one — filters nothing. An `args` list — the GitHub Copilot CLI ignores it,
launches a bare `java` that exits 1, and denies every tool call (hooks are shell form, 0168). A mode that
throws — exits 0 through `main`'s catch, looks like it passed. `schema` catches the first
three, from the `settings` block of `.claude/schemas/extensions.json`; the fourth only by
running the mode by hand.

**Under the GitHub Copilot CLI, a refusal's stderr never reaches the model.** On `preToolUse` it sees
only `hook exited with code 2`, and on `postToolUse` stderr becomes a screen warning. That is why
every `exit 2` refusal of `ArchHook` repeats its stderr on stdout, in the shape
`settings.deny_output` of `extensions.json` names (0170). **Never add `hookSpecificOutput` to that
shape:** at `exit 2`, Copilot falls back to its generic line and Claude Code swaps stderr for the
nested reason. `DenyReasonTest` fails by name on it.

**An `if` with a path only matches through `Edit(...)` or `Read(...)`.** `Edit` covers every
built-in tool that writes a file, `Write` included; an `"if": "Write(.claude/**/*.md)"` looks
like a filter and filters nothing. Source: `docs/pt-br/claude-code-docs/07-settings-permissoes-e-seguranca.md`.

**Only the hook-protocol modes of `ArchHook.java` read stdin** — `check`, `format`, `tests`,
`schema`, `audit`, `guard`, `context`, and `compose gate`. Invoking one of those by hand without
`</dev/null` blocks until something closes stdin, with no output: a command that looks hung,
not failed. `export`, `doctor`, `build` and the bare `compose` are invoked by people and read
nothing.

### `AskUserQuestion`

**It rejects a question with fewer than 2 options, and rejects the whole batch with it:**
`InputValidationError ... "too_small" ... path: ["questions",1,"options"]`. A question with one
option isn't a question — decide it, and record the decision where the answer would have gone.
The batch also has an upper bound of 4 questions per call.

**It rejects a question with more than 4 options, the same way:**
`InputValidationError ... "too_big", "maximum": 4 ... path: ["questions",0,"options"]`. Seven
choices are not one question: ask only what is still open — the rest is already decided
somewhere, like a blueprint's `features: true` — or offer four and leave the others to Other.
Same for a call with more than 4 questions: the next ones go in the next call. The generated
project carries the same pitfall in its own `CLAUDE.md`, from
`project-bootstrap/templates/root.CLAUDE.md.example`.

### MCP

**`.mcp.json` is only read at session startup**, same as `settings.json`. Adding or editing a
server mid-session has no effect until `claude` is restarted.

**A project-scoped server in `.mcp.json` needs one-time human approval** the first time it
loads (`claude mcp list` shows pending ones). That prompt is the trust boundary a cloned
repository can't skip — never work around it with `enableAllProjectMcpServers`.

**Server precedence is `local > project > user`, silently.** A personal server with the same
name as the team's `.mcp.json` one shadows it — no warning either way.

**An unset `${VAR}` in `.mcp.json` doesn't fail generation.** The server loads with the literal
`${VAR}` text and fails to connect at runtime instead. `claude mcp list` surfaces the
missing-variable warning; frontmatter schema validation does not.

### `CLAUDE.md` and `AGENTS.md`

**A module `AGENTS.md` alone is invisible to Claude Code while the root has a `CLAUDE.md`.** The
documentation says Claude Code reads `AGENTS.md` when there is no `CLAUDE.md` "on the path", and
leaves open whether the root one counts; Claude Code 2.1.296 did not load the module's on a
Read. What loads it is a `CLAUDE.md` in the module holding only `@AGENTS.md` — on a Read, Edit or
Write under the module, inside a subagent too. The `claude-md-and-agents-md` mode would fix it,
but project settings ignore it. In a generated project, `/arch-doctor`'s `Agent instructions`
line names the module missing the import. Design:
`.claude/decisions/0139-agents-md-root-pointer-module-owner.md`.

## Part 2 · This repository

### Write territory and the guards

**A skill writes only its class's territory, and `guard` blocks the rest with exit 2.** Deny
by default; the message names the class and its `write_allow`, and the fix is the data
(`skill_classes` in `.claude/schemas/extensions.json`), never a retry. A design run is
docs-only and cannot even *call* a `build`-class skill — `docker-architect` included: the
design run still cannot call it, but it asks: when the compose service is missing,
`persistence-architect` and `messaging-architect` record the answer in the partial, and the
implement run adds every `materialize` row through `compose-service-installer`. With no skill phase open, territory is unrestricted, which is why
editing a file by hand is never blocked.

**A skill that chains another of its own class adds that skill's territory; another class
replaces it.** Same class — `arch-adopt` → `sonarqube-setup` → `docker-architect`, all
`build` — and the phase holds all three, so each writes its own paths and the caller keeps
writing after the callee returns. Other class — `/new-feature` calling a design skill — and
the phase narrows to the callee, which is what keeps a design step docs-only. The rule used
to keep only the caller's territory on a same-class call, on the assumption that one class
means one territory; `build` gives each skill its own, and `sonarqube-setup` chained from
`arch-adopt` was refused `pom.xml`. Design: `.claude/decisions/0092-guard-same-class-chain-sums-territories.md`.

**A subagent's write is judged by `agent_classes`, not by the caller's phase.** Every write
carries its `agent_type`, and `guard` checks the path against the agent's own `write_allow`,
so an open design phase neither widens nor narrows it. An agent no class lists falls back to
the caller's phase — nothing else describes what it may write.

**Enforcement is no longer tool-shaped, but it is still not filesystem-shaped.** Until
lessons-learned-014 § 1 every hook matched on a tool name, so a heredoc, a `sed -i` or a
`tee` passed through `guard`, `check`, `format`, `audit` and `schema` alike — the exact
spellings a host instruction to prefer `Bash` over `Write`/`Edit` produces. `guard bash`
(`PreToolUse`, matcher `Bash`) now reads the command for the write shapes in
`guard.bash_write_shapes` and applies the territory and frozen-folder checks to every target
it can read literally. **What it deliberately does not do:** a target holding `$`, a backtick
or a glob, or landing outside the repository, is skipped without a word, and `check` and
`format` stay off `Bash` entirely — ArchUnit and `spotless:apply` already re-check both,
while a `PostToolUse` matcher there would pay a JVM on every `ls`. So the true statement is
narrow: **inside Claude Code, through `Write`/`Edit`/`MultiEdit`/`NotebookEdit`, and through
the shell spellings the shape list names.** Design:
`.claude/decisions/0063-bash-write-enforcement.md`.

**`guard sweep` backs both write guards on `Stop`, and it is git-shaped.** It diffs the
working tree against the baseline `guard prompt` takes at `UserPromptSubmit`, so a tree dirty
before the turn is not reported, and it runs the same territory and frozen-folder checks over
whatever changed — no matter which tool wrote it. Two limits worth knowing before reading it
as total coverage: it is **detection, not prevention** (the write already happened), and a
path git ignores never appears in `git status --porcelain` and is never swept — which in this
repository is only what `.gitignore` still lists, `.claude/decisions/` and
`.claude/lessons-learned/` having been versioned since 2026-09-28. A spec's `status:` close and
its `[ ]` → `[x]` toggles are admitted by comparing against `git show HEAD:`, since the sweep
has no `old_string` to read. Paths in `guard.sweep_exempt` are skipped — today `.claude/audit-usage/**`, the versioned trail the `audit` hook writes in parallel with the baseline; the tool-time guards still refuse the model a write there. **A path `guard write` or `guard bash` already admitted this turn is skipped too:** it was judged against the phase and the spec status of its own moment, and re-judging it at `Stop` against the phase open then — `git-publish`'s empty territory after `/new-feature` — or against a folder approved since flagged the run's own spec, partials and executor writes (issue #74). What the sweep judges is the writes no tool-time guard saw, against the phase open at `Stop`; a path admitted earlier and rewritten later by a spelling no guard reads is skipped with it. Design: `.claude/decisions/0065-guard-sweep-on-stop.md`, exemption `0105`, admitted paths `0114`.

**A push always prompts, a force push never runs, and a skill's `Bash` is scoped.**
`git push` and `gh repo create` sit in `permissions.ask`, which is evaluated before any
allow — so the prompt appears even inside `git-publish`, after its own gate. `guard bash`
refuses a force push in every spelling it can read (`-f`, `-uf`, `--force-with-lease`,
`+ref`, `git -C … push`, `sh -c "…"`), lists in `guard.force_push`; the old `deny` line only
caught `--force`. A skill whose `allowed-tools` names bare `Bash` fails `schema` unless its
`## Contract` carries an `**Unfiltered Bash:**` line saying why — four do (the ones that run
builds, network and docker); the rest list prefixes, and an injection command missing from
that list aborts the skill. Design: `.claude/decisions/0076-bash-scope-and-force-push-guard.md`.

**Commenting on, editing or closing an issue, and `gh api`, always prompt in this repo.** All
four sit in `permissions.ask` of `.claude/settings.json` — the prompt shows up even after
`/triage-issue`'s own yes, and it is the same prompt you see if an issue body talks
`issue-verifier` into trying to publish something. Not a broken tool: the text of a public
issue is third-party text. This repo only; a generated project has none of these lines.
Design: `.claude/decisions/0103-issue-filing-and-skeptical-triage.md`.

### Classes and models

**Every skill declares `model`, from its class's set.** `schema` fails a `SKILL.md` without
it, or with a value outside `skill_classes.classes.<c>.allowed_models` (`design` and `meta`
only `opus`, `observer` and `ops` only `sonnet`). A skill's `model` holds for the rest of the
turn, not just the skill — which is why a pinned model alone is not a reason to be an agent,
and why a lowering pin on a skill fired mid-turn is the case to watch. **Today that holds only
for a typed skill:** chained through the `Skill` tool, it runs on its caller's model (Part 1
§ Skills, #98898). Design:
`.claude/decisions/0081-skill-model-required-per-class.md`.

### Use case specs

**An implemented use case's folder is frozen except for three writes:** the spec's `status:`
line moved along `guard.status_transitions`, a checklist toggle in it, and
`UC-NNN/CHANGELOG.md` — the write `/new-feature`'s consolidation *requires* for every change
an impact row makes. Exempt basenames are data (`guard.frozen_exempt_basenames`), matched
directly under the folder: `notes/CHANGELOG.md` is still frozen.

**The spec has three closed states, and the checklist is ticked before the status closes.**
`approved` closes to `implemented`, or to `implemented-blocked` when the run left an approved
use case unreachable end to end — the code is on disk and green, and a `Satisfied by` the spec
named is not there yet. Either reverts to `approved`, which is the exit a run that closed by
mistake did not have. A `[ ]` → `[x]` toggle is admitted in **all three**
(`guard.checklist_toggle_statuses`): it is monotone, and requiring `approved` once froze 23
unticked boxes forever. The order is still the executor's contract, not the hook's: the status
line is the last write it makes to the folder. Design:
`.claude/decisions/0066-spec-state-machine.md`.

### Compose

**`compose gate` runs the compose check unprompted, and it blocks.** `docker-architect` step 7
already called `ArchHook.java compose` "not optional" and it had never been run against a
project: a `kafka` block publishing 9092 while advertising only `kafka:9092` shipped on day
one and was unreachable from every host client until a use case needed it. The gate is the
same `composeReport()` — one definition of healthy, shared with `doctor` — silent while
healthy, exit 2 with the failing lines otherwise. A service stopped on purpose blocks the stop
too; that is a gate, not a bug. Design: `.claude/decisions/0064-compose-gate-on-stop.md`.
A foreign container on one of the declared host ports is the exception: while no container of
this project exists (`docker compose ps -a` lists nothing), it is a warning that `compose` and
`doctor` print and the gate does not block on, since a stack nobody started has nothing to
bind yet. Once any container of the project exists, stopped or `created` included, it blocks
as before. Issue #107, `.claude/decisions/0129-compose-gate-foreign-port-warns-without-our-stack.md`.

**A healthcheck that passes proves nothing about host reachability** — it runs inside the
container, where `localhost` is the service. A service that publishes a port to the host
while advertising only its compose-network name (`KAFKA_ADVERTISED_LISTENERS:
PLAINTEXT://kafka:9092` next to `ports: "9092:9092"`) is reachable from no host client, and
neither the healthcheck nor Testcontainers sees it — Testcontainers wires its own listeners.
`ArchHook.java compose` reads the file for it (`compose.advertised_env_suffixes`); a service
that advertises nothing claims nothing and is left alone.

**A healthcheck that calls `localhost` inside the `caddy:2.11.6-alpine` image never turns
healthy.** Busybox `wget http://localhost:2019` resolves `localhost` IPv6-first, to `::1`,
and the admin API listens on `127.0.0.1` only — the connection is refused and the
healthcheck fails even though the proxy is fine. `docker-architect`'s edge service names
`127.0.0.1` explicitly in its healthcheck, never `localhost`. Design:
`.claude/decisions/0122-transport-security-setup-and-secrets.md`.

**Every `${VAR:default}` that points at a compose service holds in two places.** The default
serves `./mvnw spring-boot:run` on the host: it has to be `localhost` on a port the service
**publishes**. The variable in `app`'s `environment:` serves the container: without it,
`localhost` in there is the application itself. The OTLP collector published no port from
0046 until issue #66, and every host-run export failed while `compose` said healthy. Question
5 of `compose` now reads both sides, and the gate blocks an older project right after
`/arch-adopt`. The way out is the `otlp-host-run` migration's prompt. If another project
holds 4318, set `OTLP_HTTP_PORT` in `.env` and use the same port in `OTLP_ENDPOINT` and
`OTLP_METRICS_ENDPOINT` on the host. Design: `.claude/decisions/0110-otlp-host-first-collector.md`.

**A socket that opens is not a login that works.** Question 5 compares host and port, so
`jdbc:postgresql://localhost:5432/bankingapp` passed against a `postgres` service that
created `appdb` for user `app`, and the host run died on login with `compose` saying
healthy (lessons-learned-021). The datasource and the database service now read one set of
variables — `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `DB_PORT` — with the artifact name as the
default on both sides, and the password only from the untracked `.env` that compose reads
and `application.yml` imports. `compose` and `doctor` print a disagreement in database or user,
or a missing password where the service requires one, as a **warning**: the gate never
blocks on it, since a project can point its datasource at another database on purpose. It
never prints a password value. Projects generated earlier get the
`datasource-env-convention` migration's prompt from `/arch-adopt`. Design:
`.claude/decisions/0124-datasource-defaults-one-convention-checked-by-compose.md`.

**The database image applies `DB_NAME`, `DB_USERNAME` and `DB_PASSWORD` only to an empty
volume.** After the first start the named volume keeps the old database and user, while
`docker exec … env` shows the new values — so the check people reach for lies. Only
`docker compose down -v` applies them, and it deletes the data. Nothing checks this; the
service templates and the generated root `CLAUDE.md` say it.

**`grep -A2 "^services:" docker-compose.yml` is not the list of services.** It reads two
lines and stops, dropping services declared further down and reporting the children of
`volumes:` as services. Every piece that needs that list — `docker-architect`'s injection
and step 3, `messaging-architect` step 9, `persistence-architect` step 9 — uses the `awk`
one-liner bounded to the `services:` block. A wrong portrait invites recreating a service
that already exists.

### Transport security

**An SSL bundle whose file is missing fails the startup even with `server.ssl.enabled:
false`.** `sslBundleRegistry` loads every bundle eagerly, so disabling TLS in configuration
does not stop Boot from trying to read the file. Topology B's bundle therefore lives inside
a YAML document gated by the profile expression `"!local & !docker & (!test | tls)"`, never
behind a flag that leaves the document active with no file to back it. Design:
`.claude/decisions/0122-transport-security-setup-and-secrets.md`.

**Behind an edge with no `server.forward-headers-strategy`, HSTS is never written and
nothing reports it.** The application believes every request arrived over plain HTTP, so the
header's precondition is never met — a missing security header, not an error anywhere.
Design: `0122`.

**Spring Security's `HstsHeaderWriter` only writes the header when it is absent — a filter
left in place once Spring Security arrives silently overrides the chain's own value.**
That is why `transport-security-setup`'s `HstsHeaderFilter` is
`@ConditionalOnMissingClass(SecurityFilterChain)` and steps aside the moment the security
starter is added, instead of both writing the header on the same response. Design: `0122`.

**With topology B, every `@SpringBootTest` must run with `@ActiveProfiles("test")`, or its
context now starts with TLS on and fails reading `/etc/tls`.** The failure surfaces as a
missing file deep in the stack trace, not as a clear "wrong profile" message —
`transport-security-setup` step 5 adds the annotation to every existing `@SpringBootTest`
it finds without one. Design: `0122`.

**The transport integration tests generate their certificate at run time with the JDK's
`keytool` — no key file is committed, not even for a test.** A committed "test-only"
certificate ends up trusted somewhere it should not be (`@.claude/rules/secrets.md`).
Design: `0122`.

### Ownership inside a feature run

**`pom.xml` has exactly one writer inside a feature run: the executor, for a dependency the
spec declares** (the messaging partial's § 7, or the persistence equivalent). The design
skills are docs-only, `/new-feature` writes the spec, the two installers own only their own
setup. Anything else in the file — a plugin, a property, a version bump — is reported, never
written.

**Outside a feature run, the `sonar.*` lines of the root build file belong to
`sonarqube-setup`.** It writes the scanner plugin and its properties once — chained by
`project-bootstrap`'s step 8.4 or by `arch-adopt` — and stops on a re-run when the scanner
is already there. The `sonarqube` compose service it needs is still `docker-architect`'s,
and the token is never in any file: the scanner reads `SONAR_TOKEN` from the environment.
Design: `.claude/decisions/0091-sonarqube-setup-skill.md`.

**Outside a feature run, transport is decided once by `transport-security-setup`, and split
four ways from there.** The topology, `application.yml`'s TLS lines, the HSTS writer and the
transport IT are the setup skill's own territory — chained by `project-bootstrap`'s step 7.5
or by `arch-adopt`'s step 7.5, and skipped on a re-run once the root `CLAUDE.md` already
carries a `**Transport:` paragraph. The `edge` service, its Caddyfile and the `Dockerfile`'s
exposed port stay `docker-architect`'s, invoked from inside the setup skill. Outbound TLS —
a client certificate or a private CA for a call this service makes — is
`http-client-architect`'s, never the setup skill's. The client-certificate authentication
mechanism of topology C is deferred to `security-architect`, designed only when a use
case's access rule names a machine caller. Design:
`.claude/decisions/0122-transport-security-setup-and-secrets.md`.

**The outbox has three owners, split by question.** `persistence-architect`: the table,
columns, claim query, batch size, attempt ceiling, retention window and the prune's
statement. `messaging-architect`: that the case needs one, the delivery guarantee, and the
relay pass with its broker side. `jobs-architect`: when anything runs — the relay's poll
interval, the on/off switch, the replica count the claim must meet, and the prune job. A § 6
row naming columns is a divergence, and a column or claim decision that would drop a
declared guarantee stops the pipeline instead of being settled by precedence
(`.claude/decisions/0087-jobs-architect-skill.md`).

**A cache needs a tolerance the business stated, and lives in one package.** `cache-architect`
runs only when `00-use-case.md`'s `Freshness` row states how old an answer may be — a
spec approved before that row existed never triggers it, and "the query looks slow" is not a
tolerance. The cache is a decorator of the outbound port in the blueprint's `.cache` package,
wired as the port's `@Primary` bean by the delegate's bean name: a `@Cacheable` on a
persistence adapter or a service is outside `rules/caching.md`, a writer that bypasses the port
(a bulk `UPDATE`, a job, another service) does not evict, and `sync = true` next to `unless`
fails at startup. Hibernate's second-level cache stays off; HTTP caching is
`rest-api-architect`'s (`.claude/decisions/0144-cache-architect-skill.md`).

**A model or a vector store is one port, and four partials own its pieces.** `ai-architect` runs
only when `10-domain.md` declares a port of kind `LLM` or `vector store`, and `29-ai.md` owns
the provider, the model, the prompts, the answer type, the data sent, the timeout and the one
retrying layer. The port, its "could not decide" value and the review state stay
`10-domain.md`'s; the vector store's migration is `20-persistence.md`'s (`29-ai.md` § 9 only
asks); a re-index trigger or an ingestion job is `25-messaging.md`'s or `35-jobs.md`'s. Spring
AI lives in the `.ai` package alone, so `org.springframework.ai..` in an `application` module
is a boundary violation (`.claude/decisions/0145-ai-architect-skill.md`).

**A GraphQL operation is one input port, and four partials own its pieces.**
`graphql-api-architect` runs only when `00-use-case.md`'s `Trigger` row ends in `inbound
GraphQL adapter` or `inbound REST and GraphQL adapters`, and `31-graphql.md` owns the root
fields, the types, the payloads, the connections and the project's limits. A batch port it
needs arrives as a requirement from `31-graphql.md` § 9 and becomes `10-domain.md`'s port at
consolidation; the batch statement and the index a connection's order needs are
`20-persistence.md`'s; who may read each root field and each sensitive field is
`32-security.md`'s access rows, never `31-graphql.md`'s. The schema snapshot of
`GraphQlSchemaCompatibilityTest` lives in `src/test/graphql/`: under `src/test/resources` it
would sit on the test classpath. Spring for GraphQL lives in the `.graphql` package alone
(`.claude/rules/api-graphql.md`, `.claude/decisions/0146-graphql-api-architect-skill.md`).

**The storage family is a datum of the use case, and no step of the pipeline weighs it.**
`use-case-design` records it in `00-use-case.md`'s `Storage` row — per aggregate,
`relational` or `document (MongoDB)` — from the request, or from one bare factual question
("SQL or NoSQL?"), and never argues for either; that decision interview belongs to a planned
solution-design skill. `persistence-architect` stops without the row. MongoDB needs a replica set
everywhere, the compose service and the integration test included, because a standalone server
refuses every transaction; and declaring a `MongoTransactionManager` removes Spring Boot's JPA
transaction manager, so the shape that keeps JPA beside MongoDB declares both
(`.claude/rules/persistence.md` § Document (MongoDB),
`.claude/decisions/0147-document-storage-and-search-architect.md`).

**A search index is a projection, and five partials own its pieces.** `search-architect` runs only
when `00-use-case.md`'s `Search index` row names a projection, and `23-search.md` owns the
document, the mapping, the alias, the query, the indexer's write rules, the rebuild adapter and
the lag. The source's version and the outbox event are `20-persistence.md`'s; the indexer's
consumer is `25-messaging.md`'s; the rebuild job is `35-jobs.md`'s. A cache over a search port
gets the case's `Freshness` minus the lag, never the whole of it. Spring Data Elasticsearch and
OpenSearch live in the `.search` package alone, so their imports in an `application` module are a
boundary violation (`.claude/rules/search.md`,
`.claude/decisions/0147-document-storage-and-search-architect.md`).

**The bounded context is a project fact, not a per-use-case answer.** First segment of every
topic name, asked with the coordinates in `/init-project` and written into the generated
project's root `CLAUDE.md`. A prefix chosen inside one use case gives one system two
namespaces. A project adopted through `/arch-adopt`, or generated before the question
existed, gets it in that skill's step 8 — but only on the **second** `/arch-adopt` run
after the change ships: the first runs the skill's old copy, which does not have the step
yet. Until then, writing the line by hand is the immediate fix. Design:
`.claude/decisions/0093-lessons-learned-016-fact-sources.md`.

**The `tests` Stop hook does not run while a writer subagent works in the background.** An
agent whose class in `agent_classes` has `executor: true` leaves a marker at `SubagentStart`
and deletes it at the `SubagentStop` of the same `agent_id`. While the marker exists, the main
thread's `Stop` prints `⏸ Tests deferred: <agent> still running in background` and exits 0
without calling Maven. The `Stop` of the turn the agent's notification opens runs the tests. A
marker older than `tests.writer_agent_max_minutes` is deleted and ignored: an agent killed by
machine sleep never reaches `SubagentStop`. Design:
`.claude/decisions/0116-tests-defers-while-a-writer-subagent-runs.md`.

**`✅ verify … green` at `Stop` is not a green CI.** The gate runs `tests.stop_maven_args` —
`verify -DskipITs -Djacoco.skip=true` — on the changed modules, offline. So `spotless:check` and
`checkstyle-test` run at every turn end, but the integration tests (Testcontainers) and JaCoCo's
`check` stay with the executor's `./mvnw verify` and CI's `./mvnw clean verify`. A plugin of the
project's own bound to `verify` that needs the network blocks offline: its skip flag goes in
that list, never the plugin switched off. With the key gone, the gate blocks and names it.
Design: `.claude/decisions/0142-stop-gate-runs-verify-without-its.md`.

**A cost A/B across `/arch-adopt` loses the "before" rows when that run's commit was
declined.** `git-publish` stages the `.claude/audit-usage/` trail with the run's commit. Declined,
the trail stays as uncommitted appends, and `/arch-adopt` refuses a dirty worktree with no
exception for that folder — cleaning the worktree to update deletes the earlier run's
`nodes.jsonl` rows. Before updating, commit the trail on its own, or copy the run's report and
rows. Seen in issue #111. Design:
`.claude/decisions/0132-audit-per-piece-cache-and-context-growth.md`.

**A bulk write on an audited aggregate leaves no revision, and nothing says so.** A JPQL
`UPDATE`/`DELETE` (`@Modifying`), `CriteriaUpdate`, `CriteriaDelete` or native SQL changes the rows
without passing through Envers' listeners: the history misses the change, with no error and no red
test. No hook covers it — no observed failure justifies one. What exists is the `grep` of
`.claude/rules/persistence.md` § How to verify and the list of bulk writes `20-persistence.md`'s
§ Audit records. The same goes for the actor: a job or consumer pass without `SystemActor` fails the
write, by design — the actor is never `null`. Design:
`.claude/decisions/0149-auditing-variant-of-persistence-architect.md`.

**The API version is one configuration per project, written by the first REST case.**
`ApiVersioningConfig` adds the `/api/v{version}` prefix to the REST adapter package's controllers,
and to them alone: a controller whose class mapping still spells `/api/v1` then answers at
`/api/v1/api/v1/...`. So a project generated before `0150` moves every controller before turning the
config on — the `api-version-path-prefix` migration says how, and no public URL changes. The prefix
is `/api/v{version}`, not `/api/{version}` (springdoc would document `/api/1/users`), and it is
scoped to the package, not only to `@RestController` (springdoc's own controller would get the
prefix and `/v3/api-docs` would answer 404). A request rule on `/api/v1/…` leaves the `/v2` of the
same route with only `authenticated`: rules write `/api/*/…`. And the OpenAPI document lists each
operation only under the version its mapping starts at — an operation reaching v2 through its
baseline is not listed there again (`.claude/rules/api-rest.md` § Versioning,
`.claude/decisions/0150-api-versioning-in-rest-api-architect.md`).

**An admission limit exists only when the use case states it, and the quota is per replica.**
Without an `Admission` row in `00-use-case.md`, no designer proposes a quota or a bulkhead — and
`use-case-design` never asks. Resilience4j's `RateLimiter` keeps its state in the JVM: each replica
enforces the quota divided by the replica count, and behind an uneven balancer the limit is
approximate; a quota exact across replicas is the edge's. Three silent traps: a
`RateLimiterRegistry` per client never forgets anyone and publishes one metric series per client;
the `@Bulkhead` aspect runs before `IdempotencyAspect` only because the latter declares no
`@Order` — giving it one, or touching `bulkhead-aspect-order`, makes the rejected call claim the
idempotency key; and the aspect does not exist in a `@WebMvcTest`, so a slice test of the bulkhead
passes with the bulkhead absent (`.claude/rules/inbound-admission.md`,
`.claude/decisions/0151-inbound-admission-in-rest-and-graphql.md`).

**The mapper mechanism is the project's, decided once: the root `CLAUDE.md`'s `**Mappers:`
paragraph.** Without it, every mapper is manual. `/new-feature-implement`'s pre-flight asks once,
and `mapstruct-installer` records the answer — `manual`, or `MapStruct` after wiring the build and
converting every existing mapper, its callers and their tests in one change, from a clean `src/`
so `git restore` undoes it. A spec never names the mechanism; the executor reads the paragraph.
Three silent traps: declaring `annotationProcessorPaths` turns classpath discovery off — Lombok and
`spring-boot-configuration-processor` stop with no error unless listed; the Initializr declares
that list **inside the `default-compile` execution**, which replaces a plugin-level one, so an
entry written at the wrong level is ignored; and `maven-metadata.xml`'s `<release>` is the last
deploy, not the last stable — MapStruct's was `1.7.0.Beta2`. In the domain and the application,
`org.mapstruct..` is a forbidden import (`.claude/rules/mapping.md`,
`.claude/decisions/0160-mapstruct-as-a-per-project-mapper-choice.md`).

**The AsyncAPI document looks right when it is wrong, and four of its traps are silent.**
Springwolf's own `@KafkaListener` scanner documents a `__TypeId__` header this wire never
carries and, next to `@AsyncListener`, lists the method as a second message with the consumer group
lost — the generated `application-kafka.yml` turns it off and annotates both sides. The
`operationId` is not an attribute: it is `<topic>_<send|receive>_<method name>`, so renaming a port or
listener method renames the operation, and the snapshot test reports it. The partition key binding is
accepted by the annotation and not rendered into the document, so the key is written in the
operation's `description`. And Springwolf's core brings Jackson 2: application code keeps the Jackson
3 `JsonMapper` Boot registers, and `UI publishing` stays `false` (`.claude/rules/messaging.md`,
`.claude/skills/messaging-architect/references/springwolf-reference.md`,
`.claude/decisions/0162-asyncapi-contract-via-springwolf.md`).

### Mods

**Below Claude Code 2.1.287 no mod loads, and nothing says so in the session.** The band
and `/nerviz-doctor` are simply absent. `/arch-doctor`'s `Mods` line
compares the CLI on PATH with `mods.min_version`. In `claude -p`, VS Code, a cloud session or
under `--safe-mode` they are absent by design: the guards they draw keep enforcing.

**The marketplace of `.claude/mods/` registers only after the folder's workspace trust is
accepted, and resolves against the main checkout.** In a git worktree the mod loads from the
main checkout, not from the worktree being edited; test an edit there with
`claude --plugin-dir .claude/mods/<name>`. An edit takes effect at the next session or after
`/reload-plugins`.

**`schema` at `Stop` blocks a mod that would load half-way** — an unknown event, a gating hook
without `.catch`, a forbidden call, a program other than `java`, a mod the marketplace or
`enabledPlugins` forgot, no tests. The message names the defect and the `mods` key that owns
the list. Design: `.claude/decisions/0131-mods-in-architect-designer.md`.

**The cockpit's dialog appears only for a `guard` refusal.** It recognizes one by the
`mods.deny_markers` fragments; a refusal typed at a permission prompt carries none and asks
nothing. Rewording a guard message without updating its marker makes `schema` fail by name —
otherwise the dialog would stop appearing in silence. The refusal stands whichever button is
pressed; "Stop the turn" only ends the turn.

### Rules and decisions

**A rule without `paths` loads at launch, every session — it is not "citation only".** So
every rule declares the narrowest glob that holds it, and Java is `**/src/**/*.java`, never
`**/*.java`: that one matches `.claude/hooks/ArchHook.java` and pulls every Java norm in on
each read of the hook. A renamed rule's old name goes in `export.retired`, or the target keeps
both. Design: `.claude/decisions/0082-rules-without-paths-load-at-launch.md`.

**`.claude/decisions/` is neither a norm nor living documentation.** It records what was
decided on the date, not what holds today: no `paths`, outside `00-index.md`, and it does not
travel to the generated project. A new record supersedes the old one; the old one is not
rewritten.

**`arch-adopt` refuses a project built on a Spring Boot line below the floor — and does not refuse one whose line it cannot read.**
`export` reads the destination's build (`doctor.platform.build_files`, `line_patterns`) and
exits 2 before writing a byte when the line is below `doctor.platform.min_line`: the norms,
exemplars and the `tools.jackson..` allowlist this `.claude/` ships do not apply to an older
line. There is no flag to bypass it. A version held in a property, a corporate parent or a
catalog no pattern reads is not refused, because a refusal needs evidence; `doctor` prints
`Platform ⚪` there. Design: `.claude/decisions/0161-spring-boot-3-5-line-for-adopted-projects.md`.
