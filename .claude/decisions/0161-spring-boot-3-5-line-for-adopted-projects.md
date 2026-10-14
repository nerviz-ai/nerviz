# 0161 · A Spring Boot line below the floor is refused by name — `export` exits 2, `doctor` reports the line

- **Date:** 2026-10-14
- **Scenario:** "faca uma avaliacao do grau de trabalho a ser feito pelo harness nerviz para permitir
  criacao de projetos em versoes 3.x"
- **Decision:** Form 7c — option C. A refusal in `ArchHook.java export` and a `Platform` line in `doctor`,
  both reading `doctor.platform` of `.claude/schemas/extensions.json`
- **State:** approved by Lucas Fernandes, on 2026-10-14 ("aprovo C"); implemented on the branch
  `feat/platform-line-floor`

## What the code and the network showed

| Fact | Where |
|---|---|
| The Initializr no longer serves any 3.x line: `bootVersion=3.5.6` → `400 "Spring Boot compatibility range is >=4.0.0"`; offered: `4.2.0-M2`, `4.1.1`, `4.0.8` and snapshots | `start.spring.io` metadata and `starter.zip`, 2026-10-14 |
| Maven Central still holds `spring-boot-dependencies` `3.5.16` | `repo.maven.apache.org/.../spring-boot-dependencies/maven-metadata.xml`, 2026-10-14 |
| Boot 3.5 open-source support ended 2026-06-30; last OSS release `3.5.16` (2026-06-25); commercial extended support to 2032-06-30; Java 17–25 | `endoflife.date/api/spring-boot.json`, 2026-10-14 — third-party, not re-verified on spring.io |
| `arch-adopt` never read the target's Boot line: an adopted 3.x project received Boot 4 norms, exemplars and the `tools.jackson..` allowlist with no warning | `@.claude/skills/arch-adopt/SKILL.md` (no version step before this change) |
| 46 of 294 exemplars carry an explicit Boot 4 / Framework 7 marker — a lower bound, implicit API drift (Security 7, Hibernate 7) is not greppable. By skill: http-client 10, test 9, rest 6, persistence 5, security 4, messaging 4, ai 4, project-bootstrap 2, new-feature 1, graphql 1 | `grep` over `skills/*/templates`, 2026-10-14 |
| Plus 15 `references/` or `SKILL.md` files, 5 rules (`ai`, `api-rest`, `architecture-ddd`, `persistence`, `00-index`), 3 agents, 3 blueprints (`tools.jackson..` allowlist), 5 of 43 `.ci` tests | same |
| Mechanisms with no Boot 3.5 counterpart, not renames: `@ImportHttpServices` (11 files), native API versioning — `ApiVersionConfigurer`, `spring.mvc.apiversion.*`, `version = "1+"` (16 files, the whole of `0150`), `org.springframework.resilience` / `core.retry` (Framework 7), Spring AI 2.x (`0145`: Boot 4 only), Jackson 3 `tools.jackson` | same |
| Renames: starters (`webmvc`, `flyway`, `opentelemetry`, `restclient`), `spring.mongodb.*` vs `spring.data.mongodb.*` (silent when wrong), test-slice packages, Testcontainers 2 module packages | `project-bootstrap/references/dependency-catalog.md` § Spring Boot 4 renamed starters, `@.claude/rules/persistence.md` |
| CI binds the Initializr twice: `exemplar-imports` resolves every exemplar import against a `starter.tgz` classpath; `templates.yml` jobs (`JavaTemplatesTest`, `MapStructWiringTest`, `CiItCheckTest`) download from it | `.github/workflows/validate.yml` job `exemplar-imports`, `.github/workflows/templates.yml` |
| `design › no hardcoded Spring/Java version` greps `Spring Boot [0-9]+\.[0-9]+` over rules, skills, blueprints and `CLAUDE.md` | `.github/workflows/validate.yml` |

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — Symptom | Adopting an existing project that is on Boot 3.x and cannot migrate | create nothing (a silent wrong install is observed, not anticipated) |
| 8 — Destination | Both: norms and exemplars travel through `export` | meta-repo-only forms |
| Scope | The whole pipeline, not only the skeleton | bootstrap-only |
| Line | 3.5 only | any-3.x (a profile per minor) |
| Origin | Adopted projects only — `/init-project` stays on what the Initializr serves | Maven Central oracle, non-Initializr scaffold, amendment to invariant 8 |
| Record of the line | Both: a `**Platform:**` paragraph in the root `CLAUDE.md` drives the skills (precedent `**Mappers:`, `0160`); `doctor` reads the build file and reports a disagreement | build-only (a fragile parser would drive code generation) |
| 7 — Guarantee | A `doctor` line, reported, never gating: build vs paragraph, and a property of the other line (`spring.mongodb.*` in a 3.5 project) | `doctor gate` |
| 17 — CI | A 3.5 matrix in the path-filtered `templates.yml` | static-only |

