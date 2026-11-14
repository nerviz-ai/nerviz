# 0175 · A project below the Spring Boot floor is brought to it by `/platform-upgrade`, on a recipe the hook generates from data and from Spring's own metadata

- **Date:** 2026-10-19
- **Scenario:** Issue #158, triaged at `e22c8ae`: `export` refuses a project below the Spring Boot
  4.0 floor, and no skill, norm or check takes a 3.x project to that floor; `arch-adopt` stops at the
  refusal.
- **Decision:** Form 2 — `.claude/skills/platform-upgrade/SKILL.md`, plus Form 7c — `ArchHook.java upgrade-recipe`,
  reading the `doctor.platform.upgrade` block of `extensions.json`. Option 1.
- **State:** approved by Lucas Fernandes, on 2026-10-19

## Reproduced on disk

From the `issue-verifier` table of `/triage-issue 158` at `e22c8ae` (layer `static`); only the rows
it confirmed. No commit since `e22c8ae` touches a cited file.

| # | Claim | Evidence |
|---|---|---|
| 1 | `export` exits 2 below `doctor.platform.min_line` (`4.0`) | `.claude/schemas/extensions.json:160`; `.claude/hooks/ArchHook.java:1032-1045` |
| 2 | The refusal says "migrate first" and no procedure follows it | `ArchHook.java:1042`; `.claude/skills/arch-adopt/SKILL.md:183-184`; no skill migrates a Boot line |
| 3 | A Gradle build declaring a 3.x line is refused under `--dry-run`, nothing written | `extensions.json:166`; `PlatformLineTest` (0161) |
| 4 | The source (`v0.40.2`) and the published plugin (`v0.38.1`) both carry the `4.0` floor | `git show v0.38.1:.claude/schemas/extensions.json` |
| 5 | 0161 rejected a `boot-3.5` profile "for now" | `0161-spring-boot-3-5-line-for-adopted-projects.md:47` |
| 6 | `$comment_platform` anticipates a migration step that runs before `arch-adopt` | `extensions.json:158`; 0161 § Consequences |
| 7 | The Boot 4 rename list is partial: starters, `-test` artifacts and Jackson 3 in `dependency-catalog.md`; `spring.mongodb.*` in `rules/persistence.md`; Testcontainers 2 only in exemplars | `.claude/skills/project-bootstrap/references/dependency-catalog.md:29-52` |
| 8 | `design › no hardcoded Spring/Java version` greps `skills/` | `.github/workflows/validate.yml:295-307` |
| 9 | Every 3.x adopter stops at the first step | `arch-adopt/SKILL.md:183-184`; 0161:154-155 |

The issue's proposed fix is not an input to this record.

## What the network showed

