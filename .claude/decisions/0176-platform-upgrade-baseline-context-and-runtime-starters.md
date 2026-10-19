# 0176 · `/platform-upgrade` records a test baseline, checks the context loads, fixes the Gradle wrapper before the new plugin configures, and adds what only runtime would miss

- **Date:** 2026-10-19
- **Scenario:** Issue #161, triaged at `978b792`: `platform-upgrade` records no test baseline before
  step 9, does not check that the application context loads before `verify`, places the Gradle
  wrapper remedy after step 6's first `./gradlew`, and `doctor.platform.upgrade` lacks the Flyway
  starter, the JDBC test slice, the cache starter and the Spring Cloud train.
- **Decision:** Form 2 (the existing `.claude/skills/platform-upgrade/SKILL.md`, edited) plus Form 7c — `ArchHook.java test-diff`
  (new) and `upgrade-recipe` extended, reading `doctor.platform.upgrade`. Option 1.
- **State:** approved by Lucas Fernandes, on 2026-10-19

## Reproduced on disk

From the `issue-verifier` table of `/triage-issue 161` at `978b792` (layer `static`); only the rows
it confirmed. No commit since `978b792` touches a cited file. The issue's proposed fix is not an
input to this record.

| # | Claim | Evidence |
|---|---|---|
| 1 | No rename to `spring-boot-starter-flyway`; `flyway-core` alone stays | `doctor.platform.upgrade.renames.dependencies` holds only `starter-web`, `starter-aop` and 4 Testcontainers entries. The Boot 4 runtime fact is not verified at triage |
| 2 | No map from a type in use to the starter it now needs (`@EnableCaching` → `spring-boot-starter-cache`) | the data; `.claude/skills/cache-architect/SKILL.md:182` already requires the starter on the floor |
| 3 | The Spring Cloud train and other BOMs stay on the old line | all 6 `recipes.bump` steps target `org.springframework.boot:*`; `engine` has no Initializr metadata source |
| 4 | No context-load step between steps 8 and 9 | `SKILL.md:181-184` stops at `test-compile` / `compileTestJava`; next is `verify` / `build` |
| 5 | Step 9 has no baseline: "red" carries no information | `SKILL.md:207`; step 1 (`:41-53`) runs no test |
| 6 | The wrapper remedy sits after the first `./gradlew` on the bumped plugin | first `./gradlew` after the bump: step 6 (`SKILL.md:153`); remedy: step 8 (`:196-198`); the CI fixture uses the Initializr's wrapper (`.claude/.ci/PlatformUpgradeTest.java:224`) |
| 7 | `gradle_current` gives a major jump with no choice of line | `engine.gradle_current` = `services.gradle.org/versions/current` |
| 8 | `AutoConfigureTestDatabase` absent from `renames.types` and `test_starters` | no `org.springframework.boot.jdbc.test.autoconfigure` key |
| 9 | The Jackson row points at a guard, not a guide | `SKILL.md:191` cites the `architecture-ddd.md` bullet that forbids Jackson in the domain |
| 12 | Step 4 gives no curl flags; the Plugin Portal redirects | `SKILL.md:107-111`; the CI helper uses `curl -sS --fail -L` (`PlatformUpgradeTest.java:389`) |
| 14 | The report has no Baseline / Wrapper / Third-party / Not verified lines | `SKILL.md:219-231` |

Unproven at triage: #10 — `ChangeDependency` wrote a stale `:3.3.4` pin on the aspectj rename (the recipe
options carry no version; the CI fixture never declared `spring-boot-starter-aop`). **Reproduced while
designing:** on Maven, `ChangeDependencyGroupIdAndArtifactId` on a `spring-boot-starter-aop` pinned to `3.3.4`
left `<version>3.3.4</version>` under the renamed artifact, and `RemoveRedundantDependencyVersions` in the same
run did not remove it; on Gradle `gradle.AddDependency` writes `:4.1.1` after the coordinate. The pin is real,
so the fix is a step of the recipe (below), not only a check.

## What the network showed