The interview ran before the end-of-support fact was found; option C below exists because of it. The
answers on scope, record and CI describe B; with C approved they are not built (see Propagation).

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| C | Refuse a pre-4.0 target by name in `export` + `doctor` `Platform` line | 9 | **Approved** |
| B | Line profile `boot-3.5`, paired exemplars, declared gaps where no counterpart exists | 7 | Rejected for now — a 60–70 file tax on a line out of open-source support; revisit when a concrete adopter stays on it |
| A | Line profile `boot-3.5`, paired exemplars for every mechanism | 6 | Rejected — B plus a second API-versioning design and a second Spring AI API |
| D | A maintained `boot-3.5` branch of this repository, pulled with `arch-adopt --ref` | 3 | Rejected — every norm in two branches, invariant 2 |
| — | create nothing | 2 | Rejected — leaves today's silent wrong install in place |

### Option C — refuse by name (score 9)

**Motivator:** axis 1 — the observed failure is a silent install of Boot 4 content into a 3.x
project; and the line it would serve left open-source support on 2026-06-30.

**Pros:** closes the observed defect deterministically — the `export` mode reads the target's Boot
line from its build file and exits non-zero below `platform.min_line`, naming the line found, the
floor and the way out (migrate first). One `doctor` line reports the same in an already-adopted
project. Small (~8 files, one test). The detection is shared with B and A, so nothing is lost if one
of them is chosen later.

**Cons:** does not serve the adopter on 3.5 — they get a clear refusal instead of a pipeline.

**Points cut in the rubric:** none of the nine; the cost is the unserved case, recorded here.

**CI:** new `.claude/.ci/PlatformLineTest.java` in `validate › hooks-cross-platform`, spawning the jar
for `export --dry-run` and `doctor` over fixture builds: a starter parent, an imported BOM, a
property, a Gradle plugin in Groovy and in Kotlin, and a version catalog.

### Option B — line profile with declared gaps (score 7)

**Motivator:** axes 1 and Scope — the adopter keeps the pipeline on 3.5.

**Pros:** everything in C, plus `.claude/platforms/boot-3.5.yaml` (+ `_schema.md`) as data — starter
ids, property prefixes, Jackson roots, test-slice and Testcontainers packages, the Resilience4j
artifact, and an `unavailable` list. Mechanisms with no counterpart worth building — native API
versioning (`0150`), Spring AI 2 (`0145`) — are listed `unavailable`, and the owning skill stops with
that line instead of improvising. `@ImportHttpServices` → `HttpServiceProxyFactory` and Framework
retry → Resilience4j retry get paired exemplars (`templates/boot-3.5/`), as `0160` paired MapStruct.
Norms gain a `§ On the 3.5 line` inside the file that owns each theme — single owner kept.
`export.platform_copy` carries only the active profile, as `blueprint_copy` does.

