# `project-bootstrap` — build.tool: gradle

Read at step 4 of `SKILL.md`, **only** when the build tool resolved in step 2 is `gradle`.
Holds the Gradle branch of steps 4, 4.6 and 8; the parts those steps share with Maven are
in `scaffold.md` and `verify-and-report.md`. `templates/…` paths are relative to the skill
folder (`${CLAUDE_SKILL_DIR}`). Step 3's two Initializr calls stay in `SKILL.md`: they share
one block of prose, and splitting them would duplicate it.

## 4 · Restructure — gradle

If `build.layout: single-module`, the result of step 3 already works as the structure:
just create the packages from `packages.map`, there's no per-module `build.gradle` to
write and no `settings.gradle` beyond its existing `rootProject.name` line. The
Initializr's `build.gradle` still needs the `checkstyle`/`spotless`/`jacoco` blocks and
the `test`/`integrationTest` task wiring from `templates/build.gradle.parent.example`
(applied directly to the one project, not inside a `subprojects {}` block — there are no
subprojects) because none of them come from the Initializr. Copy them in, add the
`bootJar` block of `templates/build.gradle.main-boot.example` (the Boot plugin is already
applied; the name is why the Dockerfile's `COPY` survives a version bump), and move on to
step 4.6. Steps 4.6 through 4.8 apply to both layouts.

If `multi-module`:

1. Write `settings.gradle` from `templates/settings.gradle.example`, with one `include`
   line per module in `modules[]`, colon-separated to match each module's folder path
   (`adapters/adapter-in-rest` → `include 'adapters:adapter-in-rest'`).
2. Convert the root `build.gradle` into the parent shape of
   `templates/build.gradle.parent.example` — the `plugins {}` block declares
   `org.springframework.boot`, `io.spring.dependency-management`, and
   `com.diffplug.spotless` with `apply false` (applied per-module instead, step 4.7's
   note on `contains_main` decides where the Boot plugin actually activates), and the
   `subprojects {}` block carries everything every module inherits — Lombok,
   Checkstyle, Spotless, JaCoCo, the `test`/`integrationTest` split. The
   `com.diffplug.spotless` version is a placeholder: resolve it the same way as step 3,
   never from memory, but against the **Gradle plugin's own artifact** — a different one
   from Maven's, don't reuse that URL:

   ```bash
   curl -sS 'https://repo1.maven.org/maven2/com/diffplug/spotless/spotless-plugin-gradle/maven-metadata.xml' \
     | grep -o '<release>[^<]*</release>'
   ```

   No network: **ask**, don't invent. A made-up number that doesn't exist in the
   repository breaks the build on the first `./gradlew`.

   Same version-oracle discipline as Maven: `maven-metadata.xml` from `repo1.maven.org`,
   never `search.maven.org`'s lagging index.
3. Create a `build.gradle` per module from `templates/build.gradle.module.example`,
   with the dependencies that module's `depends_on` authorizes — **and only those**. The
   module with `contains_main: true` additionally gets `templates/build.gradle.main-boot.example`
   — the `org.springframework.boot` plugin and the fixed `bootJar` name the Dockerfile
   copies — and declares the `spring-boot-starter-*` dependencies its features need; every
   other module stays a plain `java-library`.
4. Move the `@SpringBootApplication` class to the module with `contains_main: true`.
5. Move `application.yml` into that module's `resources`. When it imports `.env`
   (`persistence-jpa`), the import becomes
   `optional:file:../.env[.properties],optional:file:.env[.properties]`: the run's working
   directory is the module, and `.env` stays at the root next to `docker-compose.yml`.

Mandatory order: settings → parent build.gradle → module build.gradles → main class →
configuration → docs → CI. A swapped order leaves the build broken halfway through
generation.

## 4.6 · Checkstyle — gradle

1. Resolve the Checkstyle **tool** version the same way — Gradle's `checkstyle` plugin
   ships built into Gradle itself, so there's no separate "plugin version" to resolve,
   only the tool's:

   ```bash
   curl -sS 'https://repo1.maven.org/maven2/com/puppycrawl/tools/checkstyle/maven-metadata.xml' \
     | grep -o '<release>[^<]*</release>'
   ```

   Goes into `checkstyle { toolVersion = '...' }` in the root `build.gradle` (or, in
   `multi-module`, inside the `subprojects {}` block of `templates/build.gradle.parent.example`).

   **JaCoCo's version, same discipline, against its own artifact** — not the Maven
   plugin's:

   ```bash
   curl -sS 'https://repo1.maven.org/maven2/org/jacoco/org.jacoco.core/maven-metadata.xml' \
     | grep -o '<release>[^<]*</release>'
   ```

   Goes into `jacoco { toolVersion = '...' }`, same block.
2. Write `<project>/config/checkstyle/checkstyle.xml` with the shape of
   `templates/checkstyle.xml.example` — same file, same path, same content as the Maven
   case. It's what `checkstyle { configFile = rootProject.file('config/checkstyle/checkstyle.xml') }`
   points to in `templates/build.gradle.parent.example`.
3. Write `<project>/config/checkstyle/checkstyle-test.xml` with the shape of
   `templates/checkstyle-test.xml.example` — same file as the Maven case. It's what
   `checkstyleTest`'s `configFile` points to in `templates/build.gradle.parent.example`.

## 8 · Verify — gradle

**build.tool: gradle** — the `starter.tgz` from step 3 **already brings** `gradlew`,
`gradlew.bat`, and `gradle/wrapper/` — don't run `gradle wrapper`. Confirm the wrapper
exists and works before anything else:

```bash
./gradlew -v                              # failure here = starter.tgz didn't extract the wrapper
./gradlew -q --no-daemon spotlessApply    # before any build: Initializr's files are tab-indented,
                                          # and `check` runs `spotlessCheck`
./gradlew -q --no-daemon compileTestJava  # boundaries + Checkstyle (part of `check`, see below)
./gradlew -q --no-daemon test             # Initializr smoke test — only the `*Test`s
./gradlew -q --no-daemon checkstyleMain checkstyleTest  # Checkstyle only, to isolate violations
./gradlew --no-daemon build               # includes the `*IT`s via the `integrationTest` task
```

In `build`, the freshly generated project **has no `*IT` at all** — same expected
zero-ITs result as Maven, for the same reason. What you're confirming is that the
`integrationTest` task made it into `build.gradle` and is wired into `check`: without
it, the first `*IT` that `test-architect` designs compiles and never runs.

```bash
grep -c "tasks.register('integrationTest'" build.gradle    # must be ≥ 1 (root, or the subprojects block)
```

With `features.observability` active, same confirmation, Gradle's dependency report
instead of Maven's:

```bash
./gradlew -q --no-daemon dependencies | grep -E 'micrometer-tracing-bridge|opentelemetry-exporter-otlp|spring-boot-starter-opentelemetry'
# all three lines present on Spring Boot 4 — see references/dependency-catalog.md
```

