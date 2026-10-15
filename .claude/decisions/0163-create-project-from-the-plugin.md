# 0163 · Creating a project from the plugin, without a clone

- **Date:** 2026-10-15
- **Scenario:** a person who installed the `nerviz` plugin from the marketplace can adopt an
  existing project (`/nerviz:arch-adopt`) but cannot create one: `/init-project` needs a clone
  of this repository and a session opened in it. Four mechanisms to evaluate, rejected ones
  included, each with its criteria: (A) a creation skill in the plugin that fetches the tarball
  into `$SRC` and runs `project-bootstrap` from it; (B) a creation mode in `arch-adopt`; (C) an
  `ArchHook.java init` mode doing the deterministic part, a skill doing the interview; (D)
  create nothing and reduce the clone's friction.
- **Decision:** Form 2 — `.claude/skills/new-project/SKILL.md`, vendored by the plugin as
  `/nerviz:new-project`; `export` (an existing Form 7c mode) reads the fetch marker
  `source.fetch_marker` names. Option A2.
- **State:** approved by Lucas Fernandes, on 2026-10-15

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | Observed: a person arriving from the plugin has no clone, and the plugin only adopts | — (it is a case, not anticipation) |
| 1 — cause | The weight of the first contact (clone, session in the clone, `/add-dir`, a second session), and a future private source: `source.base_url` moving to a licensing endpoint that is not a git remote, where `git clone` stops existing for the user | D as a final answer — documentation does not survive a source that cannot be cloned |
| 2, 3 — trigger, frequency | External users of the marketplace, once per project, their first contact | — |
| 7 — mandatoriness | Generating without this repository's `guard` active is acceptable: the target is an empty directory, and `schema`, `doctor` and `verify` run at the end | Does not force C |
| 8 — destination | The creation piece never travels into the generated project | B — `arch-adopt` is in `export.skills.include` |
| 9 — integration | The plugin lives in `nerviz-ai/marketplace`; edit its local clone on a branch, no commit | — |
| 10 — cost of error | High: a project that differs from what `/init-project` generates burns the first contact. Parity is mandatory | A1 as a final answer — parity rests on the model translating every path |
| 17 — CI | Answered from `references/ci-coverage.md` and the workflows — per option below | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| A2 | `.claude/skills/new-project/SKILL.md`, vendored by the plugin — fetches the tagged tarball next to where projects live and hands over to the unchanged `/init-project` inside it | 9 | **Approved** |
| D | create nothing — document the clone in the marketplace README | 7 | Viable with a caveat — leaves the clone and dies with a private source |
| A1 | same skill, but the model follows `$SRC`'s `init-project` and `project-bootstrap` inline, in a session rooted at the target | 6 | Viable with a caveat — parity rests on persuasion |
| C | `ArchHook.java init` mode + an interview skill | 4 | Rejected — second owner of generation (invariant 2), network inside the jar, still needs A as vehicle |
| B | creation mode in `arch-adopt` | 3 | Rejected — the creation capability would travel into every project (axis 8, invariant 9) |

### Option A2 — `new-project`: fetch, then hand over to `/init-project` (score 9)

**Motivator:** axis 1 (no clone for the plugin user; a future private source) under axis 10
(parity is mandatory).

**Shape.** A Form 2 skill, `disable-model-invocation: true`, written here and vendored by the
plugin as `/nerviz:new-project`, run in the directory where projects should live:

1. Resolves the source and fetches it with `arch-adopt` § 2 and § 3, **cited, not copied** —
   `${CLAUDE_SKILL_DIR}/../arch-adopt/SKILL.md` resolves in both layouts, since the plugin
   vendors `arch-adopt` already — with `SRC` set to `<cwd>/nerviz-<ref>`, which that section's
   own `SRC=${SRC:-…}` line allows. An existing `<cwd>/nerviz-<ref>` whose marker records the
   same ref is reused (the cache of D); anything else at that path stops the skill. Nothing is
   ever deleted: older `nerviz-*` trees are listed as safe to delete. (Written during Phase 4:
   a directory per ref instead of one `nerviz/` replaced on confirmation, so the skill never
   needs `rm`.)