| Fact | Where |
|---|---|
| `start.spring.io/dependencies` (`Accept: application/vnd.initializr.v2.2+json`) returns `bootVersion`, `dependencies` (six carry their own `version`: `camel`, `springdoc-openapi`, `okta`, `dgs-codegen`, `mcp-security`, `jobrunr`) and `boms` (ten: `spring-cloud` → `org.springframework.cloud:spring-cloud-dependencies:2025.1.3`, `spring-modulith`, `spring-ai`, `vaadin`, …), all for the line it serves now. `?bootVersion=3.5.7` answers HTTP 400 and `/actuator/info` lists only current `bom-ranges`: the file cannot be asked for the old line, only for the target | fetched 2026-10-19, `bootVersion` `4.1.1` |
| Boot 4.0.8: `FlywayAutoConfiguration`, `LiquibaseAutoConfiguration` and `CacheAutoConfiguration` are **absent** from `spring-boot-autoconfigure` (present in 3.5.7) and present in `spring-boot-flyway`, `spring-boot-liquibase`, `spring-boot-cache`; `spring-boot-starter-flyway`, `-liquibase` and `-cache` depend on those modules; `spring-boot-starter-jdbc-test` depends on `spring-boot-jdbc-test`, whose jar holds `org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase` (3.5.7: `org.springframework.boot.test.autoconfigure.jdbc`) | the jars and POMs on Maven Central, 2026-10-19 |
| `org.openrewrite.maven.AddDependency` and `gradle.AddDependency` **require** `version`; with `latest.release` and a BOM-managed artifact Maven writes no `<version>`, Gradle writes `:<resolved>` | two runs on Initializr projects, 2026-10-19 |
| `org.openrewrite.text.FindAndReplace` reads a build file as text: once it has changed `pom.xml` or `build.gradle`, the Maven and Gradle recipes after it in the same list no longer act on it. A `UpgradeDependencyVersion` placed after it left `spring-cloud.version` on the old train in the fuller fixture and moved it in a minimal one | a failed run of `PlatformUpgradeTest`, then the two fixtures replayed by hand, 2026-10-19 |
| The Spring Boot Gradle plugin on Gradle 8.10.2 fails at configuration: `Spring Boot plugin requires Gradle 8.x (8.14 or later) or 9.x. The current version is Gradle 8.10.2`. `services.gradle.org/versions/all` lists every release with `final` and `broken` | `./gradlew -q help`, 2026-10-19 |

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — Symptom | A real upgrade run, reported with 13 claims the verifier confirmed | create nothing |
| 2 — Trigger | Unchanged: `/platform-upgrade`, typed by the person | — |
| 7 — Mandatoriness | Not a session guarantee; the guarantee stays `export`'s refusal (0161) | Forms 7a, 7b, 8 |
| 8 — Destination | Both: the skill, the mode and the data travel (`export.skills.include`) | — |
| 9 — Integration | Same owners as 0175: `platform-upgrade`, `upgrade-recipe`, `doctor.platform.upgrade` | — |
| Baseline | Compared by a mode, not by the model | baseline by prose |
| Context load | The project's own `@SpringBootTest` classes, run first and on their own; none → reported as not verified | a temporary test written into `src/test` |
| Runtime starters, BOM trains | Deterministic in `upgrade-recipe`: a type-in-use → starter map, and the BOMs the Initializr lists for the target | residue rows |
| Wrapper | Before the bumped plugin configures: read the accepted lines from the plugin's own refusal, take the lowest, edit `distributionUrl`, then refresh the scripts | a minimum Gradle per line written into the data (ages; spirit of invariant 8) |
| Extras | F10 report lines, F9 explicit `curl -sS --fail -L`, F5 Jackson row → `engine.migration_guide`, F7 a pin check after pass 1 | — |
| 17 — CI | Answered from `references/ci-coverage.md` and the workflows, per option | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | Skill edits + `upgrade-recipe` gains `requires_starter` and `--initializr` + new mode `test-diff` (Form 7c) + data | 8 | **Approved** |
| 2 | Option 1 without `test-diff`: the baseline compared by the model | 7 | Rejected — a comparison over hundreds of cases left to persuasion |
| 3 | Skill text and the four data entries only: starters and trains as residue rows, baseline by the model | 5 | Rejected — leaves the runtime failures to a loop that reads compile errors |
| 4 | A temporary context-load test written into `src/test`, run and deleted | 4 | Rejected — a false red wherever the context needs a database or broker that is not up |
| 5 | Minimum Gradle per Boot line as data | 4 | Rejected — a version written into the repo ages; the plugin's own refusal states it at run time |
| — | create nothing beyond the four data entries | 3 | Rejected — leaves the baseline, the context and the wrapper ordering as they failed |

### Option 1 — skill + `upgrade-recipe` extended + `test-diff` + data (score 8)

**Motivator:** axis 1 and the baseline answer — the same split 0175 made: mechanical and comparable
parts deterministic, judgment guided.

**Shape:**