| Fact | Where |
|---|---|
| `org.openrewrite.recipe:rewrite-spring` is licensed under the Moderne Source Available License; it holds every `UpgradeSpringBoot_*` recipe | its POM and `MANIFEST.MF`, `6.37.1`, 2026-10-19 |
| Its 4.0 recipe chains `UpgradeSpringBoot_3_5` → … → `3_0` → `boot2.UpgradeSpringBoot_2_7`; it migrates the Jackson BOM property, not Jackson 3 code | `META-INF/rewrite/spring-boot-3*.yml`, `spring-boot-40.yml` in that jar |
| Maven Central stopped at `rewrite-spring` `6.37.1` (2026-08-19); later versions sit in an authenticated repository (HTTP 401) | `repo.maven.apache.org` metadata; `artifacts.codegenomeproject.org` |
| The MSAL forbids "offering a product or service, the value of which derives to any extent from the value of the Software" and "that accomplishes for users any purpose of the Software" | `docs.moderne.io/licensing/moderne-source-available-license` |
| `rewrite-core`, `rewrite-java`, `rewrite-java-17`, `rewrite-java-21`, `rewrite-maven`, `rewrite-gradle`, `rewrite-properties`, `rewrite-yaml`, `rewrite-xml` (`8.90.4`), `rewrite-java-dependencies` (`1.60.2`) and `rewrite-maven-plugin` (`6.46.1`) are Apache 2.0 | their POMs on Maven Central, 2026-10-19 |
| The primitives a recipe of our own needs live in those jars: `ChangePackage`, `ChangeType`, `ChangeMethodName`, `ChangeDependencyGroupIdAndArtifactId`, `UpgradeParentVersion`, `UpgradePluginVersion`, `gradle.ChangeDependency`, `gradle.UpdateGradleWrapper`, `properties`/`yaml.ChangePropertyKey` | `unzip -l` of each jar |
| The Gradle plugin is `org.openrewrite:plugin` (the `org.openrewrite.rewrite` marker only depends on it); its POM declares *The Apache Software License, Version 2.0* | `plugins.gradle.org/m2/org/openrewrite/plugin/7.41.0/plugin-7.41.0.pom`, 2026-10-19 — the open point of the draft, closed |
| Maven Central serves `rewrite-bom` only up to `8.90.4` (last updated 2026-08-24); `org.openrewrite:plugin` `7.40.0` and `7.41.0` import `rewrite-bom` `8.91.0` (404 there) and `7.39.0` imports `8.89.0` (200), so Gradle's `latest.release` fails with `Could not find org.openrewrite:rewrite-bom:8.91.0`. `rewrite-maven-plugin` lives on Central and its `release` (`6.46.1`) is coherent with it | `repo.maven.apache.org` metadata and POMs; a failed run of the init script, 2026-10-19 |
| `rewrite-maven-plugin`'s `run` goal compiles the project (through `process-test-classes`) before it runs the recipe, and the Gradle plugin's `rewriteRun` depends on `compileJava` and `compileTestJava`; a source tree whose type renames landed does not compile until the test starter is added | two failed runs of the property pass on the fixtures, 2026-10-19 |
| Spring Boot 4 jars carry their own property renames as data: `spring.data.mongodb.host` → `deprecation: {level: error, replacement: spring.mongodb.host, since: 4.0.0}`; Apache 2.0 | `spring-boot-mongodb-4.0.8.jar` `META-INF/spring-configuration-metadata.json` |

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — Symptom | A real 3.x project is waiting to adopt | create nothing |
| 2 — Trigger | A person types it: it rewrites the build and `src/` | Form 1 |
| 5 — Nature | Procedure — version resolution, two recipe passes, a guided residue, a build | Forms 4, 5 |
| 7 — Mandatoriness | Not a guarantee: the guarantee already exists, `export`'s refusal (0161) | Forms 7a, 7b, 8 |
| 8 — Destination | Both: delivered by the plugin to a project with no `.claude/`, and travelling into adopted projects for a later floor raise | plugin-only |
| Engine | A recipe written here on the Apache-2.0 OpenRewrite core, properties generated from Spring Boot's metadata, a guided pass for the residue; never `rewrite-spring` | MSAL recipes; model-only; an engine inside the jar |
| Origin line | Any 3.x — the version step is one bump to what the Initializr serves | 3.5-only |
| Build tools | Maven and Gradle; the Gradle plugin's license was checked in Phase 4 (Apache 2.0) | Maven-only |
| End | A green `verify`, no commit, the diff handed over, `/arch-adopt` printed | chaining `arch-adopt`; compile-only |
| 17 — CI | Answered from `references/ci-coverage.md` and the workflows, per option | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | `.claude/skills/platform-upgrade/SKILL.md` (Form 2) + `ArchHook.java upgrade-recipe` (Form 7c) + `platform.upgrade` data | 8 | **Approved** |
| 2 | `platform-upgrade` with the recipe as a template and properties written by the model | 6 | Viable with a caveat — a mechanical step left to persuasion |
| 3 | `platform-upgrade`, model-guided only | 6 | Viable with a caveat — right for a project small enough that one reviewed diff is cheaper |
| 4 | `rewrite-spring`'s `UpgradeSpringBoot_4_0` | 7 by the rubric | Rejected by a constraint outside it — license |
| 5 | A migration step inside `arch-adopt` | 4 | Rejected — widens a travelling skill's territory to `src/**` |
| 6 | A migration engine inside the jar (`javac` Tree API) | 3 | Rejected — a second OpenRewrite to maintain, no build-file AST |
| — | create nothing (a better refusal message) | 2 | Rejected — a real project is waiting |

### Option 1 — skill + `upgrade-recipe` mode + data (score 8)

**Motivator:** axis 1 and the engine answer — the mechanical part deterministic, the judgment part
guided.

**Shape, as built:**

1. Refuse a dirty worktree (as `arch-adopt` § 1); the build tool is the file at the root and its
   own wrapper runs everything.
