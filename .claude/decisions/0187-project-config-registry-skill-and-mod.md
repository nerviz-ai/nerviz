# 0187 · One registry of what a generated project configures, one writer, a skill and a mod over it

- **Date:** 2026-10-24
- **Scenario:** "pelas decisões 0111 e 0186, há possibilidade de configurações próprias de
  projetos gerados e mudar comportamentos do agent harness. Mas não há uma padronização ou
  documentação de como fazer isso no nerviz. 1. quero criar uma skill/agent para facilitar essa
  configuração de maneira mais intuitiva e documentada. 2. quero criar um mod do nerviz para
  visualização e uso dessa configuração via UI"
- **Decision:** Option A, delivered in two branches. **Phase 1 (this record's branch,
  `feat/project-config`):** the `project_config` registry in `.claude/schemas/extensions.json`
  (the three declarations moved into it), the Form 7c `config` mode of `ArchHook.java`
  (`list`, `set`, `unset` — the only writer), `guard` refusing a direct write of a registered
  path, the Form 2 skill `.claude/skills/project-config/SKILL.md` (class `ops`, travels), and
  `docs/{en,pt-br}/16-project-config.md`. **Phase 2 — the mod `nerviz-config`, its UI and its
  vendoring by the `nerviz` plugin — is built only after the phase 1 branch is merged**, from
  the `config list` JSON that merge fixes, under its own Form 9 run of
  `claude-code-architect-designer` that amends this record
- **State:** approved by Lucas Fernandes, on 2026-10-24 — Option A, split in two: phase 1 now,
  the mod's UI after phase 1 is merged

## What exists today

Three files of `.claude/` belong to the generated project — `export` never names them, so
`/arch-adopt` never touches them — each declared in its own block of `extensions.json`, each
validated by its own `doctor` line, each documented only in a `$comment` and its decision:

| File | Declared at | Validated by | Decision |
|---|---|---|---|
| `.claude/audit-usage/audited.json` | `audit.project_overrides` | `doctor` › `Audit overrides` (gated) | `0111` |
| `.claude/audit-usage/plan-limits.json` | `audit.plan_limits` | `doctor` › `Audit` | `0133` |
| `.claude/compose-gate.json` | `compose.project_overrides` | `doctor` › `Compose overrides` (gated) | `0186` |