1. **Step 3, Initializr metadata.** After the target, `curl -sS --fail -L -H "Accept: <engine.initializr_accept>"
   "<engine.initializr_dependencies>" -o "$WORK/initializr.json"` — without `?bootVersion=`, which the Initializr
   refuses for a line it no longer serves. The mode refuses a file whose `bootVersion` is not `--target`. The
   download failing → go on without it; the report says the BOMs were not moved.
2. **Step 4, explicit fetches.** Every metadata and POM read is `curl -sS --fail -L` (F9).
3. **Step 4b, baseline.** On the untouched tree, `./mvnw -B -fae clean verify -Dmaven.test.failure.ignore=true` /
   `./gradlew clean build --continue`; then `ArchHook.jar test-diff snapshot . --out "$WORK/baseline.json"`.
   A tree that does not build before the change → no baseline, said in the report; every red later is
   reported as it is today.
4. **Step 5, pass 1** — `upgrade-recipe --pass bump --initializr "$WORK/initializr.json"` writes, in this order:
   the Boot bump; one version bump per `boms` entry of the file plus each `dependencies` entry carrying its
   own `version` (a coordinate the project does not declare is a no-op); the coordinate renames; one
   `AddDependency` (`version: latest.release`, `onlyIfUsing`) per `requires_starter` entry; the type renames;
   and **last** one `FindAndReplace` pair per renamed or added coordinate that removes the version it
   carried (F7). The order is the finding of the first real run: a text edit turns a build file into plain
   text for the recipes after it. No `--initializr` → no BOM steps, said on stderr. The skill still reads the
   diff afterwards for a pin or a BOM the recipe could not see (a catalog, a script variable).
5. **Step 5b, Gradle wrapper.** Before step 6: `./gradlew -q help`. A refusal listing the accepted lines
   (`8.x (8.14 or later) or 9.x`) → the lowest accepted major, its newest `final`, non-`broken` release in
   `engine.gradle_versions_all`, put in `distributionUrl` of `gradle/wrapper/gradle-wrapper.properties`
   (which no build configures), then `./gradlew wrapper --gradle-version <it>` to refresh the scripts.
   Replaces the step 8 sentence.
6. **Step 8, residue.** Jackson row → `engine.migration_guide` § Jackson (F5). Springdoc row widened to
   any third-party artifact the Initializr file does not list.
7. **Step 8b, context load.** The test classes annotated `@SpringBootTest` run first and on their own
   (`-Dtest=… -Dsurefire.failIfNoSpecifiedTests=false` / `--tests …`). Red → a context failure, its first
   cause line, back into the residue loop under the same three-strikes rule. None → `Not verified:
   context load` in the report.
8. **Step 9, verify against the baseline.** After `verify` / `build`, `test-diff compare . --baseline
   "$WORK/baseline.json"`: exit 2 on a regression (green before and red now, or red and absent before);
   pre-existing failures and fixed tests are listed, never counted as regressions.
9. **Report** gains `Baseline`, `Context`, `Wrapper`, `BOMs` and `Not verified` lines (F10).

**Data:** `doctor.platform.upgrade` — `renames.dependencies` += `flyway-core` → `spring-boot-starter-flyway`,
`liquibase-core` → `spring-boot-starter-liquibase`; `renames.types` += `AutoConfigureTestDatabase`;
`test_starters` += `org.springframework.boot.jdbc.test.autoconfigure` →
`spring-boot-starter-jdbc-test`; new `requires_starter`
(`org.springframework.cache.annotation.EnableCaching` → `spring-boot-starter-cache`); `recipes.add`,
`recipes.unpin` and `recipes.bom`; `test_reports` (the report globs of Surefire, Failsafe and Gradle);
`engine.initializr_dependencies`, `engine.initializr_accept`, `engine.gradle_versions_all`,
`engine.gradle_distribution`. Each `to` was checked against the published artifact, and each runtime claim
(Flyway, Liquibase and cache auto-configuration moved out of `spring-boot-autoconfigure`) against the jars
of both lines, before it landed (clean room, `upgrade.$comment`) — see *What the network showed*.

**Pros:** a red build says what the upgrade broke and what was already broken; runtime-only gaps
handled before `verify`; trains read at run time from the Initializr (invariant 8); every new step
testable; nothing written into the project beyond what the upgrade needs.

**Cons:** a second mode for this skill, three data blocks more, a longer test run (the baseline is a
full build); `AddDependency` with a BOM-managed version needs verifying before it lands.

**Points cut in the rubric:** 5 (maintenance — one more mode, more data).

