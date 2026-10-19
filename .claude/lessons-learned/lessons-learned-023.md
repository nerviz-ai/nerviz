# Lessons learned — `/nerviz:platform-upgrade` on a real project

Feedback for the `nerviz-ai/nerviz` meta-repo, from one real run of the skill.

| | |
|---|---|
| Date | 2026-10-19 |
| Project | `dev-connect-be` — Gradle (Groovy DSL), JDK 21, Spring Boot 3.3.4, JPA + Flyway + SNS/SQS + OAuth2 login + OpenFeign + springdoc |
| Skill | plugin `nerviz` 0.41.0 (`.plugin-source.json` ref `v0.41.0`) |
| Data fetched | source tag `v0.41.1` (newest tag, per `arch-adopt` § 2) |
| Engine | `org.openrewrite:plugin` 7.39.0 (`rewrite-bom` 8.89.0) |
| Target | Spring Boot 4.1.1 (from the Initializr) |
| Outcome | Build compiles on 4.1.1. Test suite result is identical to the pre-upgrade baseline (11 of 41 failing before and after, same messages). Nothing committed. |

Evidence levels used below: **Observed** (seen in this run's output), **Inferred** (reasoned from observed facts, not tested), **Not verified**.

## Summary

| # | Severity | Where | Finding |
|---|---|---|---|
| F1 | High | Step 8 / data | Three regressions that compile cannot see (Spring Cloud train, cache, Flyway). Each surfaced one at a time as an unrelated-looking context-load failure. |
| F2 | High | Step 9 | Verify has no baseline. The project's suite was already red, so "red" says nothing about the upgrade. |
| F3 | High | Steps 6, 8 | The Gradle wrapper remedy in step 8 cannot run: the build already fails at step 6, and `./gradlew wrapper` needs a configurable build. |
| F4 | Medium | Data | `AutoConfigureTestDatabase` is missing from `renames.types` and `test_starters`. |
| F5 | Medium | Step 8 table | The Jackson row points to a guard rule, not a migration guide. |
| F6 | Medium | Procedure | Third-party BOM compatibility (Spring Cloud, Spring Cloud AWS, springdoc) is not covered. |
| F7 | Low | Step 5 | `ChangeDependency` wrote a stale version pin onto the renamed starter. |
| F8 | Low | Step 2 | Skill text (0.41.0) and fetched data (0.41.1) came from different tags. |
| F9 | Low | Step 4 | The Gradle engine-version procedure is prose; one missing `-L` made every candidate look broken. |
| F10 | Low | Report | The report template has no place for baseline, wrapper bump, third-party bumps or unverified areas. |

## Findings

### F1 — Runtime-only regressions after a green compile (High)

After the bump, `compileTestJava` was green. The suite then failed in three successive layers, each hidden behind the previous one:

1. **Spring Cloud train left on the Boot 3 line.** Observed: `java.lang.ClassNotFoundException: org.springframework.boot.autoconfigure.web.ServerProperties`, thrown from `SimpleDiscoveryClientAutoConfiguration` (`spring-cloud-commons 4.1.4`). The `springCloudVersion = "2023.0.3"` BOM import was untouched by pass 1.
2. **`@EnableCaching` with no cache module.** Observed: `NoSuchBeanDefinitionException: No qualifying bean of type 'org.springframework.cache.CacheManager' available: no CacheResolver specified - register a CacheManager bean or remove the @EnableCaching annotation`. Fixed with `org.springframework.boot:spring-boot-starter-cache`.
3. **Flyway did not run.** Observed: no error mentioning Flyway anywhere. `org.flywaydb:flyway-core` alone no longer triggers Spring Boot's Flyway auto-configuration. Symptom was HTTP 500 on `POST /api/appraisers` with `Cannot invoke "java.util.Set.forEach(...)" because the return value of "...AppraiserEntity.getRoles()" is null`, because the seed roles never loaded. The test log had 0 mentions of "flyway" after the upgrade and 4 on the baseline. Fixed by replacing `flyway-core` with `org.springframework.boot:spring-boot-starter-flyway` (its POM depends on `spring-boot-flyway`, checked on Central). After the fix, 19 regressions disappeared.

Impact: item 3 is the dangerous one. It is a silent behaviour change (schema migrations not applied), found only because a baseline comparison existed (see F2). A project with no tests exercising the database would have shipped without migrations.

Recommendations:
- **Data.** Add to `renames.dependencies`: `org.flywaydb:flyway-core` → `org.springframework.boot:spring-boot-starter-flyway`. Verified in this run.
- **Data.** Add a "starter required by" map, keyed by type or annotation, for modules whose auto-configuration moved out of `spring-boot-autoconfigure`. Verified entry: `org.springframework.cache.annotation.EnableCaching` → `spring-boot-starter-cache`. Inferred, not tested here: `liquibase-core` → `spring-boot-starter-liquibase`. Check it before adding.
- **Procedure.** Add a step between 8 and 9 that loads the application context (a `@SpringBootTest` smoke test, or `bootRun` against test config). Compile success is not a signal for auto-configuration moves.
- **Procedure.** Bump the Spring Cloud train in pass 1 (see F6).

### F2 — Verify has no baseline (High)

Step 9 says "Green → continue. Red → the failing tests and the first error are the report." The project's suite was red before any change: 41 of 41 failing without `AWS_REGION` (`Unable to load region from any of the providers in the chain`), 11 of 41 with `AWS_REGION=us-east-1` (tests that publish to real SNS). Against that, "red" carries no information.

What I had to do by hand:
1. `git worktree add --detach <scratch> HEAD`, run `./gradlew test` there, and remove the worktree afterwards.
2. Export per-testcase pass/fail from `build/test-results/test/*.xml` for both trees and diff them.
3. Compare failure messages as well, not only names.

Result: baseline 11 failed, upgraded 11 failed, same tests, same messages. Before the Flyway fix the diff showed 19 extra failures, all in `AppraiserFlowTest` and `PersonFlowTest`, which is how F1.3 was found.

Observed side effect: the environment failure (missing AWS region) masked everything else. Both suites were 41/41 red, so the Spring Cloud failure from F1.1 looked like part of the existing problem. I set `AWS_REGION` only for diagnosis and did not persist it anywhere.

Recommendations:
- Step 1 (after the clean-worktree check): run the project's tests on the untouched tree and keep the per-test result. Use a separate worktree so the project tree stays clean.
- Step 9: define "green" as "no regression against the baseline", and report new failures separately from pre-existing ones.
- Consider an `ArchHook.jar` mode that does the per-test diff, for the same reason the skill gives for `upgrade-recipe`: same input, same result, testable.

### F3 — The wrapper remedy cannot run (High)

Step 8 says a Gradle build that refuses the new plugin for its wrapper's version is changed once with `./gradlew wrapper --gradle-version <current>`. In practice the refusal comes earlier and blocks the remedy:

- Observed at step 6 (not step 8), on the first `./gradlew` call after the bump: `Spring Boot plugin requires Gradle 8.x (8.14 or later) or 9.x. The current version is Gradle 8.10.2`. Steps 6 and 7 cannot run until this is fixed.
- `./gradlew wrapper` configures the build first, so it fails with the same error. I edited `distributionUrl` in `gradle/wrapper/gradle-wrapper.properties` by hand to `gradle-9.8.2-bin.zip`, then ran `./gradlew wrapper --gradle-version 9.8.2` to regenerate the jar and scripts.
- Pass 1 itself ran fine on the old wrapper, because the plugin bump is applied only after the recipe finishes.

Related observations:
- `gradle_current` returned 9.8.2, a major jump from 8.10.2. The Boot floor in the error is 8.14. A newer 8.x could be the lower-risk choice; the skill gives no way to choose.
- Regenerating the wrapper rewrote `gradlew`, `gradlew.bat` (CRLF/LF churn, 86 lines in the diff) and the jar.
- Not verified: the `org.sonarqube 5.1.0.4882` and `io.gatling.gradle 3.12.0` plugins on Gradle 9.8.2. `compileJava`, `test` and `jacocoTestReport` ran; the `sonar` and Gatling tasks did not.

Recommendations:
- Move the wrapper step to right after pass 1 for Gradle: edit `distributionUrl` first, then run `wrapper`. State the minimum Gradle version of the target Boot line in the data, so the skill can decide without a failing build.
- Say in the report which Gradle plugins were not exercised on the new wrapper.

### F4 — Test-slice data is incomplete (Medium)

Compile error: `org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase` not found, in a `@SpringBootTest` + `@AutoConfigureTestDatabase(replace = NONE)` base class. This is a very common combination.

Verified by listing the 4.1.1 jar: the class is `org/springframework/boot/jdbc/test/autoconfigure/AutoConfigureTestDatabase.class` in `spring-boot-jdbc-test`; `spring-boot-starter-jdbc-test` depends on it.

It is in neither `renames.types` nor `test_starters`, so pass 1 did not touch it and the table row only helps after a compile error.

Proposed data:

```json
"renames.types": [
  { "from": "org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase",
    "to":   "org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase" }
],
"test_starters": {
  "org.springframework.boot.jdbc.test.autoconfigure": "org.springframework.boot:spring-boot-starter-jdbc-test"
}
```

My own imprecision: I added `spring-boot-starter-data-jpa-test` (it pulls `jdbc-test` transitively). The project has no `@DataJpaTest`, so `spring-boot-starter-jdbc-test` is the minimal and correct choice. I did not change it after noticing; it is a one-line edit in `build.gradle`.

Also: `renames.types` was not exercised in this run (the project uses none of the listed types), so those entries remain untested by this run.

### F5 — The Jackson row points to a rule, not a guide (Medium)

The step 8 table sends the reader to "the Jackson bullet of `$SRC/.claude/rules/architecture-ddd.md` — the two roots — then the code". That bullet (the "Domain" section) is a guard about forbidden imports in the domain layer. It says Jackson 3 moved the core to `tools.jackson` and kept annotations in `com.fasterxml.jackson.annotation`. It contains no migration steps.

What I had to work out myself, with the evidence level:

| Change | Evidence |
|---|---|
| `com.fasterxml.jackson.databind.ObjectMapper` → `tools.jackson.databind.ObjectMapper` | Observed (compile) |
| `com.fasterxml.jackson.core.type.TypeReference` → `tools.jackson.core.type.TypeReference` | Observed (compile) |
| Checked `JsonProcessingException` → unchecked `tools.jackson.core.JacksonException`; `throws` clause no longer needed | Observed (compile) |
| `new ObjectMapper().findAndRegisterModules().registerModule(..)` → `JsonMapper.builder().findAndAddModules().addModule(..).build()` | Observed (compile); the mapper is immutable now |
| `JavaTimeModule` no longer needed | Inferred; I removed it and the tests behave as the baseline |
| `PageJacksonModule` / `SortJacksonModule` (OpenFeign) now extend `tools.jackson.databind.JacksonModule` | Observed (`javap` on `spring-cloud-openfeign-core-5.0.3.jar`) |
| `com.fasterxml.jackson.annotation.JsonFormat` unchanged | Observed |
| Default date serialization becomes ISO-8601 instead of timestamps | Not verified |

Recommendations:
- Point the table row at `engine.migration_guide`, or ship a short Jackson 2 → 3 mapping next to the data.
- Add `ChangeType` entries for the mechanical renames: `TypeReference` and `JsonProcessingException` → `JacksonException`. A blind `ChangeType` for `ObjectMapper` is also possible, but mapper construction and module registration stay guided work.

### F6 — Third-party BOM compatibility is not covered (Medium)

Pass 1 changes the Boot version and nothing else. In this project three other version lines had to move:

| Library | Before | After | How the version was chosen |
|---|---|---|---|
| Spring Cloud (BOM) | 2023.0.3 | 2025.1.3 | `https://start.spring.io/actuator/info` → `bom-ranges."spring-cloud"`: `"2025.1.3": "Spring Boot >=4.0.0 and <4.2.0-M1"` |
| Spring Cloud AWS | 3.1.0 | 4.2.0 | Not in the Initializr (`null`). Central `<release>`; its POM parent is `spring-cloud-build 5.0.2`. The release notes contain no Boot compatibility statement. |
| springdoc | 2.2.0 | 3.1.1 | Not in the Initializr (`null`). Central `<release>`; its POM parent is `spring-boot-starter-parent 4.1.0`. |

Notes:
- `<release>` on Central means "newest", not "compatible with the target". For libraries outside the Initializr the only compatibility evidence I had was the POM parent, plus the Spring context loading (SNS/SQS beans were created). That is inferred evidence.
- The step 8 table covers springdoc only for "BOM no longer resolves". Here `springdoc 2.2.0` still resolved (it is not BOM-managed), compiled, and would have stayed on the old line silently.
- springdoc 3.1.1 is not verified at runtime: no test calls `/v3/api-docs` or the Swagger UI.

Recommendations:
- Add `engine.initializr_info` (`https://start.spring.io/actuator/info`) to the data and make "bump the Spring Cloud train from `bom-ranges`" part of pass 1 or a named step.
- For libraries outside the Initializr, state the rule explicitly: choose the newest release whose POM parent or BOM line matches the target, and say in the report that this is inferred.
- The skill promises it never writes a version from memory; the data above keeps that promise only if the oracle for these libraries is named.

### F7 — `ChangeDependency` wrote a stale version pin (Low)

After pass 1, `build.gradle` contained:

```
implementation 'org.springframework.boot:spring-boot-starter-aspectj:3.3.4'
```

The original `spring-boot-starter-aop` line had no version. The `spring-boot-starter-web` → `spring-boot-starter-webmvc` rename, from the same recipe, did not get one. The cause is not determined. The pin would override the Boot BOM with a Boot 3 version, and nothing in the procedure checks for it. I removed `:3.3.4` by hand.

Recommendation: after pass 1, grep the changed coordinates for a version suffix, and list pins in the report. If the recipe options allow it, investigate `newVersion` handling in `ChangeDependency`.

### F8 — Skill text and data come from different tags (Low)

The plugin cache held `0.41.0` and its `.plugin-source.json` says `ref: v0.41.0`. `git ls-remote` returned `v0.41.1` as the newest tag, and the skill (via `arch-adopt` § 2) fetches the newest tag by default. So the instructions I followed were from 0.41.0 and the data and `ArchHook.jar` from 0.41.1. Nothing broke, but the pairing is untested.

Recommendation: default `--ref` for this skill to the plugin's own ref, or print both refs in the report.

Related friction: the step 2 text says "as `arch-adopt` § 2-3 does it". That means reading a second skill to run this one. A short inline copy of the fetch commands would remove the dependency.

### F9 — The Gradle engine-version step is prose (Low)

Step 4 says to take the newest candidate whose POM names a `rewrite-bom` that Central serves, but gives no flags for fetching the POM. `plugins.gradle.org/m2` answers with a redirect, so `curl -sS --fail` returned an empty body, the `rewrite-bom` version parsed as empty, and the Central check ran against `rewrite-bom//rewrite-bom-.pom` and returned 404 for every candidate. That looked like "the engine's public line moved", which is the skill's stop condition.

Needed: `curl -sSL --fail`. The second run was correct.

Validated by this run: the trap described in `engine.$comment` is real as of 2026-10-19. 7.41.0 and 7.40.0 import `rewrite-bom` 8.91.0 (404 on Central); 7.39.0 imports 8.89.0 (200), so 7.39.0 is the correct choice. The Maven plugin `<release>` was 6.46.1 and was not used.

Recommendation: turn step 4 into an `ArchHook.jar` mode (for example `engine-version --build gradle`) with the redirect handling inside, as the skill does for `upgrade-recipe`.

### F10 — The report template misses what mattered (Low)

The template has no line for: the pre-upgrade baseline (F2), the wrapper change (F3), third-party bumps (F6), version pins found (F7), or areas not verified. Pass 1 "files changed (n sources, n build)" also under-reports: the wrapper files changed after pass 1.

Recommendation: add `Baseline`, `Wrapper`, `Third-party`, `Not verified` lines.

## What worked as designed

- Clean-worktree refusal, tool checks, and "never commits": all behaved as written. The diff was reviewable with `git diff`.
- `grep -q 'upgrade-recipe'` guard on the fetched `ArchHook.java`: passed, mode present.
- Initializr as version oracle: returned 4.1.1 without ambiguity.
- Both init-script templates (`rewrite-init.gradle.example`, `classpath-init.gradle.example`) worked unchanged. The classpath step produced 309 jars.
- `renames.dependencies` for `spring-boot-starter-web` → `spring-boot-starter-webmvc` and `spring-boot-starter-aop` → `spring-boot-starter-aspectj` applied correctly.
- Pass 2 produced 243 renames (written from the jars' metadata) and applied none. The mechanics were correct; this project simply had none of the keys.
- The "minimal edit, report failing tests, never delete or loosen them" rules were followable. No test was changed, skipped or deleted.

## Not verified in this run

- Runtime behaviour of the rewritten `SecurityConfig.accessTokenResponseClient()` (`DefaultAuthorizationCodeTokenResponseClient` was removed in Spring Security 7; replaced by `RestClientAuthorizationCodeTokenResponseClient` with a `RestClient`). The class is annotated `@Profile("!test")`, so the suite never loads it. It compiles; nothing more is known. The project's custom `OAuth2AccessTokenResponseHttpMessageConverter` still extends the Jackson 2 based `MappingJackson2HttpMessageConverter`, and I did not check its deprecation status.
- springdoc 3.1.1 and the Swagger UI.
- The `sonar` and Gatling Gradle tasks on Gradle 9.8.2.
- `application.yml` against property changes that carry no metadata (pass 2 only renames what jars declare).
- Jackson 3 default serialization differences (dates, enums) in production responses.
- Deprecation warnings: compile ran with `-q` and I did not inspect them.
- The 11 pre-existing failures: unchanged, not investigated (they need real AWS).

## Suggested order of work for the meta-repo

1. F2 baseline in the procedure (changes how every run is judged).
2. F1 data entries (`flyway-core`, cache starter) and the context-load gate.
3. F3 wrapper step moved before the classpath step.
4. F6 Spring Cloud train via `bom-ranges`.
5. F4, F5 data and Jackson mapping.
6. F7, F9 as checks or modes in `ArchHook.jar`.
7. F8, F10 housekeeping.
