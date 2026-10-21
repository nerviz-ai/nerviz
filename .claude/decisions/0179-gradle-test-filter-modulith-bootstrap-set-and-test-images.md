# 0179 · The Gradle test filter mirrors Surefire, the bootstrap names the Modulith set it removes, and no Testcontainers image stays on `latest`

- **Date:** 2026-10-21
- **Scenario:** `lessons-learned-025.md` — one `/init-project` run of `modular-monolith` on Gradle
  (`defy-sentinel`, Boot 4.1.1, Modulith 2.1.1): `ModularityTests` never ran, the Initializr's
  Modulith registry broke the context, `shared` had no `OPEN` marker, and three smaller gaps.
- **Decision:** Form 2 — no new piece. Option 1: edits to the pieces that own each gap, plus `.claude/.ci/TestNameFilterTest.java`.
- **State:** approved by Lucas Fernandes, on 2026-10-21

## Reproduced on disk

| # | Fact | Evidence |
|---|---|---|
| 1 | The Gradle exemplar runs only `*Test` | `project-bootstrap/templates/build.gradle.parent.example:112` — `include '**/*Test.class'` |
| 2 | Two classes the same procedure generates end in `Tests` | `templates/features/spring-modulith/ModularityTests.java.example`; `*ApplicationTests` from the Initializr, extended by `scaffold.md:137` |
| 3 | The Maven template sets no Surefire `<includes>`, so Surefire's defaults run `*Tests` there | `templates/pom.parent.xml.example:188` — failsafe only |
| 4 | The filter's comment and the pom's cite `rules/naming.md` for the suffix; the norm lives in `rules/testing.md` § Names and shape | `rules/naming.md:87-92` delegates to `testing.md:71` |
| 5 | The catalog names only `spring-modulith-starter-core` | `project-bootstrap/references/dependency-catalog.md:18` |
| 6 | Nothing under `.claude/` names `event_publication`, `starter-jpa` or `events-kafka` | `grep` over `skills`, `blueprints`, `rules` |
| 7 | `messaging-architect` excludes in-process dispatch on purpose; broker delivery is Form B | `messaging-architect/SKILL.md:93`, `rules/messaging.md:101` |
| 8 | The blueprint says `shared` is `@ApplicationModule(type = OPEN)`; no step writes it | `modular-monolith.yaml:65,108`; no `ApplicationModule(` under `project-bootstrap` |
| 9 | `init-project` says "four placeholders"; the template leaves three | `init-project/SKILL.md:129`; `GENESIS.md.example:9` |
| 10 | The existence check depends on a listing cut at 30 lines | `init-project/SKILL.md:20,35-36` |
| 11 | Docker-architect's Kafka is `apache/kafka:3.8.0`; the Initializr writes `apache/kafka-native:latest` — different repositories, so `compose` never compares them | `docker-architect/templates/kafka-service.yml.example:32`; `ArchHook.java` `splitImage` |
| 12 | Every built-in blueprint writes `build.tool: maven` with no comment; `_schema.md` says it is overridable | `blueprints/*/*.yaml`; only `custom.template.yaml:18` says `# maven \| gradle` |

Not reproducible without network: the exact artifact set the Initializr serves for
`modulith` + `data-jpa` + `kafka` (§ 2 of the lesson). The record takes the run's list as the
observation; the catalog row names it as what the Initializr served for Boot 4.1.1.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | A Gradle modular monolith whose module verification never runs, with `build` green; a context that fails on a registry no case asked for | create nothing |
| 5 — nature | Procedure and data fixes in pieces that already own them | new skill, agent, rule |
| 7 — mandatoriness | The filter gap is a template defect: CI over the templates, not a hook in every project | Form 7c doctor line (option 2) |
| 8 — destination | Both: `testing.md`, `persistence-architect`, `test-architect` travel; `project-bootstrap`, `init-project`, blueprints catalog do not (the active blueprint does, `export.blueprint_copy`) | — |
| § 1 choice | Mirror Surefire's four default includes in Gradle | `*Tests` only, drop the include, rename to `*Test` |
| § 2 choice | Remove `starter-jpa` and `events-kafka` at bootstrap; the catalog names why; `persistence-architect` brings the registry back with its migration on the first cross-module in-process event; `events-kafka` never (Form B) | keep the registry + migration at bootstrap; keep both |
| § 6 choice | Bootstrap drops the unused Kafka container and pins every other `:latest` to docker-architect's template tag; `test-architect` adds the broker with compose's repository | pin all and keep Kafka; report only |
| scope | One record for all eight sections | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | Fix in the owning pieces + `.ci/TestNameFilterTest.java` | 9 | **Approved** |
| 2 | Option 1 + a `doctor` line in every generated project for a test class no build filter runs | 6 | Rejected — Form 7c against a failure seen only in a template |
| 3 | Option 1 without the CI test | 6 | Rejected — anti-pattern 21, the next filter edit regresses silently |
| 4 | create nothing | 2 | Rejected — the lesson stays a file |

### Option 1 — edits to the owning pieces, one new CI test (score 9)

**Motivator:** axes 1 and 5 — every gap has an owner already; axis 17 — the § 1 defect passed
every existing job.