2. Fetch the source into `$SRC` (as `arch-adopt` § 2-3): a 3.x project has no `ArchHook.jar`, and an
   adopted one needs the newer floor's data. A ref older than the skill has no mode, and an unknown
   mode exits 0, so the skill greps for it first.
3. The target version is read from a project the Initializr generates (invariant 8).
4. The engine's version is read from where it is served: Maven's `<release>`; Gradle's is the newest
   plugin whose `rewrite-bom` Maven Central serves (the network table).
5. Pass 1 — `upgrade-recipe --pass bump` writes the version bump, one coordinate rename per
   `renames.dependencies` entry and one type rename per `renames.types` entry. Types are renamed
   **here, not after the bump**: a recipe run sees the classpath the project still has, and an old
   type no longer resolves once the new BOM is in.
6. The new classpath is listed (Maven `dependency:build-classpath`, Gradle an init script). Pass
   2 — `upgrade-recipe --pass properties` reads `META-INF/spring-configuration-metadata.json` out of
   each jar and emits a rename for every property the jar itself declares as an error with a
   replacement. It runs **without compiling**: the type renames of pass 1 leave the tests uncompilable
   until their starter is added.
7. The engine is Maven's `rewrite-maven-plugin` from the command line, or a Gradle init script from
   `templates/` applying `org.openrewrite:plugin`; both on the Apache-2.0 core.
8. The guided residue — a compile error naming a package of `test_starters` is cured by the
   test-scope starter that key maps to; Jackson 3 code, a springdoc line, an API that changed shape
   stay with the model, against the norms and the vendor's migration guide, until the build compiles.
9. `verify` green; no commit; print `/arch-adopt`.

**Data:** `doctor.platform.upgrade` in `extensions.json` — `renames` (coordinates, types), `test_starters`,
`recipes` (which recipe each entry becomes, with which options — the engine is data), `engine` (where
its versions are listed, and the trap above) — single owner (invariants 2 and 10). Its `$comment` records the clean-room rule: entries are
written from Spring's migration guide and Spring artifacts' own metadata, never from a recipe module
under another license. `dependency-catalog.md` § renamed starters cites the block instead of listing
it.

**Pros:** deterministic where the change is mechanical; every dependency Apache 2.0; no property
table kept by hand; the engine is data, swappable by changing one block; travels, so an adopted
project upgrades itself when the floor moves.

**Cons:** one mode, one data block, one template and two tests; this repository maintains the
coordinate and package list; running a third-party engine through the project's own wrapper has
only partial precedent (`sonarqube-setup`, `mapstruct-installer` edit a build, none runs a plugin
from the command line).

**Points cut in the rubric:** 5 (maintenance — the rename list), 6 (precedent — partial).

**Built, with the numbers that justified each choice:** every `to` of `renames` is a name an exemplar
this repository compiles on the floor already uses, or a published artifact (`testcontainers-*` on
Maven Central, the four test starters); every `from` was found in the jar of the line it leaves
(`spring-boot-test-autoconfigure`, `spring-boot-autoconfigure`, `spring-boot-actuator-autoconfigure`,
`spring-boot-test`, `org.testcontainers:postgresql`/`mongodb`). A wrong `from` renames nothing, a wrong
`to` breaks a build, so only the second is checked against a published artifact.

**CI:** new `.claude/.ci/UpgradeRecipeTest.java`, step in `validate › hooks-cross-platform`: fixture
jars carrying metadata → the expected `ChangePropertyKey` lines; a `level: warning` entry skipped; no
jars → only the data renames; a line at the floor → nothing emitted. New job in `templates.yml`
(network, path-filtered, paths in both trigger lists): a 3.x fixture project, Maven and Gradle, its
line read from Maven Central's `maven-metadata.xml` at run time, the generated recipe applied, the
result compiled on the Initializr's line. `design › frontmatter schema` covers the skill (class,
sections, `export` include); `export-determinism` carries it into a target.

### Option 2 — template recipe, properties by the model (score 6)

Same skill, no Form 7c: the static recipe is `templates/*.rewrite.yml.example` and the model reads the
metadata (`jar xf`) and writes the property entries. Cuts 4 (a mechanical step left to persuasion)
and 2 (the rename list owned by the template and the catalog at once). CI: the `templates.yml` job
only.