**Cons:** strains invariant 8 — a line identifier `3.5` written in this repo (argued: it names a
supported line, like a blueprint id; the patch version stays the project's own), and the
version-literal grep needs a reasoned exemption. 3.5 sections in five norms load in every 4.x
project too. Every future design decision owes a 3.5 answer or an `unavailable` entry, and every new
exemplar a pair or a reason — a permanent tax. The blueprints' Jackson allowlist becomes per line.
Serves a line with no open-source patches.

**Points cut in the rubric:** 2 (invariant 8 strained), 3 (3.5 norm sections always loaded),
5 (doubling without end).

**Size:** ~60–70 files over four PRs — C; profile + norms + blueprints; exemplars (~30 of the 46
flagged, the rest `unavailable`); CI.

**CI:** C's test, plus `exemplar-imports` building a second classpath from a reference POM whose
parent is the newest `3.5.x` read from Maven Central's `maven-metadata.xml`, a `templates.yml`
matrix job compiling the 3.5 exemplars with its paths in both trigger lists, and a `schema` check
that every exemplar flagged by a profile marker has a pair or an `unavailable` entry.

### Option A — every mechanism paired (score 6)

Same as B with no `unavailable` list: native API versioning reimplemented on Framework 6 (a
path-prefix mapping and deprecation headers with no `ApiVersionConfigurer`), `ai-architect` on
Spring AI 1.x. ~80–90 files. Cuts B's three points plus 6 (precedent) — a second API-versioning
design and a second Spring AI API have no precedent here and double `0145` and `0150`.

### Option D — a `boot-3.5` branch (score 3)

`arch-adopt --ref boot-3.5` already exists, so it costs nothing on day one. Every norm then lives in
two branches and drifts the first week — invariant 2; capped ≤ 4.

## References

| Claim | Source |
|---|---|
| Versions are resolved at runtime via the Initializr, never from memory | `@CLAUDE.md` invariant 8 |
| A norm has a single owning file | `@CLAUDE.md` invariant 2 |
| Architectures are data; the active blueprint alone travels | `@CLAUDE.md` invariants 7 and 9, `export.blueprint_copy` |
| Lists a mode reads live in `extensions.json`, never in the Java | `@CLAUDE.md` invariant 10 |
| A per-project choice recorded once in the root `CLAUDE.md`, exemplars in pairs | `@.claude/decisions/0160-mapstruct-as-a-per-project-mapper-choice.md` |
| `doctor` lines that report and never gate; the closest precedent for reading the build | `doctor.virtual_threads`, `@.claude/decisions/0155-virtual-threads-on-jdk-21.md` |
| A Form 7c mode always needs its own test | `.claude/skills/claude-code-architect-designer/references/ci-coverage.md` |

## Propagation

| File | Change |
|---|---|
| `.claude/schemas/extensions.json` | `doctor.platform` (`min_line`, `build_files`, `line_patterns`) and its `$comment_platform` — the single owner of the floor and of how a line is read (invariant 10) |
| `.claude/hooks/ArchHook.java` + `ArchHook.jar` | Form 7c: `bootLine`, `lineBelow`, `exportPlatformGate` (called by `export` before it loads the blueprint), `doctorPlatform` (the `Platform` line); jar rebuilt and verified |
| `.claude/skills/arch-adopt/SKILL.md` | Step 5: show `export`'s refusal as it is and stop; a line no pattern reads is not refused and `doctor` says ⚪ |
| `CLAUDE.md` | § Commands, the `export` row: the refusal and the `Platform` line |
| `docs/en/11-pitfalls.md`, `docs/pt-br/11-pitfalls.md` | Part 2, Rules and decisions: the refusal has no bypass, and an unreadable line is not refused |
| `docs/en/07-ci-validate.md`, `docs/pt-br/07-ci-validate.md` | Diagram node H22, the "What each check covers" row, the command list |
| `.github/workflows/validate.yml` | `hooks-cross-platform` step `export refuses a Spring Boot line below the floor, and doctor reports it` |
| `.claude/.ci/PlatformLineTest.java` | New: the test above |

Not touched, on purpose: `doctor.gate.labels` (the line is reported, never gated), the `export`
manifest (no new file travels: the mode lives in `ArchHook.java`, the data in `extensions.json`, both
already exported whole), `project-bootstrap` (a project it generates comes from the Initializr, on a
line at or above the floor).

Scope against the interview: this is option C only. The `**Platform:**` paragraph in the root
`CLAUDE.md` and the property-prefix check belong to B and are not built — nothing in C reads the
paragraph, and `doctor` reads the build directly.

Goes to the generated project: **yes** — `ArchHook.java`, `ArchHook.jar` and `extensions.json` are
copied whole by `export`, so an adopted project's own `doctor` prints the `Platform` line, and
`arch-adopt`'s update run, which launches the fetched ref's `export`, applies the floor.

## Consequences

- A project on a line below the floor cannot adopt this `.claude/`. It gets the line, the floor and
  the way out (migrate first) — the decision gives it nothing else.
- The floor is data. Raising it, or running a step before `arch-adopt` that brings a project to the
  floor, changes `extensions.json` and the skill that runs it; the refusal's Java does not change.
- A `.claude/` already adopted into a project below the floor keeps working as before; `doctor` now
  says it does not apply as written.
- An `arch-adopt` run whose fetched ref predates this change has no gate: the refusal is the fetched
  `export`'s, as `--commit` already is.
- A version the build holds in a property, a corporate parent or a catalog no pattern reads is not
  refused: a refusal needs evidence. `doctor` prints ⚪ there.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · hooks-cross-platform › export refuses a Spring Boot line below the floor, and doctor reports it` (`.claude/.ci/PlatformLineTest.java`) | `export` exits 2 with the line, the floor and the way out for a starter parent, an imported BOM, the `spring-boot.version` property, a Gradle plugin (Groovy, Kotlin) and a version catalog below the floor, writing nothing; admits the floor, later lines, an unreadable version, an empty build file and no build file; `doctor` prints ⚠️ / ✅ / ⚪ and `doctor gate` never names `Platform` | green on the tree (18 cases); red with the `exportPlatformGate(destRoot)` call removed — 8 cases fail by name, from `Maven starter parent 3.5.16 — expected exit 2, got 0` to `refused, no --dry-run` |
| `validate · hooks-cross-platform › committed ArchHook.jar is what the source compiles to` | The jar carries the new mode | `build --verify` green |
| `validate · design › frontmatter schema` | `extensions.json` still parses and the `arch-adopt` body keeps its class sections | `schema` exit 0 |
| `validate · design › no hardcoded Spring/Java version` | The new text writes no `Spring Boot N.N` into rules, skills, blueprints or `CLAUDE.md` | grep over those paths: no hit |
