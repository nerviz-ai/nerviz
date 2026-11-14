# Lessons learned: `/nerviz:platform-upgrade` on a real project

Run date: 2026-10-19. Target repository for these recommendations: `nerviz-ai/nerviz` (and the `nerviz` plugin marketplace copy).

## Run summary

| Item | Value |
|---|---|
| Project | `dev-connect-be`, Gradle (groovy DSL), Java 21, 41 test cases |
| Starting line | Spring Boot 3.3.4 (Gradle wrapper 8.10.2) |
| Target | Spring Boot 4.1.1 (from `start.spring.io`) |
| Plugin skill version executed | `nerviz` plugin `0.41.0` (`SKILL.md` as vendored in the plugin cache) |
| Source ref fetched | `v0.41.2` (newest tag) |
| Engine | `org.openrewrite:plugin` 7.39.0 |
| Outcome | Green. 41 test cases before, 41 after, 0 regressions (`test-diff compare`) |
| Hand edits needed after the recipes | 5 groups (listed below) |

The procedure reached a green build, and the baseline/compare mechanism did its job. The items below are what did not go as the skill text led me to expect, ordered by how much they cost or could cost another user.

## What worked as designed

These are worth keeping and are evidence the design is sound.

1. **The Gradle plugin trap documented in `engine.$comment` is real today.** `org.openrewrite:plugin` 7.41.0 and 7.40.0 both import `rewrite-bom` 8.91.0, which Maven Central does not serve. Walking back to 7.39.0 (`rewrite-bom` 8.89.0, HTTP 200) worked on the first try.
2. **The init-script approach** (`rewrite-init.gradle`, `classpath-init.gradle`) worked unchanged. The project's build files were never edited to carry the engine.
3. **`test-diff snapshot` and `test-diff compare`** gave an unambiguous verdict: `41 test case(s) now, 41 in the baseline — 0 regression(s), 0 missing, 0 new failure(s)`.
4. **Step 5b (wrapper bump)** fired exactly as described: the Spring Boot plugin refused Gradle 8.10.2 with `requires Gradle 8.x (8.14 or later) or 9.x`, and taking the newest 8.x final (8.14.6) fixed it.
5. **Step 8b (context load) caught a real failure that compilation could not.** See item B1.

## What did not go as expected

### A. The skill that runs is not the skill that was fetched (highest impact)

**A1. Two versions of the procedure, one run.** The text I was given to follow was the plugin's vendored `SKILL.md` (v0.41.0). Step 2 fetches the newest tag (v0.41.2). `diff` of the two `platform-upgrade/SKILL.md` files shows the newer one adds whole steps:

- 4b: baseline snapshot of the tests before touching anything
- 5b: Gradle wrapper handling
- 8b: context-load check
- `--initializr` input for pass 1 (moves Spring Cloud, springdoc and other BOMs)
- `test-diff compare` in step 9
- `requires_starter`, new `renames`, a `Baseline` / `BOMs` / `Wrapper` / `Context` / `Not verified` report layout

I only noticed because I diffed the two files by hand. Following the vendored text literally would have skipped the baseline (so step 9 could not tell a regression from a pre-existing failure), skipped the BOM moves, and skipped the wrapper step, which would have made every `./gradlew` call after pass 1 fail.

*Recommendation:* after step 2's fetch, the skill must instruct: "read `$SRC/.claude/skills/platform-upgrade/SKILL.md` and follow that file; the vendored copy only bootstraps". Alternatively, pin the default ref of a plugin release to the ref it was synced from, so the two cannot differ. Today `.plugin-source.json` says `v0.41.0` while `git ls-remote --tags` returns `v0.41.2`, so the drift existed on day one of the run.

**A2. The compatibility guard checks only one mode.** Step 2 greps for `upgrade-recipe` only. The newer text greps for both `upgrade-recipe` and `test-diff`. A ref that has the first but not the second passes the old guard and fails later with an unknown-mode "success" (exit 0, one stderr line), which is exactly the failure the guard exists to prevent.

*Recommendation:* the guard should list every `ArchHook.jar` mode the procedure calls, or the jar should expose a `--capabilities` / `modes` listing the skill can check once.

