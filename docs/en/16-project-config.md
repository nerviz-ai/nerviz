# Project settings — what a generated project configures in its own `.claude/`

Almost everything under a generated project's `.claude/` belongs to Nerviz: `/arch-adopt`
overwrites it on every update. A few files belong to the **project**. `export` never names
them, so an update never touches them, and while a file is absent the project gets the
behaviour Nerviz ships. This page lists those files, the one command that changes them, and
the steps to add a new one.

Design: [`0187`](../../.claude/decisions/0187-project-config-registry-skill-and-mod.md).

## The settings

| Id | File | What it changes | Default | `doctor` line | Decision |
|---|---|---|---|---|---|
| `audited` | `.claude/audit-usage/audited.json` | Which skills and agents the audit trail records. `skills.<name>` / `agents.<name>`: `true` or `false` | each piece's class (`audited` in `skill_classes` / `agent_classes`). `arch-adopt` is fixed off | `Audit overrides` (gates CI) | [`0111`](../../.claude/decisions/0111-audit-opt-out-owned-by-the-project.md) |
| `plan-limits` | `.claude/audit-usage/plan-limits.json` | The billable-token budget behind the plan-window projection of the audit reports. `pro.five_hour_tokens`, `pro.weekly_tokens`: a number above 0, or `null` | unset, so no projection | `Plan limits` | [`0133`](../../.claude/decisions/0133-audit-plan-window-projection.md) |
| `compose-gate` | `.claude/compose-gate.json` | Whether a compose service in `exited`/`dead` blocks the end of a turn. `exited`: `block` or `warn` | `block` | `Compose overrides` (gates CI) | [`0186`](../../.claude/decisions/0186-compose-gate-foreign-port-warns-while-our-stack-is-stopped.md) |

The table above is a reading aid. The source of truth is the `project_config` block of
`.claude/schemas/extensions.json`, and `config list` prints it with the current values.

## Changing a setting

```bash
java -jar .claude/hooks/ArchHook.jar config list                              # every file, key, value, default
java -jar .claude/hooks/ArchHook.jar config set compose-gate exited warn
java -jar .claude/hooks/ArchHook.jar config set audited skills.report-issue false
java -jar .claude/hooks/ArchHook.jar config set plan-limits pro.five_hour_tokens 400000
java -jar .claude/hooks/ArchHook.jar config unset compose-gate exited         # back to the default
```

In Claude Code, `/project-config` does the same in conversation. It shows what is set,
asks which change you want, and runs the command above.

How `config` behaves:

- **`config` is the only writer.** `set` builds the file as it would be after the change and
  runs it through the validator `doctor` uses. On any problem it writes nothing and exits 2,
  naming the problem. `guard` refuses a direct Write, Edit or shell redirect into any of these
  paths and prints the `config set` command instead. A model therefore cannot leave one of
  these files half valid, even on a host that ignores `disable-model-invocation`.
- **`unset` restores the default.** It removes the key, and deletes the file when nothing is
  left, because an absent file means the default.
- **Values.** `true`/`false`, `null`, a number, and anything else is a string.
- **Out of reach.** A key upstream fixes, which today is only `skills.arch-adopt`, is refused.
  A setting of the audit trail is refused while `.claude/audit-usage/` does not exist: creating
  the directory to hold the setting would switch the whole trail on, and that is a separate
  decision (see [`08-audit-usage.md`](08-audit-usage.md)).
- **Exit codes.** 0 written, 2 refused and nothing written, 1 usage error.

These files are versioned. Commit a setting together with the change it serves, so the
reason sits next to it in the history.

## The pane

`/nerviz-config` opens the same settings in a pane, from the `nerviz-config` mod of this
repository. It spends no turn. The pane opens with the N of the logo on top, and a spine runs down it with
one node per file. A node and a value are blue only when the file holds a value that differs
from the default; the rest is a ghost `○`. A button is a change: it asks first (`Write` or
`Cancel`), then runs `config set` or `config unset`. When `config` refuses, the pane shows
what it printed and nothing was written. Where nothing draws (`claude -p`) the command prints
the listing as text and changes nothing.

The mod holds no rule: every value, default and refusal is `config`'s. It stays silent in a
project whose `.claude/hooks/ArchHook.jar` is missing, and says to run `/arch-adopt` for a jar
that has no `config` mode. It does not load in `claude -p`, VS Code or a cloud session, which
is why the skill and the command stay the way to change a setting everywhere. Delivery to
projects through the plugin is a separate change in `nerviz-ai/marketplace`; nothing under
`.claude/mods/` goes through `export` (0131).

## On other agentic platforms

`config` is plain Java, so it works the same in Claude Code, the GitHub Copilot CLI, VS
Code, a cloud agent, or a terminal with no agent. `/project-config` is a `SKILL.md`, which
Copilot also reads from `.claude/skills/`. Its `` !` `` injection only expands in Claude Code,
and on other hosts the skill tells the model to run `config list` first. `guard` runs wherever
the host executes the hooks in `.claude/settings.json`
([`13-github-copilot.md`](13-github-copilot.md) § 2.5). Where it does not, `doctor gate` in CI
still fails on a malformed `audited.json` or `compose-gate.json`.

## Adding a new project setting

1. One entry under `project_config.files` in `.claude/schemas/extensions.json`: `base`
   (`root` or `audit`), `path`, `validator`, `doctor_label`, `summary`, `decision` and `keys`.
   A key may use `{skill}`, `{agent}`, `{budget_plan}` or `{window_budget}`, and `config list`
   expands each one.
2. A validator. `keys` already covers a flat file of closed values: list them in `values`
   and write no Java. Anything else gets a new `case` in `projectConfigProblems` of
   `ArchHook.java`, returning the problems and then, as the last element, what the file sets.
3. The reader: the code whose behaviour the setting changes, which looks up the path with
   `projectConfigRel` / `projectConfigName`, never a literal.
4. A `doctor` line through `projectConfigFileProblems`. Add it to `doctor.gate.labels` when a
   malformed file is a defect of the project rather than of the machine.
5. A row in the table above, in both languages, and a case in
   `.claude/.ci/ProjectConfigTest.java`.

Never add a fourth file declared in its own block, the way the first three were before 0187.

## What is not here

- `.claude/settings.json` and `extensions.json`: Nerviz owns them, and `/arch-adopt`
  overwrites them.
- `.claude/settings.local.json` and `~/.claude/settings.json`: personal configuration that is
  not versioned.
- A pane that works outside Claude Code: the mod loads only where Claude Code loads mods.
