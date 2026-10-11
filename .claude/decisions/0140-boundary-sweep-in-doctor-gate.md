# 0140 · Sweep every committed `.java` for forbidden imports in `doctor gate`

- **Date:** 2026-10-11
- **Scenario:** vamos fazer agora os próximos passos anotados no 0139 — the one actionable now: a
  CI check that refuses a forbidden import from any agent from the first commit, before ArchUnit
  exists. (The other, the migration that rewords `## Under another agent`, belongs to phase 8
  step 5 and cannot be written before that step proves the payload.)
- **Decision:** Form 7c — a `Boundary violations` line in `ArchHook.java doctor`, gated through
  `doctor.gate.labels`, sweeping every source file with the match `check` now shares
  (`forbiddenImports`), reading `doctor.boundary_sweep`; plus the `boundary-sweep-in-ci` entry of
  `migrations`
- **State:** approved by Lucas Fernandes, on 2026-10-11 (option 1)

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — Concrete symptom | None observed in a run. Found while designing 0139: `doctor`'s `Boundaries` line counts the rules in `.claude/forbidden-imports.txt` and reads no source; the generated CI's `boundaries` job runs only `doctor gate`; ArchUnit is absent until `test-architect` setup installs it, chained by the first `/new-feature-implement` — a Claude Code skill a Copilot-only team may never run | Weighs for "create nothing" (option 4); kept below because the gap is structural, not anecdotal |
| 7 — Mandatoriness | Fail the generated project's CI (user's answer) | Report-only |
| 8 — Destination | The generated project; existing projects too, on their next update | A step only in `ci.yml.example` (never re-exported — 0128) |
| 9 — Integration | `check`'s part 1 already owns the matching rule (`module|prefix`, `rel.startsWith(module + "/")`, `import [static] <prefix>`); its part 2 compiles with `mvnw -o`, which a sweep must not do | A second implementation of the match |
| 10 — Cost of getting it wrong | An existing project with violations turns red on update — answered: red, plus a `migrations` entry that lists and fixes them (user's answer) | A per-file baseline |
| 16 — Existing mode | `check` covers one file at edit time; `doctor gate` is what CI runs | A new hook registration |
| 17 — CI coverage | `DoctorGateTest` already proves every gated label fails the gate by name and appears in healthy output | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | A `Boundary violations` line in `doctor`, gated: part 1 of `check` over `git ls-files '*.java'`; data in `doctor.boundary_sweep`; label in `doctor.gate.labels`; `migrations` entry | 8 | **Approved** |
| 2 | `check --all` mode plus a step in `ci.yml.example` and `ci-gradle.yml.example` | 5 | Rejected — never reaches a project generated before it (0128) |
| 3 | Install ArchUnit at bootstrap instead of at the first implement | 4 | Rejected — model-rendered, needs network, misses existing projects |
| 4 | Create nothing | 4 | Rejected — under Copilot, ArchUnit may never arrive |

### Option 1 — gated sweep in `doctor` (score 8)

**Motivator:** axes 7 and 8; 0128's reason for `doctor gate` over a YAML step — `ci.yml` is
written once and never re-exported, while `ArchHook.java`, the jar and `extensions.json` travel on
every update.

**Shape:** `check`'s forbidden-import match moves into one method both callers use — the edit-time
hook on one file, the sweep on every tracked `.java` (`git ls-files`, so build output and untracked
files stay out). No compilation. Output: `Boundary violations . ✅ N file(s) swept against M rule(s)`
or `❌ K file(s) import what their module forbids`, then up to 8 `path → prefix (module)` lines.
With no `forbidden-imports.txt` the line is ✅ "nothing to sweep" — `Boundaries` is already red
there, and one cause should fail one line. In this meta-repository: no map, nothing to sweep.

**Migration `boundary-sweep-in-ci`:** the note says CI now fails on a forbidden import whoever
wrote it; the prompt runs `doctor`, and for each named file moves the code to the module that may
depend on the framework — never edits `forbidden-imports.txt`.

**Pros:** a build-side guarantee from the first commit, under every agent; one owner of the match;
reaches existing projects through `arch-adopt` with no YAML touched; the `## Under another agent`
section (0139) can then say "the CI refuses a forbidden import" without "once ArchUnit is
installed" — written in this change, since the template is ours and new projects get it at once.

**Cons:** a project with past violations goes red on its next update (by choice, with the
migration); `doctor` reads every tracked `.java` — no compile, milliseconds per file, on demand and
once per CI run; overlaps ArchUnit's domain-purity rule once installed (two checks, one data
source each — the blueprint — so no norm in two places).

**Points cut in the rubric:** 6 (precedent) — half: the sweep is new, the gate and the match are
not; 9 (cost of always running) — half: a gate that blocks, on a rule that is exact (prefix match,
no judgment).