2. Writes the fetch marker — ref and commit resolved in step 2 of `arch-adopt` — at the path
   `source.fetch_marker` of `extensions.json` names, inside `<cwd>/nerviz-<ref>`.
3. Prints the next two moves and stops: `cd nerviz-<ref> && claude`, then `/init-project`. The
   project is born at `../<artifactId>` = `<cwd>/<artifactId>`, exactly as from a clone:
   **no change to `init-project`, `project-initializer` or `project-bootstrap`.**

`export` (Form 7c, an existing mode) reads the marker when the source root is not the top of
its own work tree and neither `--ref` nor `--commit` was given — so a project generated from a
fetched tree is stamped with a real tag and commit, which a clone today is not (`ref:
working-tree`).

**Pros:** byte-for-byte the same generation as a clone — same `/init-project`, same agent,
same model pins, and this repository's `guard` active during it; no `git clone`, so it holds
when `base_url` becomes a licensing endpoint; the fetch has one owner (`arch-adopt` § 2-3);
the stamp gets a real provenance; the piece never travels (`export.skills.exclude`).

**Cons:** the first contact keeps two sessions (generation in the fetched tree, development in
the project) and the `/add-dir` prompt — it removes the clone, not the second session; a
fetched tree is not a git repository, so the hooks that read `git` (`guard sweep`, `tests`)
are inert there, which axis 7 accepted; a coupling — an edit to `arch-adopt` § 2-3 changes
this skill's behavior too; a second vendored file in the marketplace, which ships only after
the source tag that contains it.

**Points cut in the rubric:** 5 (maintenance) — a cross-skill citation, a marker read by
`export`, and a second repository to update.

**When to use:** the person has no clone and should not need one; the source may stop being a
git remote; parity with `/init-project` is mandatory. **When not to:** a maintainer who already
has a clone (`/init-project` directly); a session that must end with the project fully
generated without a second launch — then nothing here helps, see A1's criteria.

**CI:** `validate` › `design` › `frontmatter schema` covers the skill (class, sections, model,
`export.skills.exclude`); `validate` › `export-determinism` › `the exported tree carries
nothing that only exists here` gains `.claude/skills/new-project` in its forbidden list; the
marker gets cases in `.claude/.ci/ExportProvenanceTest.java` (`hooks-cross-platform`): a
non-git source with a marker stamps its ref and commit; without one, `working-tree`/`unknown`
as today; `--commit` given wins over the marker; a malformed marker commit is refused like a
malformed `--commit`. In `nerviz-ai/marketplace`, `validate.yml` › `vendored copies match
their source` and `every skill the plugin ships has frontmatter` loop over `files` and
`skills/*` already. `mods.yml` is untouched — no mod.

### Option D — create nothing (score 7)

**Shape.** A "Create a project" section in the marketplace README pointing at the clone; the
clone is already `--depth 1` in `README.md`; "cache" is `git pull` in that clone.

**Pros:** zero pieces; the clone keeps `guard` active and parity is exact.

**Cons:** does not remove the clone or the weight of the first contact; with a private
source, the instruction becomes a `curl` + `tar` + header recipe a human runs by hand, with
the stamp's commit `unknown` — the failure of issue #123 by hand.

**Points cut:** 1 (form fit — an observed multi-step procedure is a skill, § 2), 4 (a
provenance a program could resolve is left to a person), 5 (two READMEs describing a path
that stops working when the source goes private).

**When to use:** the source stays a public git remote and the audience is mostly maintainers.
**When not to:** first contact from the plugin, or a source that is not a git remote.

**CI:** nothing testable — prose in two READMEs.

### Option A1 — the same skill, generation inline (score 6)

**Shape.** The plugin skill fetches into `$SRC` and the model follows
`$SRC/.claude/skills/init-project/SKILL.md` and `project-bootstrap` as files, in the session
rooted at the target directory.

**Pros:** one session until the restart that loads the project's hooks.

**Cons:** nothing of `$SRC/.claude/` is loaded in that session — no `project-initializer`
(only vendoring it as a namespaced plugin agent, whose `hooks` are ignored), no `Skill` call
to `project-bootstrap`, no model pin, no `guard`; the `!` injections of `init-project` do not
run when it is read as a file, and `${CLAUDE_PROJECT_DIR}` there names the target; every
relative path of `project-bootstrap` and its `references/` assumes the session root is the
source, so either they are all rewritten to be location-agnostic or the model translates
them on every run — parity by persuasion, against axis 10.