**A3. I read stale data and, as a result, did redundant work (my slip, caused by A1).** To bootstrap step 2 I fetched `extensions.json` from the raw URL at the plugin's ref (`v0.41.0`), and the tarball at `v0.41.2`. Two refs in one run. I printed `test_starters` from the older file, which has no entry for `org.springframework.boot.jdbc.test.autoconfigure`. After pass 1 the compiler reported that package missing, and I resolved the starter by querying Maven Central by hand (`spring-boot-starter-jdbc-test`). The `v0.41.2` data already maps that package to exactly that starter, and step 8's first table row says to use it. The answer was correct, but the lookup was avoidable and the behavior "read data from a different ref than the one you execute" is a trap.

*Recommendation:* make "fetch `$SRC` first, then read every data file only from `$SRC`" the explicit rule, and say where `.plugin-source.json` lives (it is under the plugin's cache directory, not in the project). Step 2 currently says "as `arch-adopt` § 2-3 does it", which forces the reader to open a second skill to learn how to bootstrap the first.

### B. Gaps the recipes and data did not cover (hand edits)

**B1. Spring Cloud AWS was not moved, and the failure only appeared at runtime.** The build used `io.awspring.cloud:spring-cloud-aws-dependencies:3.1.0` through an `ext` variable. It is not in the Initializr BOM list, so pass 1 left it alone. Compilation passed. The context failed in step 8b with:

```
java.lang.NoSuchMethodError: 'org.springframework.boot.context.properties.PropertyMapper
  org.springframework.boot.context.properties.PropertyMapper.alwaysApplyingWhenNonNull()'
  (while creating 'defaultSqsListenerContainerFactory' from SqsAutoConfiguration)
```

Fix applied: `springCloudAwsVersion` 3.1.0 → 4.2.0, chosen as `<release>` in Central's `maven-metadata.xml`, per the step 8 table. Two caveats: (1) `4.2.0` was published 2026-09-29, after `4.1.1` (2026-08-26), and its parent `spring-cloud-build` 5.0.2 builds against Boot 4.0.7, so "newest release" is a choice, not a verified compatibility statement; only the passing test run confirms it; (2) nothing in the output told me this library was a risk until the context failed.

*Recommendation:* add a data-driven list of well-known third-party starters that are tied to a Boot line (`io.awspring.cloud`, `org.springdoc`, Spring Cloud, Testcontainers, Flyway, etc.) with the version line each needs for the target. At minimum, have step 5 print "libraries in your build that the Initializr metadata does not list and that are not managed by the BOM", so the person sees the unknown set before step 8b. This is the single most useful early-warning check missing.

**B2. Jackson 3 is entirely manual, and Jackson 2 stays on the classpath, hiding the problem.** After the bump, `com.fasterxml.jackson.databind.ObjectMapper` still compiled in `src/main`, because Jackson 2 artifacts (`jackson-databind 2.21.5`, `jackson-datatype-jsr310`, via transitive dependencies such as `jjwt 0.9.1`) remain on the classpath next to Jackson 3 (`tools.jackson` 3.1.5). The only compile error was in test code: Spring Cloud OpenFeign 5.0.3's `PageJacksonModule` is now a `tools.jackson.databind.JacksonModule`, which a Jackson 2 `ObjectMapper.registerModule` rejects (`PageJacksonModule cannot be converted to Module`).

I migrated 3 main files and 5 test files by hand:

- `com.fasterxml.jackson.databind.ObjectMapper` → `tools.jackson.databind.ObjectMapper`
- `com.fasterxml.jackson.core.type.TypeReference` → `tools.jackson.core.type.TypeReference`
- `com.fasterxml.jackson.core.JsonProcessingException` → `tools.jackson.core.JacksonException` (checked → unchecked, so one `throws` clause was removed)
- test mapper: `new ObjectMapper().findAndRegisterModules().registerModule(new JavaTimeModule())…` → `JsonMapper.builder().findAndAddModules().addModule(…).build()`; `JavaTimeModule` is no longer needed

Annotations (`com.fasterxml.jackson.annotation.*`) correctly stayed.

Hypothesis, not verified in this run: if main code had been left on the Jackson 2 `ObjectMapper`, the injected bean type would not match what Boot 4 auto-configures (a Jackson 3 `JsonMapper`), so a compile-green build could fail on startup. The compile-time silence is the hazard.

*Recommendation:* (a) add an optional recipe pass `jackson3` with the four `ChangeType` entries above (the first three are plain type renames), documented as "changes exception semantics"; (b) have step 8's table row warn that a `com.fasterxml.jackson.databind` import compiling is not evidence of a correct migration when a Jackson 2 jar is transitively present, and name the transitive source (`dependencyInsight --dependency jackson-databind`); (c) the row currently points to the external migration-guide wiki, and the guide's Jackson section is long, so a compact local table of the mappings above would save a round trip.

**B3. Spring Security 7 API removal, not covered by any rename.** `SecurityConfig` used `DefaultAuthorizationCodeTokenResponseClient` with a `RestTemplate`. The class no longer exists in `spring-security-oauth2-client` 7.1.1. Replacement used: `RestClientAuthorizationCodeTokenResponseClient` with a `RestClient` built with the project's custom message converter and `OAuth2ErrorResponseErrorHandler` as `defaultStatusHandler`. This is not a type rename (the setter changed from `setRestOperations(RestTemplate)` to `setRestClient(RestClient)`), so a `ChangeType` cannot do it. It is a good example for the step 8 table's "anything else / an API that changed shape" row.

*Recommendation:* add a documented residue entry for OAuth2 client token-response clients, with the old and new class names and the setter change, since this pattern (custom token converter for a non-standard provider such as LinkedIn) is common.

**B4. The recipe appended a stray block to `build.gradle`.** `org.openrewrite.gradle.AddDependency` for `spring-boot-starter-cache` (triggered correctly by `@EnableCaching`) did not find a top-level `dependencies {}` block because this build declares its dependencies inside `ext { … }`. It appended a new `dependencies { implementation "…" }` block at the end of the file, with double quotes and no trailing newline, unlike the surrounding single-quoted style. It works, but it is a surprise in a diff the person is supposed to review quickly. I moved it into the existing list by hand.

*Recommendation:* step 5's "read the diff for what a recipe cannot see" paragraph should include "an `AddDependency` that created a second `dependencies` block". It would also be worth a small post-check in the mode.

**B5. Step 8b's discovery command misses inheritance.** The step says to run the classes found by `grep -rl '@SpringBootTest' src`. In this project that returns only `BaseContext.java`, a base class that is not itself a test. The five real test classes `extends BaseContext`. Running `--tests '*BaseContext'` would run nothing and could read as "no context failure". I had to grep `extends BaseContext` myself.

*Recommendation:* tell the reader to follow subclasses of any class found, or have the mode (`ArchHook.jar`) list concrete test classes that inherit `@SpringBootTest`. Also: a project whose only context-loading tests are subclasses is common.

### C. Things the report cannot currently say

**C1. Changed code that no test loads is not flagged.** `SecurityConfig` is annotated `@Profile("!test")`. The test profile uses its own `SecurityConfig` in `src/test`. So the class I rewrote (OAuth2 token client) is never instantiated by any test. The build is green and the compare says 0 regressions, which is true and misleading at the same time. I listed this under "Not verified" by judgment. The same applies to `OAuth2AccessTokenResponseHttpMessageConverter`, which still extends `AbstractHttpMessageConverter` and wraps `MappingJackson2HttpMessageConverter`: it compiles, but its runtime path (the LinkedIn login) is exercised by nothing.

*Recommendation:* make "files changed in step 8 that are not loaded by any test" a required line of the report. The project already generates a JaCoCo XML report, so the mode could cross-check changed files against it when present.

**C2. Pinned overrides are left in place silently.** `build.gradle` has `set('testcontainers.version', "1.21.4")` with a comment saying it overrides the Spring Boot-managed version. The skill's `renames.dependencies` includes Testcontainers 2 coordinate renames (`org.testcontainers:postgresql` → `testcontainers-postgresql`, etc.). With the pin kept, the tests pass on Testcontainers 1.x and the renames never mattered. I did not touch the pin, and nothing told the person that it now overrides a newer managed version. The same applies to the explicit versions of `io.spring.dependency-management` (1.1.6), `org.sonarqube`, `io.gatling.gradle`, `jjwt:0.9.1` and `javax.xml.bind:jaxb-api`, none of which the Initializr metadata covers.

*Recommendation:* add a report line `Pinned ... <list of version overrides left as is>` produced by scanning for `ext`/`properties` version keys that match BOM-managed names, so the person decides, not the tool.

**C3. "Pass 2" reporting is ambiguous.** The mode printed `243 property rename(s) written`, and the run changed no files. The skill text says that when no jar declares a rename the mode writes nothing and says so, and the report template says `<n> property renames applied`. 243 were rules, 0 were applied. I reported "none declared by the jars", which is not accurate: the jars declared 243 and the project used none of them. Context load cannot reveal an ignored obsolete property (Spring ignores unknown keys), so zero applied is plausible here but unproven; `application.yml` has only a few top-level keys (`spring.datasource`, `spring.jpa`, `management`, `dev`).

*Recommendation:* have the engine step (or the mode) report `rules: N, matched: M`, and have the report template use those words.

### D. Smaller friction

- **Diff noise from the wrapper.** `gradlew`, `gradlew.bat` (git warns `CRLF will be replaced by LF`) and the binary `gradle-wrapper.jar` changed along with `gradle-wrapper.properties`. Expected after `./gradlew wrapper`, but the person should be told these four files are tool output, not review items.
- **Docker is a hidden prerequisite.** The baseline and verify runs start LocalStack through Testcontainers. If Docker is not running, the baseline either fails everything (making it useless) or the tool reports "no baseline". Step 1 checks `java -version` but not Docker; a project-agnostic hint ("tests need services that are not up?") would help.
- **Every run reinvents steps 3 and 4.** Resolving the target, reading the Maven/Gradle plugin metadata, walking back through the 12 Gradle candidates checking Central for each BOM, and the wrapper version lookup are all `curl` + text processing written ad hoc by the reader. I used `tail -r` (BSD only) for the walk-back. Step 3 and 4 are deterministic, and the skill's own rationale (decision 0175/0176) is that deterministic work belongs in `ArchHook.jar`.

  *Recommendation:* add a mode such as `ArchHook.jar resolve-platform` that prints target, engine version and wrapper version as JSON, so these steps stop depending on the reader's shell.

## Observations I could not verify

- The skill states "Pinned to `opus`". In this session the model that actually drove the run was selected by the user with `/model` before the skill started, and the git-attribution reminder named different model names at different points. I cannot tell from inside the session whether the frontmatter pin was honored. If it is a requirement, the skill should print the model it is running on in its report.
- I did not run `doctor`: the project has no `.claude/hooks/ArchHook.jar` (the step says to run it only when it exists), so the `Platform` line is not in this report.

## Prioritized recommendations for the nerviz repository

1. **(A1, A2, A3)** Make the fetched `$SRC` copy of the skill the one that is executed, or keep the plugin's default ref equal to its synced tag. Make the compatibility guard check every mode used. Read every data file only from `$SRC`.
2. **(B1)** Add an "unmanaged third-party libraries" early warning and a small data list of Boot-line-bound libraries (Spring Cloud AWS first).
3. **(B5)** Make step 8b follow inheritance to find context-loading tests.
4. **(C1, C2, C3)** Add three report lines: changed-but-unloaded files, pinned overrides left in place, and `rules/matched` for properties.
5. **(B2, B3)** Add the optional `jackson3` pass and a residue entry for OAuth2 token-response clients.
6. **(B4)** Document the "second `dependencies` block" case under step 5.
7. **(D)** Add `resolve-platform`; mention the Docker prerequisite; label wrapper files as tool output.

## Files changed by the upgrade in this repository

For reference when reproducing: `build.gradle`, `gradle/wrapper/*`, `gradlew`, `gradlew.bat`, `SecurityConfig.java`, `AssessmentChangeNotificationConsumer.java`, `AssessmentChangePublisherImpl.java`, `BaseContext.java`, and four `*FlowTest.java` files. Nothing was committed. The scratch artifacts of the run (recipes, logs, `baseline.json`, the fetched source) are under the session scratchpad and are not part of the repository.