Nothing lists them together. A fourth override would invent its shape, its validator and its
documentation a fourth time.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | A project owner did not know the files existed; one edited a file by hand with a wrong key or value; more overrides are coming and each reinvents the pattern | create nothing as "anticipation" |
| — scope | A registry in `extensions.json` naming every project-owned file, its keys, values and default; the three above move into it; a new override is one entry there | per-file documentation only; `settings.local.json` (personal, out of the designer's scope) |
| 2, 3 — trigger, frequency | By hand, rarely — `/project-config`, `disable-model-invocation: true`: it writes a versioned file | Form 1 |
| 5, 6 — nature, isolation | A procedure; no context to preserve, no tool to restrict beyond what the writer enforces, same model | Form 3 (invariant 5) |
| — writer | **One writer:** a new `config` mode of `ArchHook.jar` validates against the registry and writes; `guard` refuses a direct Write/Edit/Bash write of a registered path and names the command | a skill writing JSON with Edit; a mod writing with `$.fs.write` (`mods.forbidden_calls`) |
| 8 — destination | Registry, mode, guard and skill travel (both). The mod does not travel with `export` | — |
| 18 — surface | Generated projects may run under the GitHub Copilot CLI, VS Code or a cloud agent, where no mod loads (`16-mods.md` § Where it runs; `docs/pt-br/13-github-copilot.md` § 2.3, 2.5) | the mod as the only way to configure |
| 19 — guarantee beneath | The `config` mode's validation, `guard`, and `doctor`'s gated lines | a mod that validates in TypeScript (invariant 2) |
| 20 — state | None in the mod: it reads `config list` every time it draws | `$.store` |
| 17 — CI | The mod's tests run in this repository's `mods` job; the marketplace only vendors it | the marketplace's own CI |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| A | Registry + `config` mode + guard + `/project-config` + mod `nerviz-config` vendored by the `nerviz` plugin | 8 | **Approved** — in two phases, the mod after phase 1 is merged |
| B | As A, with the mod shipped by `export` | 4 | Rejected — 0131's risks 1 and 3 inside every project, and no mod loads under Copilot, VS Code or a cloud agent |
| C | As A without the mod | 7 | Rejected as the end state — it is phase 1 of A |
| D | Create nothing beyond a documentation page | 3 | Rejected — a wrong key still waits for `doctor`; a fourth override reinvents everything |

## Phase 2 — the mod, after phase 1 is merged

Not started on purpose. What phase 1 leaves fixed for it, and what its own design run decides:

- **Fixed by phase 1:** the `config list` JSON line (each file's `id`, `path`, `exists`,
  `available`, `in_force`, `problems`, and per key `key`, `type`, `values`, `default`, `value`,
  `fixed`), `config set|unset` exit codes (0 written · 2 refused, nothing written · 1 usage),
  and the guard rule the mod sits on.
- **Decided then:** render sites (a `Pane` and a `/nerviz-config` command that spends no turn,
  a dialog confirming a change), the `mods.$comment_calls` sentence admitting a write made
  through `ArchHook.jar config set` (the mode is the validator), silence where there is no
  `.claude/hooks/ArchHook.jar`, the "run `/arch-adopt`" fallback for a jar with no `config`,
  no hook on a `gating_events` event, its `tests/*.test.ts` in the `mods` job, and the separate
  pull request in `nerviz-ai/marketplace` that vendors it after the source tag.

### Option A — registry, one writer, skill, plugin-delivered mod (score 8)

**Motivator:** axes 1 and "writer" — every override has one shape, one validator and one
writer; the mod is a view over that, never another owner.

**Shape:**

```
.claude/schemas/extensions.json
  project_config {                      ← new, single owner
    files: {
      audited:      { path, shape, default, doctor_label, decision }
      plan-limits:  { … }
      compose-gate: { … }
    }
  }
  audit.project_overrides, audit.plan_limits, compose.project_overrides
                                        → read from project_config (moved, not copied)
  guard: registered paths refused to Write/Edit/Bash outside the mode
.claude/hooks/ArchHook.java
  config list            JSON: each file, keys, allowed values, current value, in force?
  config set <id> <key> <value>   validate → write → print the diff line
  config unset <id> <key>
  doctor › Audit overrides / Compose overrides / Audit read the same validator
.claude/skills/project-config/SKILL.md  Form 2, class ops, export.skills.include
docs/{pt-br,en}/16-project-config.md    what each setting changes, default, decision
.claude/mods/nerviz-config/             Pane + /nerviz-config (no turn) + dialog
  └─ $.process.run → java -jar .claude/hooks/ArchHook.jar config list | set
nerviz-ai/marketplace                   vendors the mod beside arch-adopt (sync.sh), separate PR
```

**Pros:** one owner per setting (invariant 2) — `doctor`, the skill, the mod and the docs read
the registry; a fourth override is data (invariant 10); the `config` mode is plain Java, so
the skill works under Claude Code, the Copilot CLI and VS Code alike, and any agent can call
it; the guard keeps a model from hand-editing a registered file even where
`disable-model-invocation` is not honoured (`13-github-copilot.md:208`); the generated
project stays Java-only, and the mod's unsandboxed code reaches only people who installed
the plugin (0131's axis 8 kept for `export`).

**Cons:** `mods.forbidden_calls` exists to stop "a file write that no tool-time guard sees";
`config set` launched by the mod is such a write — admitted because the mode is the validator,
and the `$comment_calls` must say so. The plugin's mod may run against an older project jar
without `config`: it must detect that and say "run `/arch-adopt`". The mod also loads in every
project of the person who installed the plugin, and must stay silent where there is no
`.claude/hooks/ArchHook.jar`. A second repository to update, shipping after the source tag.
Moving the three declarations is a change to three `doctor` readers.

**Points cut in the rubric:** 5 (maintenance: a mode, a guard rule, a skill, a mod, a second
repository); 8 (trust surface: a mod that writes through a process).

**CI:** `validate · hooks-cross-platform` — new `.claude/.ci/ProjectConfigTest.java` (`config
list` prints every registered file; `set` accepts a valid value and refuses an unknown key, an
out-of-shape value and `arch-adopt` in `audited`; `unset` restores the default), new cases in
`BashGuardTest` and the write-guard test (a registered path refused, message names `config
set`); `DoctorGateTest` unchanged and still green; `validate · design › frontmatter schema`
covers the skill's class, sections and export entry; `templates · mods` runs `claude plugin
validate`/`test` on `nerviz-config`, stubbing `config list`, an old jar and a missing jar.

### Option B — as A, mod shipped by `export` (score 4)

Every person who clones a project would run the mod. Takes on, inside every generated
project, 0131's risks 1 (TypeScript with no test there — `ci.yml.example` has no Node and
cannot call `.claude/.ci/`) and 3 (an unsandboxed in-process program its owner never
reviewed), reverses 0131's axis 8, and adds nothing under Copilot, VS Code or a cloud agent,
where no mod loads. **CI:** as A, plus nothing that can run inside the project.

### Option C — as A without the mod (score 7)

Everything A guarantees, without a second repository or a mod. Leaves request 2 unanswered.
**CI:** as A minus the `mods` job.

### Option D — a documentation page only (score 3)

Answers "did not know it existed". A wrong key still waits for `doctor`; a fourth override
still reinvents shape, validator and docs. **CI:** nothing testable.

## References

| Claim | Source |
|---|---|
| A project-owned file survives `/arch-adopt` because `export` never names it | `0111` § Found while designing |
| The three files and their `doctor` lines | `extensions.json` `audit.project_overrides`, `audit.plan_limits`, `compose.project_overrides`; `ArchHook.java` `doctor` |
| Mods stay out of `export`; their risks | `0131` axis 8, cons 1-4; `claude-code-architect-designer` § Out of scope |
| A mod is unsandboxed and reaches every file and secret | `docs/en/claude-code-docs/16-mods.md` § Security and governance |
| No mod draws in `-p`, VS Code, cloud; none loads under `--safe-mode`, untrusted workspace, `allowManagedModsOnly` | `16-mods.md` § Where it runs, § Turning off |
| Copilot reads `.claude/skills/` and `.claude/settings.json` hooks; `disable-model-invocation` unverified outside VS Code | `docs/pt-br/13-github-copilot.md` § 2.3, § 2.5, line 208 |
| A mod writes no file; `java` is the only program it starts | `extensions.json` `mods.forbidden_calls`, `mods.process_allow` |
| The plugin vendors pieces through `sync.sh` and ships after the source tag | `0163`; `nerviz-ai/marketplace` `sync.sh` |
| A list a hook reads lives in `extensions.json` | `@CLAUDE.md` invariant 10 |

## Propagation

Phase 1 only.

| File | Change |
|---|---|
| `.claude/schemas/extensions.json` | New `project_config` block (`files.audited`, `files.plan-limits`, `files.compose-gate`: `base`, `path`, `validator`, `doctor_label`, `summary`, `decision`, `keys`); `audit.project_overrides`, `audit.plan_limits` and `compose.project_overrides` removed, their comments pointing at the block; `project-config` in `skill_classes.ops` (comment extended) and in `export.skills.include` |
| `.claude/hooks/ArchHook.java` | New `config` mode (`config`, `configList`, `configWrite`, `configValue`, `configRemove`, `configExpand`, `configDefault`, `configFixed`, `configAvailable`) and the `projectConfig*` helpers; the three validators split into content-based `auditOverrideContentProblems`, `planLimitContentProblems` and a generic `keysContentProblems` (the compose file's keys and values now data), dispatched by `projectConfigProblems`; every reader of the three paths goes through the registry; `doctor` reads its three labels from it; `guardViolations` rule 0 refuses a tool's write into a registered path; `guardSweep` skips them; a throw in `config` exits 1; the compose gate's block names `config set` |
| `.claude/hooks/ArchHook.jar` | Rebuilt under JDK 21; `build --verify` passes |
| `.claude/skills/project-config/SKILL.md` | New — Form 2, class `ops`, injects `config list` |
| `.claude/skills/audit-usage/SKILL.md` | The two places that told the person to write the JSON now name `config set` |
| `.claude/.ci/ProjectConfigTest.java` | New |
| `.claude/.ci/DoctorGateTest.java` | The in-force text of `Compose overrides` is the generic `` `exited` = "warn" `` |
| `.github/workflows/validate.yml` | New step in `hooks-cross-platform` |
| `docs/{en,pt-br}/16-project-config.md` | New — the settings, the command, other platforms, how to add one |
| `docs/{en,pt-br}/07-ci-validate.md` | The new step's row |
| `docs/{en,pt-br}/08-audit-usage.md` | `audited.json` points at `project_config` and `config set` |
| `docs/{en,pt-br}/11-pitfalls.md` | Guard refuses a direct edit of a project setting; the compose `exited` line names `config set` |
| `docs/{en,pt-br}/README.md`, `README.md` | Index rows; the `config` row of the mode table |
| `CLAUDE.md` | `config` in § Commands; a routing row for `/project-config` |
| `claude-help.md` | The audit line names `project_config` and `config set` |

Not touched: `.claude/settings.json` and `project-bootstrap/templates/settings.json.example` —
`guard write` and `guard bash` are already registered in both, and `config` runs on no event.
No restart is needed: every hook launches the jar. No `migrations` entry: a file a project
already wrote keeps its meaning and its path.

Goes to the generated project: **yes** — `ArchHook.java`, the jar, `extensions.json` and the
`project-config` skill travel through `export`; the project's own setting files survive it.
The mod of phase 2 does not.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · hooks-cross-platform › config writes only valid project settings, and guard leaves it the only writer` (`ProjectConfigTest`) | `list` names every file and expands key patterns; `set` writes valid values and refuses, leaving the file untouched, an unknown key, an out-of-set value, a skill with no file, `skills.arch-adopt`, a negative budget and a setting of a switched-off trail; `unset` deletes an emptied file; `guard write`/`guard bash` refuse each registered path naming `config set`; `guard sweep` passes `config`'s write under a `/project-config` phase and still catches a stray file | Green on the tree. Red three ways, each failing by name: sweep skip removed (1 case), guard rule 0 disabled (8 cases), the validator's refusal in `set` disabled (7 cases) |
| `validate · hooks-cross-platform › doctor gate fails on every gated line and only on those` (`DoctorGateTest`) | `Audit overrides` and `Compose overrides` still gate, now read through the registry | Green |
| `validate · hooks-cross-platform › audit renders …` (`AuditRenderTest`) | `audited.json` and `plan-limits.json` still read from the trail directory | Green |
| `validate · design › frontmatter schema` | The skill's frontmatter, class `ops`, its required sections, its `export` entry and its injection path | Green; `claude plugin validate .claude/skills` passes |
| Generated project `ci.yml.example` › `doctor gate` | A malformed setting file fails CI where no hook runs | Already present, nothing added |

Also green after the change: `BashGuardTest`, `SkillTerritoryTest`, `AgentTerritoryTest`,
`GuardStatusTest`, `SweepTest`, `ComposeGateTest`, `InjectionPathTest`,
`ExportProvenanceTest`, `MigrationsSchemaTest`, `ModsSchemaTest`, `FrontmatterYamlTest`.
