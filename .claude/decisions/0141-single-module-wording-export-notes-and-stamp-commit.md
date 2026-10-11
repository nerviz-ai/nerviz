# 0141 · Single-module wording after 0140, layout-aware agent templates, a truthful Overwrites note and stamp commit

- **Date:** 2026-10-11
- **Scenario:** Issue #123, triaged at f97c5ac: no caminho do arch-adopt de um projeto layout:
  single-module, o project-bootstrap ainda diz que não há enforcement fora do Claude Code (falso
  desde o 0140), a migration agents-md e os templates raiz prometem AGENTS.md de módulo que esse
  layout nunca gera, o export avisa Overwrites sem comparar com o disco, e o stamp registra commit
  unknown
- **Decision:** option 1 — `project-bootstrap` report wording; `{{#if moduleAgentFiles}}` in both root
  templates, defined in `references/project-files.md` § 6; Form 7c changes to `ArchHook.java export`
  (the `Overwrites` note compares with the destination; `--commit <sha>`; `sourceCommit()` trusts
  `rev-parse` only at the top of the source's own work tree); `arch-adopt` resolves the peeled sha
  and passes `--commit`; `migrations` entry `single-module-agent-wording`
- **State:** approved by Lucas Fernandes, on 2026-10-11 (option 1)

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — Concrete symptom | Issue #123, a real `/arch-adopt` update of a single-module project to v0.25.0; every row below verified at `f97c5ac` by `issue-verifier` | "Create nothing" for (a) and (b) |
| 8 — Destination | Both: `project-bootstrap` and its templates (new projects); `arch-adopt` and `export` travel into every project | — |
| 9 — Integration | (a) is `project-bootstrap/SKILL.md`'s final report, mirrored in `docs/*/02-init-project.md`; (b) `root.AGENTS.md.example`, `root.CLAUDE.md.example`; (c)/(d) `ArchHook.java export`; (d) also `arch-adopt` step 2/5 and the consumer `report-issue` step 2 | — |
| — Existing projects | A new `migrations` entry for the four single-module blueprints; the shipped ones are never edited (user's answer) | Editing `agents-md` |
| — Stamp commit | `arch-adopt` resolves the peeled sha and passes `--commit` (user's answer) | Reading the tarball's pax header |
| — Scope | One PR for all four (user's answer) | — |
| 17 — CI coverage | `export-determinism` exports each blueprint but never into an existing tree and never reads `commit`; nothing covers the bootstrap's report text | — |

## Reproduced on disk

From the `issue-verifier` table at `f97c5ac` (= `HEAD` when this design started; no commit since
touches a cited file). Confirmed rows only:

| Claim | Evidence |
|---|---|
| The bootstrap tells the model to write "no enforcement whatsoever until ArchUnit" for single-module, and line 2 says boundaries are "guaranteed only by the hook (inside Claude Code)" | `project-bootstrap/SKILL.md:382-395`; `docs/en/02-init-project.md:169`, `docs/pt-br/02-init-project.md:170`. 0140 updated the templates, not this passage |
| `doctor gate` now fails CI on a forbidden import | `extensions.json` `doctor.gate.labels` (`Boundary violations`); `ci.yml.example:95-96` |
| Neither migration rewords the sentence | `agents-md` prompt "Change nothing else"; `boundary-sweep-in-ci` fixes imports only |
| `agents-md` targets single-module; `root.AGENTS.md.example:19` and `root.CLAUDE.md.example:75-76` and its module-structure sentence assume module `AGENTS.md` files | `extensions.json` `agents-md.blueprints`; the two templates, unconditional |
| A single-module layout never gets a module `AGENTS.md` | `references/project-files.md:62` (only modules with non-empty `forbidden_imports`); the single-module blueprints declare none |
| `export` prints `Overwrites` for every `overwrite` entry without reading the destination | `ArchHook.java` `exportFiles` — the note is added on `group.equals("overwrite")`; `destRoot` is not passed in |
| The stamp records `"commit": "unknown"` on the adopt path | `stamp()` runs `git -C <ROOT> rev-parse HEAD`; `arch-adopt` step 5 sets `CLAUDE_PROJECT_DIR="$SRC"`, an extracted tarball, not a work tree |
| `report-issue` reads `commit` when `ref` is not a tag | `report-issue/SKILL.md:66-73` |
| `ls-remote --tags --refs` returns the tag object for an annotated tag, not the commit | `git rev-parse v0.25.0 v0.25.0^{}` differ |

Static inference from the verifier, kept: with `SRC` inside another git work tree, `rev-parse`
would stamp **that** repository's HEAD — a wrong value instead of "unknown".

Row 3 (partly) is kept as a constraint, not a defect: the stale sentence's location in an existing
project was chosen by the model, so the migration finds it by content.

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | All four: bootstrap wording; templates keyed on "some module has `forbidden_imports`"; the note only for an existing destination that differs; `--commit` plus a top-level guard; migration `single-module-agent-wording` | 8 | **Approved** |
| 2 | Option 1 with the commit read from the tarball (`git get-tar-commit-id`) | 6 | Rejected — needs the `.tgz` as input; `--source` stays uncovered |
| 3 | Text only — (a), (b) and the migration; leave the note and the stamp | 6 | Rejected — `report-issue` still blind on a non-tag ref; the note keeps pointing at empty diffs |
| 4 | Create nothing | 3 | Rejected — every new single-module project starts with false text |

### Option 1 — fix all four (score 8)

**(a) Bootstrap report.** Line 2 of Next steps becomes: until ArchUnit, boundaries are guaranteed
by the `check` hook at edit time (inside Claude Code) and by CI's `doctor gate` sweep of
`.claude/forbidden-imports.txt` (any agent)<, and by the POMs' `depends_on` — multi-module>. The
single-module paragraph says what is true now: no per-layer POM, the compiler enforces nothing,
and outside Claude Code only the CI sweep holds — which in that layout carries the
`dependency_rules` directions step 5 derived — until ArchUnit adds the rest. Mirrored in both
`02-init-project.md`.

**(b) Templates keyed on data, not on a blueprint id (invariant 7).** One condition, defined in
`references/project-files.md` § 6 as "at least one module has non-empty `forbidden_imports`" —
the same fact that decides whether module files are written:
- `root.AGENTS.md.example`: the module bullet under `{{#if moduleAgentFiles}}`; otherwise a bullet
  pointing at `.claude/forbidden-imports.txt`, per package.
- `root.CLAUDE.md.example`: the CI sentence names `.claude/forbidden-imports.txt` instead of "a
  module's `AGENTS.md`" — true in both layouts, no condition; the module-structure sentence gets
  the same `{{#if}}`, with a per-package sentence otherwise.

**(c) `Overwrites` note.** `exportFiles` receives the destination and notes an `overwrite` entry
only when the destination file exists and its content differs from what is written. A fresh tree,
or an unchanged file, prints nothing.

**(d) Stamp commit.** `export` accepts `--commit <sha>` (7–40 hex) and stamps it as given. Without
it, `rev-parse HEAD` is trusted only when `git rev-parse --show-toplevel` is the source root itself;
otherwise `"unknown"`, never a parent repository's HEAD. `arch-adopt` resolves the commit in step 2
with `git ls-remote <git_url> refs/tags/<tag>^{} refs/tags/<tag>` (the peeled line wins; a
lightweight tag has only the second), `refs/heads/main` for `main`, the sha itself for a full sha,
and passes `--commit` in step 5. `--source <path>`: no flag; the guard reads the path's own HEAD.

**Migration `single-module-agent-wording`** — blueprints `clean-architecture-single-module`,
`layered`, `modular-monolith`, `vertical-slice`. The prompt finds by content, anywhere in the root
`CLAUDE.md`, `AGENTS.md` and `README*`: the "no enforcement whatsoever" / "guaranteed only by …
the hook" sentence, the "each module … has its own `AGENTS.md`" bullet and sentence, and the CI
sentence naming "a module's `AGENTS.md`"; rewrites each with the fetched templates' single-module
wording; nothing found → says so and changes nothing.

**Pros:** closes #123 whole; the condition is the data that already drives generation; the stamp
becomes exact for tag, `main` and sha, which is when `report-issue` needs it; no gate or hook
changes behavior.

**Cons:** two Java changes in `export`, which runs on other people's machines; `arch-adopt` gains a
resolution step; one more migration for four blueprints.

**Points cut in the rubric:** 5 (maintenance) — half: a new flag and a template condition;
6 (precedent) — half: the template conditional is precedented (`{{#if features.…}}`), the flag is
not.

**CI:**
- New `.claude/.ci/ExportProvenanceTest.java`, step in `validate › hooks-cross-platform`: export
  into an empty tree → no `Overwrites`; again unchanged → none; after editing the destination's
  `settings.json` → the note names it; `--commit <sha>` → stamped as given; a copy of `.claude/`
  nested in another git work tree, no flag → `"unknown"`, not the outer HEAD; a malformed
  `--commit` → exit 2.
- `MigrationsSchemaTest` (existing) covers the new entry.
- The bootstrap and template wording: nothing testable in CI, because a model renders it; the
  `Agent instructions` doctor line (0139) still checks every path the root `AGENTS.md` cites.

### Option 2 — commit from the tarball (score 6)

Rejected in the interview: `export` would need the `.tgz` path as a new input, and `--source`
(a local path, no tarball) stays uncovered.

### Option 3 — text only (score 6)

Fixes what misleads a reader, leaves the noise and the stamp. Cut: `report-issue` cannot tell
whether a project on `--ref main` is behind (0055's deferral rested on "no failure has followed",
and 0103 made the field load-bearing); the `Overwrites` note keeps pointing at files that did not
change.

### Option 4 — create nothing (score 3)

Every new single-module project would be told it has no enforcement outside Claude Code while CI
holds one, and promised files it never gets.

## References

| Claim | Source |
|---|---|
| A triaged issue's confirmed rows are the record's evidence; the proposed fix is not an input | `.claude/decisions/0103-issue-filing-and-skeptical-triage.md` |
| A layout condition must key on blueprint data, never an id | `@CLAUDE.md` invariant 7 |
| A shipped `migrations` entry is never edited; a correction is a new id | `extensions.json` › `migrations.$comment` |
| `commit: unknown` was deferred, `--commit` sketched | `.claude/decisions/0055-lessons-learned-011-remediation.md` § accepted and not fixed, item 3 |
| The sweep that made the sentence false | `.claude/decisions/0140-boundary-sweep-in-doctor-gate.md` |
| Module files only where `forbidden_imports` is non-empty | `.claude/decisions/0139-agents-md-root-pointer-module-owner.md`; `references/project-files.md` § 6 |

## Propagation

| File | Change |
|---|---|
| `.claude/skills/project-bootstrap/SKILL.md` | Next steps line 2 names the CI sweep; the single-module paragraph says what holds |
| `docs/{en,pt-br}/02-init-project.md` | The report example, the same line 2 |
| `.claude/skills/project-bootstrap/references/project-files.md` | § 6 defines `moduleAgentFiles` on blueprint data |
| `.claude/skills/project-bootstrap/templates/root.AGENTS.md.example` | The module bullet under `{{#if moduleAgentFiles}}`, a per-package bullet otherwise |
| `.claude/skills/project-bootstrap/templates/root.CLAUDE.md.example` | The CI sentence names `.claude/forbidden-imports.txt`; the module-structure sentence conditional |
| `.claude/hooks/ArchHook.java` + `ArchHook.jar` | `exportFiles` takes `destRoot` and notes only a changed existing file; `--commit` parsed and validated; `stamp()` takes it; `sourceCommit()` with the top-level guard; usage lines |
| `.claude/skills/arch-adopt/SKILL.md` | Step 2 resolves the peeled sha; step 5 passes `--commit`, leaving it out for `--source` and for a fetched `ArchHook.java` that predates the flag |
| `.claude/schemas/extensions.json` | `migrations` entry `single-module-agent-wording` |
| `.claude/.ci/ExportProvenanceTest.java`, `.github/workflows/validate.yml` | New test, step in `hooks-cross-platform` |
| `CLAUDE.md` | The `export` row of § Commands names `--ref` and `--commit` |
| `docs/{en,pt-br}/10-arch-adopt.md`, `docs/{en,pt-br}/07-ci-validate.md` | `--commit` and the note; the new test |

Found while writing, folded in: `arch-adopt` runs the **fetched** `ArchHook.java`, so `--ref` to
a tag older than this change would get "Unknown option: --commit" — step 5 passes the flag only
when the fetched source has it.

Goes to the generated project: **yes** — `arch-adopt`, `ArchHook.java`, the jar and
`extensions.json` travel through `export`; the template and bootstrap text reach new projects;
the migration reaches existing single-module ones.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · hooks-cross-platform › export notes only real overwrites, and never guesses the stamp's commit` | `ExportProvenanceTest`: fresh tree and unchanged file without `Overwrites`; an edited destination `settings.json` named; `--commit` stamped as given; a source at the top of its work tree stamps its HEAD; a copy nested in another work tree stamps `unknown`; a malformed `--commit` exits 2 | green · red by name (3 cases) with the note unconditional and `rev-parse` unguarded |
| `validate › MigrationsSchemaTest` (existing) | The new entry's fields, id and blueprint ids | green |
| `validate · export-determinism` (existing) | All 8 blueprints export identically twice, no dead citation — and, checked by hand here, no `Overwrites` on a fresh tree | green |

Nothing testable in CI for (a) and (b): the model renders the bootstrap report and the root
templates. The `Agent instructions` doctor line (0139) still checks every path the root
`AGENTS.md` cites, including the new `.claude/forbidden-imports.txt` bullet.