**CI:** `UpgradeRecipeTest` gains cases (a `requires_starter` entry → its `AddDependency` steps; an
Initializr file → one bump per BOM and per versioned dependency; a missing or malformed file → exit 2,
nothing written; no file → no BOM step). New `.claude/.ci/TestDiffTest.java`, a step in
`validate › hooks-cross-platform`: Surefire and Gradle report trees built in the test — green→red exit 2
naming the test, red→red exit 0 listed as pre-existing, red→green listed as fixed, `error` and `failure`
both red, `skipped` neither, no baseline file → exit 2. `PlatformUpgradeTest` (`templates.yml`): the
Gradle fixture on a wrapper older than the plugin demands (the newest release of the previous major,
read from `engine.gradle_versions_all` at run time), a `@SpringBootTest` context test, `@EnableCaching`
and `flyway-core` with one migration — assert the wrapper moved, the starters landed, the migration ran,
and `test-diff compare` exits 0. `design › schema` covers the skill.

### Option 2 — option 1 without `test-diff` (score 7)

The baseline is the report directories copied to `$WORK/baseline/`, compared by the model at step 9.
Cuts 4 (a comparison over hundreds of report files left to persuasion). CI: `UpgradeRecipeTest` and
`PlatformUpgradeTest` as in option 1, no `TestDiffTest`.

### Option 3 — skill text and four data entries (score 5)

