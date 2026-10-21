# 0180 · A blueprint module slot carries the boundary to modules created later, the hook finds the Gradle wrapper, and approval checks the spec before it freezes

- **Date:** 2026-10-21
- **Scenario:** Issue #167, triaged at `5f1a1fc` — six defects in a first `/new-feature` run on a
  Gradle `modular-monolith`: the boundary map covers `shared` only, approval freezes a spec nobody
  checked, 400 and 422 for the same case, Maven-only checks, a version prefix scoped to one
  module, and an `errorCode` no partial owns.
- **Decision:** Option 1 — edits to the owning pieces, no new piece: `boundary-map`/`forbiddenImports` module slot, `build_tools` for `check`/`moduleOf`/`doctor`, the guard's freeze message from `status_transitions`, two `new-feature` steps, `api-rest.md` § Errors and § Versioning, the versioning exemplar, two `migrations`, `.claude/.ci/BuildToolTest.java`.
- **State:** approved by Lucas Fernandes, on 2026-10-21

## Reproduced on disk

From the `issue-verifier` table of `/triage-issue 167`, confirmed rows only. Refuted: 5c (the
`ControllerTest` exemplar pins `/api/v1/...`, so a module controller without the prefix fails its
own slice test — the defect is real, not silent). The issue's proposed fixes are not inputs.

| # | Fact | Evidence at `5f1a1fc` |
|---|---|---|
| 1a | The blueprint says real modules are created later with the shape of `shared`; `packages.map` holds only `shared.internal.*` | `modular-monolith.yaml:69-81` |
| 1b | `boundary-map` derives left sides from `packages.map` values only; `forbiddenImports` skips a file under no listed module path | `ArchHook.java:3220`, `:197` |
| 1c | Nothing rewrites `forbidden-imports.txt` after bootstrap; the blueprint names `domain-modeling` as the module's creator, whose class writes only `docs/` | `project-files.md:11`; `extensions.json:396`; `0146` row 11 |
| 1d | ArchUnit bans frameworks in every `..domain..`, but only at `verify` and only after `test-architect` setup | `ArchitectureTest.java.example:72-74` |
| 2a | Approval flips `status: approved` with no check of the consolidated spec | `new-feature/SKILL.md:802` |
| 2b | Reopening an approved spec is refused by design | `extensions.json:654-658`; `0066` |
| 2c | The freeze message names "the spec's own `status:` line" without the transitions it admits | `ArchHook.java:8204-8205` |
| 3 | `api-rest.md` says a body the aggregate rejects is 422 and maps `ValidationException` (a VO invariant) to 400; the spec template repeats both | `api-rest.md:79-81,134`; `error-handling.md:24`; `feature-spec.md.example:262` |
| 4a | Gradle is a supported `build.tool` | `modular-monolith.yaml:31`; `init-project/SKILL.md:7`; `0179` row 12 |
| 4b-d | Entry guardrail checks `pom.xml`; checklist step 19 is `./mvnw verify`; `wrapper()` resolves `mvnw` only, so `doctor` prints `Maven wrapper ❌` and `check` skips its compile on Gradle | `entry-guardrail.md:104`; `feature-spec.md.example:250`; `ArchHook.java:8729,437,160` |
| 5a-b | The versioning exemplar scopes the prefix to its own package; a modular monolith has one REST package per module | `ApiVersioningConfig.java.example:5,47-49`; `0150` row 1 |
| 6 | Consolidation applies precedence only to facts present in two partials; no step checks an `errorCode` exists in its owner | `new-feature/SKILL.md:606-607,625`; `persistence-spec-document.md.example:80` |

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | Observed in one generated project; 17 claims confirmed at `HEAD` | create nothing |
| 7 — mandatoriness | Defects 1 and 4 are enforcement the hook already claims to give; 2, 3, 5, 6 are norm and procedure | a hook for 2, 3, 6 |
| 8 — destination | Both: `ArchHook`, `new-feature`, `rules/`, `rest-api-architect` travel; existing projects need `migrations` for 1 and 5 | — |
| 16 — existing mode | `boundary-map`, `check`, `doctor`, `guard` already run each check — no new mode | new Form 7c mode |
| D1 | A module slot declared in the blueprint; `boundary-map` writes `{module}` lines; `forbiddenImports` binds one path segment | regenerate at pre-flight; detect in `doctor` only |
| D2 | A procedure step in `new-feature` § Approval | a `spec lint` mode the guard runs on `draft → approved` |
| D3 | 400: a field a value object refuses is input; 422 stays the aggregate's business rule | `ValidationException → 422` |
| D4 | The prefix scoped to the application's base package | one config per module; a modular-monolith exemplar |
| scope | One record for the six defects, as `0179` | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | Fixes in the owning pieces: module slot (D1-A), approval step (D2-A), 400 prose (D3-A), base-package scope (D4-A), Gradle wrapper, `errorCode` check, two migrations | 9 | **Approved** |
| 2 | D1-B — `boundary-map` rerun by `/new-feature-implement`'s pre-flight with the modules on disk | 5 | Rejected — coverage depends on the pre-flight having run |
| 3 | D1-C — a `doctor` line red when a module's `internal/domain` has no rule | 4 | Rejected — blocks nothing at edit time |
| 4 | D2-B — `spec lint` mode refusing `draft → approved` | 4 | Rejected — a hook for one observed failure, matching free text |
| 5 | D3-B — `ValidationException → 422` | 4 | Rejected — changes every generated project's HTTP contract |
| 6 | D4-B / D4-C — one versioning config per module, or a modular-monolith exemplar | 3 | Rejected — invariants 2 and 7 |
| 7 | create nothing | 1 | Rejected — the holes stay |

