# 0139 · AGENTS.md in the generated project: a pointer at the root, the owner per module

- **Date:** 2026-10-11
- **Scenario:** Fase 8, passo 1 do roadmap.md: AGENTS.md no projeto gerado. Escopo: só o passo
  1 ("cheapest mechanism first"), com docs/pt-br/13-github-copilot.md, lessons-learned-019 e
  claude-help.md § AGENTS.md como entradas, e as questões A–F do prompt fechadas com evidência
- **Decision:** templates in `project-bootstrap` (`root.AGENTS.md.example`, `module.AGENTS.md.example`,
  `module.CLAUDE.md.example` reduced to `@AGENTS.md`, `copilot-instructions.md.example`, and a
  `## Under another agent` section in `root.CLAUDE.md.example`), the `agents-md` entry of
  `migrations`, and Form 7c: an `Agent instructions` line in `ArchHook.java doctor`, reading
  `doctor.agent_instructions`, never gating
- **State:** approved by Lucas Fernandes, on 2026-10-11 (option 1)

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — Concrete symptom | None observed: no team on Copilot, Codex or Cursor yet. The input is the gap lessons-learned-019 found by comparison, and phase 8 of `roadmap.md` | Weighs against any Java now: rejects the deterministic render (option 4) |
| 2 — Trigger | Generation time (`project-bootstrap` step 6); on update, a `migrations` entry the user pastes | Forms 1–3 — no procedure runs per session |
| 5 — Nature | Declarative facts: stack, commands, invariants, forbidden imports per module | Forms 1/2/3 |
| 7 — Mandatoriness | Guidance; the consistency check only reports, never gates (user's answer) | `doctor.gate.labels` entry; any blocking hook |
| 8 — Destination | The generated project only. Delivered by `project-bootstrap`; `arch-adopt` does **not** deliver the module files (user's answer 1-A); a project already generated gets them through a `migrations` entry | An `export` entry for the module files |
| 9 — Integration | Six pieces read or write the root `CLAUDE.md` by name: `archunit-installer` and `commons-logging-installer` (Read it explicitly under `omitClaudeMd`, for the `commons` package), `messaging-architect` (bounded context), `transport-security-setup` (writes and greps `**Transport:`), `arch-adopt` step 8 (writes the bounded-context line), `project-bootstrap/references/verify-and-report.md` | Moving the root facts out of `CLAUDE.md` (option 2) retargets all six |
| 10 — Cost of getting it wrong | Minutes: a Markdown file a person reads. Getting the honesty block wrong misleads a Copilot user into editing a frozen spec — lessons-learned-019 § Honesty | — |
| — Overwrite on update | Write only what is missing (user's answer 2-A); a future blueprint change to a module's restrictions ships its own `migrations` entry | Overwrite semantics, which need a deterministic writer |
| 17 — CI coverage | `schema` already covers the `migrations` entry (`MigrationsSchemaTest`); nothing covers a doctor line over `AGENTS.md` | — |

## Reproduced on disk

| Claim | Evidence |
|---|---|
| A module `AGENTS.md` with no `CLAUDE.md` next to it is **not** read by Claude Code when the root has a `CLAUDE.md` | Claude Code 2.1.296, scratch repo: root `CLAUDE.md` + `orders/AGENTS.md`; `claude -p` reading `orders/data.txt` received no `AGENTS.md`. The docs leave this case open ("a subdirectory's `AGENTS.md`, when Claude opens a file there with the Read tool and that subdirectory has none of the three `CLAUDE.md` files of its own" — code.claude.com/docs/en/memory) |
| A module `CLAUDE.md` holding only `@AGENTS.md` loads lazily, with the import expanded | Same run, second repo: on reading `orders/data.txt`, both `orders/CLAUDE.md` and `orders/AGENTS.md` arrived with the Read result. "Relative paths resolve relative to the file containing the import" (same page) |
| The same import loads on Edit and on Write under the module, and inside a subagent; the root `AGENTS.md` is never loaded while the root has a `CLAUDE.md` | Claude Code 2.1.296, three more scratch repos with a canary per file, asked after the approval to answer "does what works under Claude Code today keep working": Edit → root `CLAUDE.md` + module `AGENTS.md`; Write → the same; a `general-purpose` subagent reading a module file → the same, the module pair arriving with its Read result; the root `AGENTS.md` canary absent in all four runs. The option depends on `@import`, not on the native `AGENTS.md` reading of v2.1.277, so an older Claude Code loads it the same way |
| Nothing that enforces depends on a `CLAUDE.md` | `check` reads `.claude/forbidden-imports.txt`; `guard`, `audit`, `compose` and `context subagent` read `extensions.json` and the transcript; no `InstructionsLoaded` hook in `settings.json.example`; `project-bootstrap` writes under `write_allow: ["**"]`; the two installers Read the root `CLAUDE.md`, which stays the owner |
| The `claude-md-and-agents-md` mode cannot be set by the project | "Claude Code reads the entry from `~/.claude/settings.json`, a `--settings` file, or managed settings, and ignores it in project and local settings files" (same page; key `pluginConfigs` › `cc-plugin-agents-md@builtin` › `options.instructionFiles`, v2.1.285+) |
| Copilot CLI and VS Code add every instruction file up; neither picks one | CLI: "Copilot CLI combines their instructions … does not define a general precedence order between these files". VS Code: "Applicable instruction sources are additive" (docs.github.com/…/copilot-cli/…/add-custom-instructions; code.visualstudio.com/…/custom-instructions) |
| The cloud agent reads `AGENTS.md` **or** `CLAUDE.md`; which one wins when both exist is not documented | "Agent instructions (using AGENTS.md, CLAUDE.md or GEMINI.md files)"; "Alternatively, you can use a single `CLAUDE.md` or `GEMINI.md` file stored in the root of the repository" (docs.github.com/…/custom-instructions-support; …/add-repository-instructions) |
| Five chat surfaces read only `.github/copilot-instructions.md` | GitHub.com Chat, Visual Studio, JetBrains, Xcode, Eclipse (docs.github.com/…/custom-instructions-support). GitHub.com Chat is one more than `docs/*/13-github-copilot.md` lists |
| VS Code does not expand `@path`; Markdown links only, behind `chat.includeReferencedInstructions` (default `false`) | code.visualstudio.com/…/custom-instructions; …/copilot-settings |
| **The generated project's CI does not refuse a forbidden import until ArchUnit is installed** | `doctor`'s `Boundaries` line counts the rules in `.claude/forbidden-imports.txt` and reads no source (`ArchHook.java`, `report("Boundaries", …)`); `ci.yml.example`'s `boundaries` job runs only `doctor gate`; `pom.parent.xml.example` carries no ArchUnit — `test-architect` setup installs it, chained by the first `/new-feature-implement`. `docs/*/13-github-copilot.md` § 7 row 1 says "garantido" from day one: it is not |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | Root `CLAUDE.md` stays the owner; root `AGENTS.md` is a pointer; per module, `AGENTS.md` owns and `CLAUDE.md` is `@AGENTS.md`; `.github/copilot-instructions.md` pointer; `Agent instructions` doctor line (report only); `migrations` entry | 8 | **Approved** |
| 2 | Root `AGENTS.md` owns the portable facts; `CLAUDE.md` = `@AGENTS.md` + Claude Code routing; same module shape | 5 | Rejected — six pieces retargeted, and the cloud agent regresses on the undocumented branch where it reads `CLAUDE.md` alone |
| 3 | Module files only — no root `AGENTS.md`, no `copilot-instructions.md` | 7 | Rejected — two static files would reach the agents that read only `AGENTS.md` and five chat surfaces |
| 4 | Option 1 with the module files rendered by `export` (Form 7c), overwritten on update, delivered by `arch-adopt` too | 5 | Rejected in the interview — 1-A and 2-A; Java for a team that does not exist yet |
| 5 | Create nothing | 4 | Rejected — fails the honesty constraint of lessons-learned-019 |

### Option 1 — root pointer, module owner (score 8)

**Motivator:** fact 1 (every Copilot surface already reads the root `CLAUDE.md`) and fact 2
(only `AGENTS.md` nests across both runtimes), with axis 9 (six pieces bound to the root
`CLAUDE.md`).

**Shape in the generated project:**

```
<project>/
├── CLAUDE.md                     owner of the root facts — gains "## Under another agent"
├── AGENTS.md                     pointer: read CLAUDE.md, norms in .claude/rules/, module AGENTS.md
├── .github/copilot-instructions.md   one Markdown link to CLAUDE.md, for the five chat surfaces
├── domain/
│   ├── AGENTS.md                 owner: allowed deps, forbidden imports, base package, rule path
│   └── CLAUDE.md                 @AGENTS.md
└── application/
    ├── AGENTS.md
    └── CLAUDE.md                 @AGENTS.md
```

Who reads what:

| Reader | Root | Module |
|---|---|---|
| Claude Code | `CLAUDE.md` (`AGENTS.md` ignored: a `CLAUDE.md` is on the path) | `CLAUDE.md` → import expands `AGENTS.md` (reproduced) |
| Copilot CLI | `CLAUDE.md` + `AGENTS.md` + `copilot-instructions.md`, combined | nearest `AGENTS.md` |
| Copilot cloud agent | `AGENTS.md` and/or `CLAUDE.md` (undocumented); either way it reaches the facts — directly, or by the pointer's one instruction | nearest `AGENTS.md` |
| VS Code | all three, additive | nested `AGENTS.md` only with `chat.useNestedAgentsMdFiles` (experimental, off) |
| Copilot code review | `CLAUDE.md` and `AGENTS.md` (head branch) | — |
| Chat in GitHub.com, Visual Studio, JetBrains, Xcode, Eclipse | `copilot-instructions.md` → link to `CLAUDE.md` | — |
| Codex, Cursor, Gemini CLI, Jules, Aider | `AGENTS.md` → pointer | nearest `AGENTS.md` |

Answers to the scenario's questions:

- **A.** `CLAUDE.md` owns the root (shape (b)). Shape (a) loses on two counts: a cloud
  agent that picks `CLAUDE.md` alone would get the routing and a dead `@AGENTS.md` and miss
  the commands and invariants — the main target regresses on an undocumented branch — and
  the six pieces of axis 9 would all retarget. Shape (b)'s risk is the symmetric branch
  (cloud agent picks `AGENTS.md` alone) and costs one hop the pointer states in plain words.
- **B.** Module = `AGENTS.md` (owner) + `CLAUDE.md` containing only `@AGENTS.md`. The
  setting route is closed: project settings ignore it, and the lone `AGENTS.md` is not read
  (reproduced).
- **C.** The honesty block lives in the root `CLAUDE.md` — the one file every Copilot surface
  and Claude Code read — as a short `## Under another agent` section of
  `root.CLAUDE.md.example`. Its content, worded in Phase 4: only the build is guaranteed
  (Checkstyle, JaCoCo, and ArchUnit **once `test-architect` installed it**); write
  territories, frozen specs, the `deny` list, the audit trail, the pattern catalog and the
  edit-time `check` are Claude Code hooks — under the Copilot CLI they may run and are not
  verified, under the cloud agent and every other agent they do not run; a skill whose
  routing row says "manual only" is manual only under Claude Code (and VS Code), so invoke
  one only when the user names it — `arch-adopt` rewrites `.claude/`; the cloud agent has
  no user, so a skill that asks (`AskUserQuestion`) or confirms (`git-publish`) cannot
  finish there. No list of skill names — the routing table already marks them.
- **D.** The module content still comes from the blueprint at generation time
  (`references/project-files.md` § 6), rendered once, into the module `AGENTS.md` only.
  `module.CLAUDE.md.example` becomes `module.AGENTS.md.example` with one sentence changed:
  the `check` hook blocks at edit time **under Claude Code**; under any agent, ArchUnit does
  once installed.
- **E.** Templates: `root.AGENTS.md.example`, `module.AGENTS.md.example`,
  `module.CLAUDE.md.example` (one line), `copilot-instructions.md.example`, and the new
  section in `root.CLAUDE.md.example`. No `export` entry: these are project files that
  `project-bootstrap` step 6 renders, like the root `CLAUDE.md` (answer 1-A). `arch-adopt`
  unchanged. `migrations` entry `agents-md`, every blueprint id, whose prompt converts each
  module `CLAUDE.md` into the pair, writes the root `AGENTS.md` and
  `.github/copilot-instructions.md`, and adds the section — taking the wording from the
  fetched `root.CLAUDE.md.example`, not from the prompt, so the text has one owner. Proof:
  an `Agent instructions` line in `doctor`, never in `gate.labels`, reading
  `doctor.agent_instructions` in `extensions.json`: root `AGENTS.md` present; for every
  module of the active blueprint (the travelling copy, id from the provenance stamp) with
  non-empty `forbidden_imports` whose directory exists, `<path>/AGENTS.md` lists every
  prefix and `<path>/CLAUDE.md` imports it; every path cited in backticks in those files
  exists. Silent in this meta-repository (no stamp). Plus one row in `verify-and-report.md`'s
  autonomy check.
- **F.** Yes. One static line, no placeholder, no derivation, and the only file five chat
  surfaces read. A Markdown link to `CLAUDE.md`, not `@` — the CLI would expand an `@` into a
  second copy of a file it already reads.

**Pros:**
- No reader loses anything it has today; every Copilot surface gains the module context the
  cloud agent and the CLI read natively.
- Invariant 2 holds by construction: two owners, two scopes (root facts, module facts), and
  three pointers that carry no fact.
- No piece outside `project-bootstrap` changes; no Java beyond one report line.

**Cons:**
- Codex, Cursor and the others reach the root facts through one hop.
- The `## Under another agent` section is in the project's own `CLAUDE.md`, which `export`
  never rewrites: when phase 8 step 5 makes the CLI guard verified, that text needs its own
  `migrations` entry. Recorded here so step 5 does not forget it.
- Adopted projects get none of it (1-A), by choice.

**Points cut in the rubric:** 5 (maintenance) — four templates, a doctor line, a test and a
migration for a team that does not exist yet; half a point for the undocumented cloud-agent
branch.

**CI:** a new `.claude/.ci/AgentInstructionsTest.java`, step in `validate.yml` ›
`hooks-cross-platform`: builds a project tree with a blueprint copy and a stamp, runs
`java -jar .claude/hooks/ArchHook.jar doctor`, and requires the line green; then removes a
prefix, drops the module `CLAUDE.md`, and cites a missing path, and requires each to be
named. `MigrationsSchemaTest` already covers the new entry; `schema` the new data block.

### Option 2 — `AGENTS.md` owns the root (score 5)

Shape (a) of lessons-learned-019. Non-Claude agents read the root facts with no hop. Cut:
invariant 2 strained during the transition (an updated project holds the facts in both files
until the user runs the migration), six pieces retargeted (axis 9 — the two installers Read
the file explicitly under `omitClaudeMd`, where an import does not expand), and the main
target regresses on the undocumented branch where the cloud agent reads `CLAUDE.md` alone.
**CI:** the same test as option 1, plus every retargeted piece's own coverage.

### Option 3 — module files only (score 7)

Fact 1 taken literally: a root `AGENTS.md` adds nothing for Copilot. Saves two static files.
Cut: Codex, Cursor and the others get no root entry, and the five chat surfaces get nothing —
for the cost of two files with no placeholder. **CI:** the same test, without the root check.

### Option 4 — deterministic render in `export` (score 5)

The module files rendered by `export` from the blueprint, overwritten on update, delivered by
`arch-adopt`; provenance digests would then name a hand edit for free. Rejected by axis 1 and
answers 1-A/2-A: Java, a write outside `.claude/` on every update, and a wider `arch-adopt`
territory, before any team asked. The upgrade path from option 1 when one does.

### Option 5 — create nothing (score 4)

The root `CLAUDE.md` already reaches every Copilot surface. Cut: no module context outside
Claude Code (fact 2), and no reader is told that only the build holds — which is the honesty
constraint of lessons-learned-019, and fails it.

## Out of scope — later steps of phase 8

- Deriving `rules/` into `.github/instructions/*.instructions.md` (step 3).
- `.github/agents/*.agent.md` (step 4).
- The guard in `.github/hooks/*.json` and its payload adapter (step 5) — which owes the
  `migrations` entry above.
- `copilot-setup-steps.yml`.
- Making the 14 manual skills safe under Copilot: here only the sentence in the section.
- **Found on the way, not decided here:** a CI step that runs `ArchHook.java check` over every
  committed `.java` file would refuse a forbidden import from any agent from day one, before
  ArchUnit exists. Cheap, but a separate change to `ci.yml.example`.

## References

| Claim | Source |
|---|---|
| Claude Code reads `AGENTS.md` only with no `CLAUDE.md` on the path; `@AGENTS.md` inside `CLAUDE.md` is the preferred sharing | `@claude-help.md` § AGENTS.md; code.claude.com/docs/en/memory |
| What each Copilot surface reads | `@docs/pt-br/13-github-copilot.md` §§ 1–2, 7, 8, and the rows of § Reproduced on disk |
| One owner per norm; pointers carry no fact | `@CLAUDE.md` invariant 2 |
| Module content from the blueprint at generation time | `@CLAUDE.md` invariant 7; `project-bootstrap/references/project-files.md` § 6 |
| Every cited path exists inside the project | `@CLAUDE.md` invariant 9 |
| A convention change that leaves existing projects behind is a `migrations` entry, printed by `arch-adopt`, never run | `.claude/schemas/extensions.json` › `migrations`; `arch-adopt` step 9 |
| A report-only doctor line over versioned files | `doctor.uc_references` (precedent) |
| The honesty and cheapest-first constraints | `.claude/lessons-learned/lessons-learned-019.md` |

## Propagation

| File | Change |
|---|---|
| `.claude/skills/project-bootstrap/templates/root.AGENTS.md.example` | New — the pointer |
| `.claude/skills/project-bootstrap/templates/copilot-instructions.md.example` | New — one Markdown link to `CLAUDE.md` |
| `.claude/skills/project-bootstrap/templates/module.AGENTS.md.example` | Renamed from `module.CLAUDE.md.example`; the `check` sentence now says Claude Code only, and ArchUnit once installed |
| `.claude/skills/project-bootstrap/templates/module.CLAUDE.md.example` | Reduced to `@AGENTS.md` |
| `.claude/skills/project-bootstrap/templates/root.CLAUDE.md.example` | `## Under another agent`; the module-structure sentence names the pair |
| `.claude/skills/project-bootstrap/SKILL.md` | Template list, step 6 heading, `Writes` |
| `.claude/skills/project-bootstrap/references/project-files.md` | § 6 writes the root pointers and the module pair |
| `.claude/skills/project-bootstrap/references/verify-and-report.md` | The `Agent instructions` line must be ✅ before reporting |
| `.claude/blueprints/*/*.yaml` (8) and `_schema.md` | `module_docs` points at `module.AGENTS.md.example`; the `forbidden_imports` row names the pair |
| `.claude/schemas/extensions.json` | `doctor.agent_instructions`; `migrations` entry `agents-md`. The blueprint path is split into `blueprint_dir` + `blueprint_file`: spelled whole, `.claude/blueprints/` is the export residue marker, and the first run of `export` reported it as a dead citation |
| `.claude/hooks/ArchHook.java` + `ArchHook.jar` | `doctorAgentInstructions()` and `yamlModuleForbidden()`; jar rebuilt |
| `.claude/.ci/AgentInstructionsTest.java`, `.github/workflows/validate.yml` | New test, step in `hooks-cross-platform` |
| `docs/{pt-br,en}/13-github-copilot.md` | § 1 (code review reads `CLAUDE.md`; GitHub.com Chat), § 3 and § 7 (build: ArchUnit only once installed, `doctor gate` reads no code; module context row), § 8 item 3 corrected, item 8 added |
| `docs/{pt-br,en}/11-pitfalls.md` | Part 1, `CLAUDE.md` and `AGENTS.md` |
| `docs/{pt-br,en}/04-arch-doctor.md`, `docs/{pt-br,en}/07-ci-validate.md`, `docs/{pt-br,en}/05-blueprints.md`, `README.md` | The new line, the new test, the module pair |
| `roadmap.md` | Phase 8 step 1 checked, with the migration step 5 owes |

Goes to the generated project: **yes** — the files through `project-bootstrap` step 6, the
doctor line and its data through `export` (`ArchHook.java`, the jar and `extensions.json` travel
whole). `arch-adopt` delivers none of the files (answer 1-A).

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · hooks-cross-platform › doctor checks the agent instruction files against the blueprint, and never gates` | `AgentInstructionsTest`: green on a complete hexagonal fixture with absent adapter directories skipped; names a prefix not listed, a module `CLAUDE.md` missing or not importing, a dead cited path, missing root pointers; silent without a stamp; reads the inline list form; `doctor gate` never names the line | green on the tree · red by name ("a module CLAUDE.md that does not import is named") with the import check removed from the jar |
| `validate › MigrationsSchemaTest` (existing) | The `agents-md` entry's fields, id and blueprint ids | green |
| `validate · export-determinism › export twice per blueprint` (existing) | No dead citation in the exported `extensions.json` — the one that caught `.claude/blueprints/` in the first draft of the data block | green on all 8 blueprints after the split |

Not in CI, on purpose: whether Claude Code loads the module pair. That is the runtime's
behavior, it needs a model and a key, and it varies by run; the five scratch runs above, with
the version, are where to go back when the runtime changes.