### Option 3 — model-guided only (score 6)

Version from the Initializr, property renames from the metadata, a compile loop and the official
migration guide, no engine. Cuts 4 (the model rewrites a user's code with nothing deterministic
beneath) and 5 (cost and variance grow with the code base). When it would be the right answer: a
project small enough that one reviewed diff is cheaper than a recipe run, or a build the engine
cannot load. CI: nothing testable beyond `schema`.

### Option 4 — `rewrite-spring` (rejected by license)

Scores 7 by the rubric — it covers more than option 1 on day one. Rejected because this repository's
migration engine stays free of a license that restricts derived products and services. When it is
acceptable: a person running that recipe on their own code, on their own acceptance of its terms,
outside any piece of this repository.

### Option 5 — inside `arch-adopt` (score 4)

`arch-adopt` writes `.claude/**`, `.gitignore` and `CLAUDE.md` and travels into every project; a
migration writes the build and `src/**`. Axis 9 — the territory of a skill that runs remote content
would grow to the whole tree.

### Option 6 — an engine inside the jar (score 3)

The JDK's `javac` Tree API rewrites imports and types with no dependency, but it is a second
OpenRewrite to maintain and has no AST for `pom.xml` or Gradle scripts. 0163 rejected network inside
the jar for the same family of reasons.

## References

| Claim | Source |
|---|---|
| Versions resolved at runtime via the Initializr | `@CLAUDE.md` invariant 8; 0161 |
| A norm has one owning file | `@CLAUDE.md` invariant 2 |
| Lists a mode reads live in `extensions.json` | `@CLAUDE.md` invariant 10 |
| What travels is data in the `export` block; a travelling skill cites only what travels | `@CLAUDE.md` invariant 9 |
| A skill delivered by the plugin fetches the source into `$SRC` | `@.claude/decisions/0163-create-project-from-the-plugin.md`; `arch-adopt` § 2-3 |
| `upgrade` is a native command, so the skill cannot be named after it | `native_commands` in `extensions.json` |
| A Form 7c mode always needs its own test | `.claude/skills/claude-code-architect-designer/references/ci-coverage.md` |
| A decision citation in a travelling skill is cut by `export`, and `ArchHook.java` is exempt from the residue markers | `export.body_transforms` of `extensions.json` |
| A skill below a floor of `.claude/skills` and `grep` for a version: none may name `Spring Boot N.N` | `.github/workflows/validate.yml` › `design` › `no hardcoded Spring/Java version outside decisions/` |
| The vendored plugin copy is the source's file at a tag; a new file waits for the tag | `@.claude/decisions/0163-create-project-from-the-plugin.md` § Propagation |

## Propagation

| File | Change |
|---|---|
| `.claude/skills/platform-upgrade/SKILL.md` | new, Form 2, `model: opus`, `disable-model-invocation: true`; `**Class:** build`, `**Unfiltered Bash:**`, `## Why`, `## Contract` |
| `.claude/skills/platform-upgrade/templates/rewrite-init.gradle.example`, `classpath-init.gradle.example` | the two Gradle init scripts; read from `$SRC`, since the plugin vendors only `SKILL.md` |
| `.claude/hooks/ArchHook.java` | `upgrade-recipe` mode (dispatch, header line, usage, method with its Javadoc); `export`'s refusal now names `platform-upgrade` |
| `.claude/hooks/ArchHook.jar` | rebuilt under the pinned JDK |
| `.claude/schemas/extensions.json` | `doctor.platform.upgrade` (`engine`, `recipes`, `test_starters`, `renames`, `target_pattern`, `metadata_entry`, `metadata_deprecation`); `skill_classes.classes.build.skills` + an `overrides.platform-upgrade.write_allow` of the build files, wrapper files and `src/**`; `export.skills.include` |
| `.claude/skills/arch-adopt/SKILL.md` | step 5 names `/platform-upgrade` as the way up when `export` refuses |
| `.claude/skills/project-bootstrap/references/dependency-catalog.md` | § renamed starters cites `doctor.platform.upgrade` as the owner of the mechanical renames (invariant 2) |
| `CLAUDE.md` | routing row for `platform-upgrade`; Commands row for `upgrade-recipe`; the `export` row points at the way up |
| `README.md`, `roadmap.md` | the mode table row; a paragraph under *Existing project*; the skill in the roadmap list |
| `docs/en/01-file-types.md`, `docs/pt-br/01-tipos-de-arquivo.md` | `platform-upgrade` in the `build` class row |
| `docs/en/07-ci-validate.md`, `docs/pt-br/07-ci-validate.md` | the `UpgradeRecipeTest` step and diagram node, the `platform-upgrade` job of `templates.yml`, both commands |
| `.claude/.ci/UpgradeRecipeTest.java`, `.claude/.ci/PlatformUpgradeTest.java` | new |
| `.github/workflows/validate.yml`, `.github/workflows/templates.yml` | the step in `hooks-cross-platform`; the job, with its paths (`templates/**`, the test, `extensions.json`) in **both** trigger lists |
| `nerviz-ai/marketplace` (local clone, branch `feat/platform-upgrade` from `origin/main`, not committed) | `.plugin-source.json` › `files` += `skills/platform-upgrade/SKILL.md`; the vendored copy; `README.md` (§ Bring a project up to the floor, three skills); the descriptions of `plugin.json` and `marketplace.json`. Its drift check fetches at `ref`, so the new file 404s until this repository cuts the tag carrying it — merge after that tag, with `./sync.sh <tag>` |