### Option 1 — fixes in the owning pieces (score 9)

**Motivator:** axes 1 and 16 — every defect has an owner and an existing mode; D1-D4.

**Pros:**
- **1.** The blueprint declares which first segment of its `packages.map` values repeats per
  module (`packages.module_slot: shared`, `_schema.md` documents it as optional).
  `boundary-map` writes that segment as `{module}` on both sides of each derived line —
  `src/main/java/<base>/{module}/internal/domain|org.springframework.` and
  `…/{module}/internal/domain|<base>.{module}.internal.application.` — and `forbiddenImports`
  matches `{module}` as one path segment, binding the same value in the prefix. Every module,
  `shared` and those not yet written, is covered at edit time and in the gate, from data
  (invariants 7 and 10). No file is ever regenerated. The blueprint comment names the executor,
  not `domain-modeling`, as the module's creator.
- **2.** `new-feature` § Approval, before the question: read the consolidated spec for every
  `§` reference to a section it does not have and for text addressed to the approver; fix
  them, then ask. The guard's freeze message lists the transitions from
  `guard.status_transitions` for the spec's current status.
- **3.** `api-rest.md` § Errors: a value a value object refuses is 400 (input, rejected at
  construction); a well-formed value the aggregate refuses by a business rule is 422. The
  type table stays; `error-handling.md` is cited, not restated. `feature-spec.md.example:262`
  follows.
- **4.** `wrapper()` returns `gradlew` when there is no `mvnw`; `moduleOf()` accepts
  `build.gradle(.kts)`/`settings.gradle(.kts)`; `check` runs
  `gradlew -q --offline :<mod>:compileTestJava` (root: `compileTestJava`); the `doctor` line
  becomes `Build wrapper` (not a gate label — `doctor.gate` comment updated). The entry
  guardrail and checklist step 19 name the build file and give both commands; the executor's
  line too.
- **5.** The exemplar scopes the prefix with `forBasePackage("<base package>")`, so every
  controller of the application gets it and the OpenAPI endpoint keeps its path. One
  configuration, one supported-version list (invariant 2). `0150` row 1 stands for placement.