The four data entries; `@EnableCaching` and the trains as residue rows; baseline by the model. Cuts 4
twice (the runtime failures surface in no compile error the residue loop reads) and 1 (a mechanical,
listable change left to the model, against 0175's split). CI: `PlatformUpgradeTest` only.

### Option 4 — temporary context-load test (score 4)

Writes `src/test/.../NervizContextLoadsTest.java`, runs it, deletes it. Always checks, but a context that
needs a datasource or broker fails without one — a red the upgrade did not cause — and the skill would
create and delete code the person never asked for.

### Option 5 — minimum Gradle per line as data (score 4)

A version written into the repository; it ages with every Gradle and Boot release, and the Boot plugin
already states it in its refusal.

## References

| Claim | Source |
|---|---|
| Versions resolved at run time via the Initializr | `@CLAUDE.md` invariant 8 |
| Lists a mode reads live in `extensions.json` | `@CLAUDE.md` invariant 10 |
| A norm has one owner; the guide owns Jackson facts | `@CLAUDE.md` invariant 2 |
| Mechanical part deterministic, judgment guided; clean-room `to` check | `@.claude/decisions/0175-platform-upgrade-below-the-floor.md` |
| No network inside the jar; the skill downloads, the mode reads a file | 0175 option 6; 0163 |
| A Form 7c mode always needs its own test | `.claude/skills/claude-code-architect-designer/references/ci-coverage.md` |
| Network or minutes → `templates.yml`, paths in both lists | `ci-coverage.md`; 0099 |
| A guarantee is bought against an observed failure | decision matrix § 2.2, last row; the issue's run |

## Propagation

| File | Change |
|---|---|
| `.claude/skills/platform-upgrade/SKILL.md` | steps 3 (Initializr metadata), 4 (`-L` on every fetch), 4b (baseline), 5 (`--initializr`, the diff read), 5b (Gradle wrapper), 8 (Jackson row to `engine.migration_guide`; the third-party row; the wrapper sentence moved), 8b (context load), 9 (`clean`, `test-diff compare`), report lines, `## Why`, `## Contract` |
| `.claude/hooks/ArchHook.java` | `upgrade-recipe`: `--initializr`, `requires_starter`, unpin, BOM steps, order; new mode `test-diff` (dispatch, header, usage, Javadoc naming this record) |
| `.claude/hooks/ArchHook.jar` | rebuilt under the pinned JDK |
| `.claude/schemas/extensions.json` | `doctor.platform.upgrade`: `recipes.add|unpin|bom`, `requires_starter`, `test_reports`, `engine.initializr_dependencies|initializr_accept|gradle_versions_all|gradle_distribution`, two dependency renames, one type rename, one test starter; the block's `$comment` names the new placeholders and this record |
| `CLAUDE.md` | Commands: the `upgrade-recipe` row extended, a `test-diff` row; Routing: the `platform-upgrade` row |
| `README.md` | the *What it changes / How* paragraph; the mode table row for `test-diff` |
| `docs/en/15-platform-upgrade.md`, `docs/pt-br/15-platform-upgrade.md` | changes table, diagram, passes, steps 4b / 5b / 8b, data keys, what it does not do (Jackson), CI table, report |
| `docs/en/07-ci-validate.md`, `docs/pt-br/07-ci-validate.md` | `TestDiffTest` node, row and command; the `UpgradeRecipeTest` and `PlatformUpgradeTest` rows |
| `.claude/.ci/UpgradeRecipeTest.java`, `.claude/.ci/PlatformUpgradeTest.java` | new cases (below) |
| `.claude/.ci/TestDiffTest.java` | new |
| `.github/workflows/validate.yml`, `.github/workflows/templates.yml` | the `TestDiffTest` step in `hooks-cross-platform`; the notes of the `platform-upgrade` job; its timeout 30 → 40 minutes |

Not touched: `export.skills` (the skill was already listed), `skill_classes` (the class and territory are the
skill's, unchanged), `nerviz-ai/marketplace` — the vendored copy is the source's file at a tag and follows
the next release, as in 0175.

Goes to the generated project: **yes, via `export.skills.include`** — the skill, both modes and the data travel whole,
as in 0175.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · hooks-cross-platform › upgrade-recipe writes the bump and the property renames, and refuses what it cannot spell` | `UpgradeRecipeTest`, new cases: an `AddDependency` pair per `requires_starter` entry with its `onlyIfUsing` (per tool); the unpin pair for the added starter and for every renamed coordinate, dots escaped; a BOM and a versioned dependency of an Initializr file become version bumps in key order, one without a version does not; no `--initializr` → no BOM and a note; a file for another Boot version, an unreadable one, a data block with no `add`, `unpin` or `bom` template → exit 2, nothing written; the text edits are the last steps | green on the tree · red with `onlyIfUsing` removed from the Maven `AddDependency` template, failing as "bump adds spring-boot-starter-cache for maven only when org.springframework.cache.annotation.EnableCaching is used" |
| `validate · hooks-cross-platform › test-diff separates what the upgrade broke from what was already broken` | `TestDiffTest`: snapshot of Surefire, Failsafe and Gradle (module) reports; compare exits 0 on the same reports, a failure already there, a fix and a skip; 2 on a regression, a vanished test and a new failure each alone, and together; 2 on no report, a foreign baseline, a report that is not XML, one with a DOCTYPE, and a source with no `test_reports` | green on the tree · red with a vanished test no longer counted, failing as "compare with only a test that disappeared — expected exit 2 … got 0" |
| `templates · platform-upgrade › Maven and Gradle fixtures, 3.x to the Initializr's line, context loads, no test worse` | `PlatformUpgradeTest`, new on both tools: a baseline of three cases before the bump and `compare` after (3 now, 3 before, none worse); the context test passes only with the cache and Flyway starters; the Spring Cloud BOM moves from the previous train to the Initializr's; `starter-aop` pinned to `3.3.4` loses its pin on the rename; the added cache starter carries no version; on Gradle, Gradle `8.10.2` is refused by the new plugin and fixed to the lowest accepted major's newest release | green locally on 2026-10-19 (Maven `rewrite-maven-plugin` 6.46.1, Gradle `org.openrewrite:plugin` 7.39.0, fixture on the newest 3.5, target 4.1.1, Gradle 8.10.2 → 8.14.6) · red with the cache entry of `requires_starter`, the Flyway rename and the unpin patterns removed from the data: the three cases fail by name (flyway not renamed, cache starter missing, a version kept) — and, before the BOM steps were moved ahead of the text edits, with "pom.xml still carries the old Spring Cloud train" on both tools |
| `validate · design › schema` | the skill's sections and the data block it reads | green |

Not proven, on purpose:

- **Liquibase end to end.** Its rename rests on the jars (`LiquibaseAutoConfiguration` moved to `spring-boot-liquibase`,
  `spring-boot-starter-liquibase` depends on it); no fixture runs a Liquibase changelog.
- **`AutoConfigureTestDatabase`.** The new package and starter are checked against the published jars; no fixture
  uses a JDBC test slice, and that `ChangeType` carries the nested `Replace` enum with the annotation is not run.
- **A library whose artifact was renamed** (`jobrunr-spring-boot-3-starter` → `-4-starter` is listed by the Initializr as
  a new artifact): the version bump moves a coordinate the project declares, it does not rename one.
- **A `@SpringBootTest` that needs infrastructure.** Step 8b skips a class red in the baseline; the case where the
  baseline had no database up is not exercised.
- **A multi-module or Kotlin DSL Gradle build,** as in 0175.