**CI:** `DoctorGateTest` — the new label must appear in healthy output and fail the gate by name
when broken (add the case: a fixture file importing a forbidden prefix). A new
`.claude/.ci/BoundarySweepTest.java` in `validate › hooks-cross-platform`: a fixture with a map,
one clean and one violating file in a module, one violating file untracked (ignored), a test file
under the module (swept, like the hook), single-module package-path prefixes; red on each case
with the match broken.

### Option 2 — `check --all` plus a YAML step (score 5)

Same match, a CLI flag, and a step in both CI templates. Rejected: existing projects never get it
— the reason 0128 moved the gate into `doctor`; two templates kept identical by hand.

### Option 3 — ArchUnit at bootstrap (score 4)

Moves `archunit-installer` into `project-bootstrap`. The ArchUnit class is model-rendered from
the blueprint, needs a resolved dependency version (network, invariant 8), and still does not
reach existing projects without a run. Covers more rules, but is the expensive answer to a
prefix match.

### Option 4 — create nothing (score 4)

ArchUnit arrives with the first implement run, and under Claude Code the edit-time `check` already
blocks. Cut: under Copilot neither may ever happen, so the one guarantee 0139 tells other agents
they have would not exist for them.

## References

| Claim | Source |
|---|---|
| `Boundaries` reads no source | `ArchHook.java`, `report("Boundaries", …)` |
| The generated CI runs only `doctor gate` for boundaries | `project-bootstrap/templates/ci.yml.example`, job `boundaries` |
| `ci.yml` is never re-exported; `doctor gate` travels | `.claude/decisions/0128-doctor-gate-fails-the-generated-boundaries-job.md` |
| ArchUnit is installed by `test-architect` setup, not by the bootstrap | `pom.parent.xml.example` (comment on ArchUnit); `test-architect`; `archunit-installer` |
| A rule that must always hold is a guarantee | `@CLAUDE.md` invariant 6 |
| Lists read by a mode live in `extensions.json` | `@CLAUDE.md` invariant 10 |
| The gap, and its place in the honesty text | `.claude/decisions/0139-agents-md-root-pointer-module-owner.md` § Out of scope |

## Propagation

| File | Change |
|---|---|
| `.claude/hooks/ArchHook.java` + `ArchHook.jar` | `forbiddenImports()` extracted from `check` (both callers use it); `doctorBoundarySweep()` after the `Boundaries` line; jar rebuilt |
| `.claude/schemas/extensions.json` | `doctor.boundary_sweep` (`extensions`, `walk_exclude_dirs`); `Boundary violations` in `doctor.gate.labels` and its `$comment_gate`; `migrations` entry `boundary-sweep-in-ci` |
| `.claude/.ci/BoundarySweepTest.java`, `.github/workflows/validate.yml` | New test, step in `hooks-cross-platform` |
| `.claude/.ci/DoctorGateTest.java` | `broken("Boundary violations", …)` — a domain file importing a forbidden prefix |
| `project-bootstrap/templates/root.CLAUDE.md.example` | `## Under another agent`: CI fails on a forbidden import whoever wrote it; the ArchUnit caveat now applies to ArchUnit only |
| `project-bootstrap/templates/module.AGENTS.md.example` | The enforcement sentence: CI first, the edit-time hook as Claude Code's extra |
| `project-bootstrap/templates/ci.yml.example`, `ci-gradle.yml.example` | The `doctor gate` comment names the new line (identically) |
| `CLAUDE.md` | The `doctor gate` row of § Commands names the new line |
| `docs/{pt-br,en}/13-github-copilot.md` | § 3 build sensors and § 7 row 1 |
| `docs/{pt-br,en}/04-arch-doctor.md`, `docs/{pt-br,en}/07-ci-validate.md` | The new line; the new test; `DoctorGateTest`'s gated list |

Goes to the generated project: **yes** — `ArchHook.java`, the jar and `extensions.json` travel
through `export`, so a project already generated gets the gated line on its next `arch-adopt`
update, with the migration printed; the template text reaches new projects through step 6.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · hooks-cross-platform › doctor sweeps every source file for forbidden imports, with check's own match` | `BoundarySweepTest`: clean tree green with counts; plain and `static` violations named with prefix and module; a test under the module swept; a file outside every module not; a single-module package prefix; git: untracked swept, ignored `target/` not; no git: `build/` skipped at any depth; no map green; `check <path>` blocks exactly the file the sweep names | green · red by name (2 cases) with `/src/test/` skipped in the sweep |
| `validate › doctor gate fails on every gated line and only on those` (existing, one case added) | The new label prints on a healthy project and fails the gate by name | green · red by name (2 cases) with the label removed from `gate.labels` |
| `validate · export-determinism` (existing) | No dead citation from the new data block; the exported tree passes its own `schema` | green on all 8 blueprints |

Also run by hand: an exported `hexagonal` tree with a map and one violating domain file —
`doctor gate` exits 1 naming `Boundary violations` and the file.