**Points cut:** 4 (persuasion where the clone flow has `guard` and the agent), 5 (dozens of
paths made location-agnostic, or translated each run), 6 (no precedent: `arch-adopt` reads
one template from `$SRC`, never a whole procedure), 8 (a fetched procedure writing `**`
unguarded).

**When to use:** a later need for a single-session creation, after `project-bootstrap` and
`init-project` resolve every path from `${CLAUDE_SKILL_DIR}` instead of the session root —
a refactor worth its own decision. **When not to:** while parity is mandatory and those paths
assume the session root.

**CI:** parity is not testable in CI — it needs a model run; `schema` covers the skill only.

### Option C — `ArchHook.java init` (score 4)

**Shape.** A new mode fetches the source, calls Spring Initializr, creates the blueprint's
packages and runs `export`; a skill keeps the interview.

**Cons:** the deterministic part is a fraction of the generation — restructuring into modules
with the exemplars, Checkstyle, `CLAUDE.md`/`AGENTS.md`, README and the verify loop stay prose
in `project-bootstrap`, so the Initializr call would have two owners (invariant 2, cap ≤ 4);
a jar that fetches over the network is a new trust surface (`export` is offline, `arch-adopt`
fetches with `curl`); and the plugin cannot run a jar it has not fetched yet, so C still needs
A as its vehicle.

**When to use:** if `project-bootstrap` one day becomes fully template-driven, with no step a
model judges — then the whole generation is a mode and parity is a test. **When not to:**
while any generation step needs a model.

**CI:** a new `.claude/.ci/InitModeTest.java` needing the network, in `templates.yml`.

### Option B — creation mode in `arch-adopt` (score 3)

**Cons:** `arch-adopt` travels into every generated project (`export.skills.include`), so
every project would carry a creation capability the `exclude` list keeps out on purpose
(axis 8, invariant 9, cap ≤ 4); its `build` override would grow from `.claude/**` to `**` in
every update run; its preconditions are the opposite (a clean git worktree versus an empty
directory); and the skeleton is `project-bootstrap`'s — a second owner (invariant 2), or a
read from `$SRC`, which is A1 in another file.

**When to use:** never as long as `arch-adopt` travels. **When not to:** see above.

**CI:** a `SkillTerritoryTest` case for the widened territory, and `export-determinism`.

## References

| Claim | Source |
|---|---|
| The plugin vendors only `arch-adopt`, through `.plugin-source.json` › `files` and `sync.sh` | `nerviz-ai/marketplace` › `.plugin-source.json`, `README.md` |
| Fetching the source by tarball with an optional header, and resolving tag and commit, has an owner | `.claude/skills/arch-adopt/SKILL.md` § 2, § 3 |
| `source.base_url` is an indirection meant to move to a licensing endpoint | `.claude/schemas/extensions.json` › `source.$comment`; `0054` |
| The project is born at `<this repository>/../<artifactId>` | `.claude/skills/project-bootstrap/SKILL.md` § Where the project is born |
| `export` stamps `ref: working-tree` without `--ref`, and the source's HEAD only when the source root is its own work tree's top | `ArchHook.java` › export argument parsing, `sourceCommit()`; `0141` |
| A creation skill never travels | `@CLAUDE.md` invariant 9; `export.skills.exclude` |
| A skill reading another skill's section by path has precedent | `.claude/skills/init-project/SKILL.md` step 2 (reads `transport-security-setup` § 2) |
| Plugin skills are namespaced; plugin subagents ignore `hooks` | `@claude-help.md` § 5, § 7, § 10 |
| `--add-dir` loads `<dir>/.claude/skills/` but not that directory's settings or hooks | `@claude-help.md` § 5 (Where they live) |
| A mode with no lifecycle event behind it has precedent | `export`, `compose`, `audit summary` |

## Raised during Phase 4 — the README outside `meta`'s territory