Not touched: `docs/en|pt-br/04-arch-doctor.md` and `10-arch-adopt.md` — they carried uncommitted edits
of their own when this ran, and a PR for this piece should not carry them. `10-arch-adopt.md` is where a
sentence about the refusal's way up belongs.

Goes to the generated project: **yes, via `export.skills.include`.** The skill, the mode and the data
travel whole; the plugin delivers the skill to a project that has no `.claude/` yet, and an adopted project
keeps it for the next floor.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · hooks-cross-platform › upgrade-recipe writes the bump and the property renames, and refuses what it cannot spell` | `UpgradeRecipeTest`: the bump carries the target and one step per rename of the production data, deterministic; a target below the floor, a malformed one, a project at the floor, a missing `recipes` block and an unresolved placeholder → exit 2, nothing written; the property pass over jars built in the test (error with replacement written, warning and no-replacement and no-metadata skipped, first jar wins, key order, newline and path-separator classpaths); no rename → exit 0, nothing written | green on the tree · red with the `level` check removed from the property pass, failing as "properties wrote a rename for spring.warned.key, which no jar declares as one" |
| `templates · platform-upgrade › Maven and Gradle fixtures, 3.x to the Initializr's line, build green` | `PlatformUpgradeTest`: on each build tool, a fixture on the newest GA line below the floor goes through the skill's procedure — the recipe the mode writes, run by the Apache-2.0 engine; the starter, types and `spring.data.mongodb.*` renamed; the test starter found in `test_starters` by the package the compile error names; the build green. The engine version resolved the way the skill resolves it | green locally on 2026-10-19 (Maven `rewrite-maven-plugin` 6.46.1, Gradle `org.openrewrite:plugin` 7.39.0, fixture on the newest 3.5, target the Initializr's) · red with the `test_starters` key for the web slice misspelled, failing for Maven and for Gradle as "the compile error names no package test_starters holds" |
| `validate · design › schema` | the skill's class, sections, `**Unfiltered Bash:**`, `export` include and territory override | green |
| `validate · design › no hardcoded Spring/Java version outside decisions/` | the skill and its templates name no `Spring Boot N.N` | green |
| `validate · export-determinism` | the skill and the mode travel the same on every OS | covered by the existing step; no case added |

Not proven, on purpose:

- **A scheduled run.** The engine's public line already stopped once (Central's core at `8.90.4`); the job
  runs on a PR touching its paths, not weekly, so a drift in the engine between PRs shows at the next one.
- **Coverage of the rename list.** `renames` holds what this repository's exemplars name; a Boot package
  moved that no exemplar uses is the guided residue's, and nothing here measures how often that happens.
- **Jackson 3 code.** Packages `com.fasterxml.jackson.databind` and the checked-to-unchecked exception
  change are not renamed by the recipe: an annotation stayed, a package moved and an exception changed
  meaning, which is a decision per site, not a rename.
- **A multi-module Gradle build and a Kotlin DSL one.** The fixtures are single-module Groovy; the init
  scripts and the territory are written for the others and untested.
- **The vendored plugin copy.** It matches the source only after the tag that carries it exists.