- **6.** Consolidation: every `errorCode` a partial names exists in `10-domain.md` § 2, and
  each one mapped over HTTP has a `30-rest.md` row; one missing is not settled by
  precedence — stop and ask.
- Two `migrations`: `modular-monolith-module-slot-boundary` (rerun `boundary-map` with the
  updated blueprint) and `api-version-prefix-base-package` (change the predicate).

**Cons:** `forbidden-imports.txt` gains a token old hooks do not understand — an old jar reading
a new file matches nothing on the `{module}` lines (fails open, as today). `check` on Gradle
pays a Gradle daemon start the first time.

**Points cut in the rubric:** criterion 5 — six edits across hook, norm, two skills, one
exemplar; proportional to six defects, so rounded up to 9.

**CI:**
- `validate` · `hooks-cross-platform` › `BoundaryMapTest`: `modular-monolith` yields `{module}`
  lines; `BoundaryTest`: a framework import under `<base>/order/internal/domain/` exits 2, the
  same under `order/internal/adapter/` passes, and `order` domain importing
  `<base>.order.internal.application` exits 2.
- `DoctorGateTest` — the renamed label; a new case in a test for `wrapper()` with only
  `gradlew` (doctor prints `Build wrapper ✅`).
- `ApiVersionTemplateTest` — the exemplar's predicate is the base package.
- `MigrationsSchemaTest` — the two new entries.
- `blueprints.yml` — `modular-monolith` on Maven and Gradle already runs its boundary map.
- Defects 2, 3, 6 are prose: `schema` covers the files; nothing behavioral to test.

### Option 2 — D1-B, rerun `boundary-map` in the pre-flight (score 5)

Keeps the file format, but coverage depends on the pre-flight having run; a module written by
hand or by `/arch-adopt`'s project stays open; module names become an input of the mode.

### Option 3 — D1-C, detect in `doctor` only (score 4)

Blocks nothing at edit time; a guarantee was available (criterion 4).

### Option 4 — D2-B, `spec lint` on the approval flip (score 4)

A hook against one observed failure, matching free text by regex that will be wrong — criterion
9 and the mirror of invariant 6.

### Option 5 — D3-B, `ValidationException → 422` (score 4)

Changes the HTTP contract of every generated project and their contract tests for a wording
defect.

### Option 6 — D4-B / D4-C (score 3)

One config per module gives the version list N writers (invariant 2); a modular-monolith-only
exemplar ties the skill to one blueprint (invariant 7).

### Option 7 — create nothing (score 1)

The boundary hole and the Maven-only hook stay in every Gradle and modular-monolith project.

## References

| Claim | Source |
|---|---|
| A norm has one owner; others cite it | `@CLAUDE.md` invariant 2 |
| A blueprint's structure is data, never a skill's literal | `@CLAUDE.md` invariant 7; `@.claude/blueprints/_schema.md` |
| A mode reads its lists from `extensions.json` | `@CLAUDE.md` invariant 10 |
| A convention change that leaves existing code behind is a `migrations` entry | `@CLAUDE.md` routing, `arch-adopt` row |
| `forbidden-imports.txt` is written once at generation | `0146` row 11 |
| Reopening an approved spec stays by hand | `0066` |
| One versioning configuration per project | `0150` row 1 |
| `build.tool` is overridable | `0179` row 12 |

## Propagation