**Pros:** no new piece; each fix lands where its norm or procedure already lives; the filter
gap becomes loud in `validate`; the Modulith and `latest` handoffs get a named owner.

**Cons:** two handoffs (`persistence-architect`, `test-architect`) that only prose holds; the
four Surefire patterns are an external fact to keep equal.

**Points cut in the rubric:** 6 (precedent) → none for a test-name check, half a point; the
handoffs are persuasion (4) but nothing executable could hold them before the case exists.

**CI:** new `.claude/.ci/TestNameFilterTest.java` in `validate` › `design`: the Gradle exemplar's
includes equal Surefire's four defaults, and every `*.java.example` under
`.claude/skills/**/templates/` that declares a `@Test` method is named so both tools run it
(`*IT` aside). `schema` covers the edited skills' frontmatter and sections unchanged.

### Option 2 — option 1 + a generated-project `doctor` line (score 6)

Form 7c in every project, against a failure observed only in a template. Invariant 6's mirror:
no user class has been seen skipped. Loses form fit, maintenance, cost of always running.

### Option 3 — option 1 without the test (score 6)

Anti-pattern 21: the defect passed `blueprints` because `BlueprintSkeletonTest` builds its own
minimal files, not the templates — the next filter edit regresses silently.

### Option 4 — create nothing (score 2)

The lesson stays a file; the next Gradle modular monolith ships without module verification.

## References

| Claim | Source |
|---|---|
| Surefire's default includes are `Test*`, `*Test`, `*Tests`, `*TestCase` | Maven Surefire `test` goal docs, `includes` parameter — re-verified in Phase 4 |
| A norm written in two places diverges | `@CLAUDE.md` invariant 2 |
| Versions are never written from memory — the `event_publication` schema is read from the resolved Modulith jar | `@CLAUDE.md` invariant 8 |
| `BlueprintSkeletonTest` does not prove the templates' build files | header of `.claude/.ci/BlueprintSkeletonTest.java` |
| `test-architect` owns the pins in `TestcontainersConfiguration` | `test-architect/SKILL.md:158,323` |

## Propagation

| File | Change |
|---|---|
| `skills/project-bootstrap/templates/build.gradle.parent.example` | `test` task includes Surefire's four defaults; comment cites `testing.md` |
| `skills/project-bootstrap/templates/pom.parent.xml.example` | comment names the four defaults and the Gradle mirror |
| `rules/testing.md` | § Names and shape: a whole-application test ends in `Tests` |
| `skills/project-bootstrap/references/dependency-catalog.md` | `spring-modulith` row names `starter-jpa` and `events-kafka`, removes both, says who brings them back |
| `skills/project-bootstrap/templates/features/spring-modulith/shared-package-info.java.example` | new — `@ApplicationModule(type = OPEN)` for `shared` |
| `skills/project-bootstrap/references/scaffold.md` | 4.7 `spring-modulith` row writes the marker; 4.10 step 3 drops the Kafka test container and pins `latest` |
| `skills/persistence-architect/SKILL.md` | owns the event publication registry and its migration, read from the resolved jar |
| `skills/test-architect/SKILL.md` | the messaging IT row names the broker container, with compose's repository and tag |
| `skills/init-project/SKILL.md` | "three" placeholders; `ls ../<name>` whatever the listing shows; git note in step 7 |
| `blueprints/*/*.yaml` (nine) and `blueprints/README.md` | `build.tool` is a default; Maven or Gradle is asked |
| `schemas/extensions.json` `migrations.entries` | two entries for projects generated before: `gradle-test-filter-tests-suffix` (nine blueprints; the prompt checks Gradle and the filter first and stops) and `modulith-shared-open` (`modular-monolith`; applied after the first). Not migrated: the Modulith starters (a project that got past the context load already dealt with them), the `latest` images (`compose` reports the Postgres tag), the `init-project` and blueprint-comment fixes (they touch no generated code) |
| `.claude/.ci/TestNameFilterTest.java`, `.github/workflows/validate.yml`, `docs/{en,pt-br}/07-ci-validate.md` | the CI check |

Goes to the generated project: **`rules/testing.md`, `persistence-architect`, `test-architect`, and the two migrations through `arch-adopt`**
(already in `export`; no manifest change). `project-bootstrap`, `init-project` and the blueprint
catalog stay here, as before. `ArchHook.java` is untouched, so the jar is not rebuilt.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · design › Gradle and Maven run the same test classes, and every test exemplar is named for both` | the Gradle `test` task includes exactly Surefire's four defaults and excludes `*IT`; the pom configures no Surefire; each of 25 test exemplars is named for a build to run | green on the tree · red with the old `include '**/*Test.class'`, red with Surefire configured in the pom, red with `ModularityTests` renamed `ModularitySpec` |
| `validate · design › frontmatter schema` | the edited skills keep their sections, class and export entry | green, exit 0 |

Not covered, and why: §§ 2, 3 and 6 are procedure for an agent run against the live Initializr;
the Initializr's artifact set needs network and moves with Boot. The generated project's own
`./gradlew build` is the proof, the way `lessons-learned-025` found them. Fixed directly, no
test: § 4 (a number in prose), § 5 (a precondition in prose), § 7 (YAML comments).