The README edit this option names was refused by `guard bash`: `README.md` was not in
`skill_classes.meta.write_allow`. A first edit had gone in through a `python3` heredoc, which
`guard bash` does not read as a write — `guard sweep` is the backstop for that shape. Records
`0144`–`0162` list the README in their propagation, so the designer has written it all along,
unguarded. Two options were put to the user, each with its trade-off: widen `meta` to
`README.md` (the territory then says what the skill does; one more path) or revert and hand
the README diff over (territory unchanged; the README stale until someone applies it).
**Approved: widen**, by Lucas Fernandes, on 2026-10-15 — the README carries the piece counts
and creation-only list every new skill changes, and nothing else at the root but `CLAUDE.md`
and `.mcp.json` joins it.

## Propagation

| File | Change |
|---|---|
| `.claude/skills/new-project/SKILL.md` | New — Form 2, class `ops` |
| `.claude/schemas/extensions.json` | `skill_classes.ops.skills` += `new-project`; `export.skills.exclude` += `new-project`; `source.fetch_marker` and its `$comment`; `skill_classes.meta.write_allow` += `README.md` |
| `.claude/hooks/ArchHook.java` · `ArchHook.jar` | `export` reads the marker (`fetchMarker`) when the source is not its own work tree, for the field the command line left out; usage text. Jar rebuilt under JDK 21 |
| `.claude/.ci/ExportProvenanceTest.java` | Seven marker cases |
| `.claude/.ci/SkillTerritoryTest.java` | Three `meta` cases: phase opens, `README.md` allowed, another root file blocked |
| `.github/workflows/validate.yml` | `export-determinism` › forbidden list += `.claude/skills/new-project`; comment of the `ExportProvenanceTest` step |
| `.claude/skills/claude-code-architect-designer/SKILL.md` | `## Contract` names `README.md` in the territory and in **Writes** |
| `CLAUDE.md` | Routing row |
| `README.md` | § New project — the plugin path beside the clone; skill count 30; `new-project` in the creation-only list |
| `docs/{en,pt-br}/02-init-project.md` | § Where the project is born — **Without a clone** |
| `docs/{en,pt-br}/07-ci-validate.md` | The `ExportProvenanceTest` row |
| `nerviz-ai/marketplace` (local clone, branch `feat/new-project` from `origin/main`, not committed) | `.plugin-source.json` › `files` += `skills/new-project/SKILL.md`; the vendored copy; `README.md` (§ Create a project, two skills); descriptions of `plugin.json` and `marketplace.json`. Its `validate.yml` drift check fetches at `ref`, which is `v0.33.0`: the new file 404s there until the source cuts the tag carrying it — merge after that tag, with `./sync.sh <tag>` |

Goes to the generated project: **no** — a creation skill, in `export.skills.exclude`.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · hooks-cross-platform › export notes only real overwrites, and never guesses the stamp's commit` | A fetched tree with no marker stamps `working-tree`/`unknown`; with one, its ref and commit; `--commit` and `--ref` each win over their own field; a marker with no sha, or not JSON, exits 2; a marker in a source that is its own work tree is ignored | Green, 14/14 · red with the marker read disabled: 5 cases failed by name |
| `validate · hooks-cross-platform › SkillTerritoryTest` | A `meta` phase admits `README.md` and still refuses another root file | Green · red with `README.md` removed from `meta`: `README.md is meta's — allowed` failed |
| `validate · design › frontmatter schema` | `new-project`'s frontmatter, class `ops`, its `## Procedure`, `## Failure modes`, `## Contract`, `**Unfiltered Bash:**` marker, model `sonnet`, and its `export.skills.exclude` entry | `schema` exit 0 |
| `validate · export-determinism › the exported tree carries nothing that only exists here` | `new-project` never reaches a project | Export of `hexagonal`: no `new-project` under `.claude/skills` |
| `nerviz-ai/marketplace` · `validate.yml` › `vendored copies match their source`, `every skill the plugin ships has frontmatter` | The vendored copy is the source's at `ref` | Pending the source tag; `claude plugin validate .` passes locally |

`mods.yml` is untouched: no mod. Not testable in CI: that `/init-project` in the fetched tree
generates what a clone does — it is the same skill on the same files, which is the design's
claim, and proving it would take a model run.