| File | Change |
|---|---|
| `.claude/blueprints/modular-monolith/modular-monolith.yaml` | `packages.module_slot: shared`; the comment names the executor, not `domain-modeling`, as the writer of a new module |
| `.claude/blueprints/_schema.md` | `packages.module_slot` row, optional |
| `.claude/hooks/ArchHook.java` + `ArchHook.jar` | `MODULE_SLOT`; `forbiddenImports` binds `{module}` to one directory and the prefix; `boundary-map` writes `slotted` values; `BuildTool`/`buildTools()`/`buildTool()` read `build_tools`; `moduleOf` accepts Gradle build files; `check` compiles through the tool's wrapper; `doctor` line `Build wrapper`; the freeze message lists `status_transitions` for the spec's status. `format` and the `tests` Stop gate stay Maven-only |
| `.claude/schemas/extensions.json` | `build_tools` block; `boundary_map` and `doctor.gate` comments; migrations `modular-monolith-module-slot-boundary` and `api-version-prefix-base-package` |
| `.claude/skills/project-bootstrap/references/project-files.md` | `{module}` lines are never rewritten when a module appears |
| `.claude/rules/api-rest.md` | § Errors: 400 is input, a field a value object refuses is 400, a business rule is 422, plus the table row; § Versioning: prefix on every controller under the base package |
| `.claude/skills/rest-api-architect/templates/ApiVersioningConfig.java.example` | `forBasePackage(APPLICATION_PACKAGE)` |
| `.claude/skills/new-feature/SKILL.md` | consolidation: every `errorCode` exists in `10-domain.md` § 2 and, when HTTP returns it, `30-rest.md` § 3 — else stop and ask; § Approval: read the spec for dead `§` references and text addressed to the approver before asking |
| `.claude/skills/new-feature/references/entry-guardrail.md` · `templates/feature-spec.md.example` · `.claude/agents/java-spring-boot-developer.md` | the root build file, Maven or Gradle; step 19 gives both commands; the resolved-divergence example names the value-object case |
| `.claude/skills/arch-doctor/SKILL.md`, `docs/{pt-br,en}/04-arch-doctor.md`, `docs/en/00-overview.md` | `Build wrapper` |
| `docs/{pt-br,en}/07-ci-validate.md`, `.github/workflows/validate.yml` | the `BuildToolTest` step, row and diagram node |

Goes to the generated project: **yes** — `ArchHook.java`/`.jar`, `extensions.json`, `rules/`, `new-feature`, `rest-api-architect`, the executor and the active blueprint travel through `export`; existing projects receive the two migrations on `/arch-adopt`.

Not changed, on purpose: the `tests` Stop gate and `format` (Maven goals; the gate's Gradle gap is already recorded in migration `stop-gate-runs-verify`), and the other `pom.xml` mentions under `.claude/` (46 here), which name the Maven case of a skill's own procedure rather than a check that fails on Gradle.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · hooks-cross-platform › boundary-map writes…` (`BoundaryMapTest`) | modular-monolith yields `{module}` lines and no literal `shared`; `check` blocks a framework import and an own-application import in `order`'s domain, a module no bootstrap created, and passes Spring in `order`'s REST adapter | green on the tree · red with `module_slot` removed: 5 cases, `check` exit 0 on `order` |
| `validate · hooks-cross-platform › check compiles with the project's Maven or Gradle wrapper` (new `BuildToolTest`) | the wrapper gets `-q --offline :domain:compileTestJava`, `-q --offline compileTestJava`, `-q -o -pl domain -am test-compile`; `doctor` prints `Build wrapper ✅ (gradle)` and `Setup operational` | green · red with the `gradle` entry of `build_tools` removed: 6 cases |
| `validate · hooks-cross-platform › doctor gate…` (`DoctorGateTest`) | the renamed `Build wrapper` stays red on the healthy fixture and outside the gate | green |
| `validate · hooks-cross-platform › guard sweep…` (`SweepTest`) | a write into an approved folder is refused naming the moves from `approved` to `implemented` or `implemented-blocked` | green · red with `status_transitions.approved` reduced to `implemented` |
| `validate · … › ApiVersionTemplateTest` | the exemplar scopes the prefix with `forBasePackage(APPLICATION_PACKAGE)` and never `forBasePackageClass(` | green · red with the old predicate: 2 problems |
| `validate · … › MigrationsSchemaTest` | the two migrations carry the required fields and known blueprint ids | green |

Defects 2 (the approval read), 3 and 6 are prose: `schema` checks the files; nothing behavioral is testable in CI.
