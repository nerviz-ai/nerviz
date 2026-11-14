# `project-bootstrap` — build.tool: maven

Read at step 4 of `SKILL.md`, **only** when the build tool resolved in step 2 is `maven`.
Holds the Maven branch of steps 4, 4.6 and 8; the parts those steps share with Gradle are
in `scaffold.md` and `verify-and-report.md`. `templates/…` paths are relative to the skill
folder (`${CLAUDE_SKILL_DIR}`). Step 3's two Initializr calls stay in `SKILL.md`: they share
one block of prose, and splitting them would duplicate it.

## 4 · Restructure — maven

If `build.layout: single-module`, the result of step 3 already works as the structure:
just create the packages from `packages.map`, there's no per-layer POM to write. The
Initializr's `pom.xml` still needs the `<build>` blocks from
`templates/pom.parent.xml.example` — Spotless, Checkstyle, failsafe, and JaCoCo —
because none of them come from the Initializr. Copy them into that `pom.xml`, add the
`<finalName>` line of `templates/pom.main-build.xml.example` to its `<build>` (the Boot
plugin is already there; the name is why the Dockerfile's `COPY` survives a version bump),
and move on to step 4.6. Steps 4.6 through 4.8 apply to both layouts.

If `multi-module`:

1. Convert the root `pom.xml` into a parent (`<packaging>pom</packaging>` +
   `<modules>`), using `templates/pom.parent.xml.example` as the shape reference. The
   `<spotless.version>` property is there as a placeholder: resolve it on Maven
   Central, same rule as step 3, never from memory.

   ```bash
   curl -sS 'https://repo1.maven.org/maven2/com/diffplug/spotless/spotless-maven-plugin/maven-metadata.xml' \
     | grep -o '<release>[^<]*</release>'
   ```

   No network: **ask**, don't invent. A made-up number that doesn't exist in the
   repository breaks the build on the first `./mvnw`.

   The version oracle is `maven-metadata.xml` from `repo1.maven.org` — the repository
   itself. Don't use `search.maven.org`'s `solrsearch`: it's a separate index, returns
   versions that lag behind the repository (observed: Spotless 2.44.5 in the index,
   3.10.2 in the repository) and responds intermittently. The same holds for every
   version resolved in this skill.
2. Create a `pom.xml` per module from `templates/pom.module.xml.example`,
   with the dependencies that module's `depends_on` authorizes — **and only those**. The
   module with `contains_main: true` also gets the `<build>` of
   `templates/pom.main-build.xml.example` in place of the empty one: the Spring Boot plugin
   and the fixed `<finalName>` the Dockerfile copies. No other module carries either.
3. Move the `@SpringBootApplication` class to the module with `contains_main: true`.
4. Move `application.yml` into that module's `resources`. When it imports `.env`
   (`persistence-jpa`), the import becomes
   `optional:file:../.env[.properties],optional:file:.env[.properties]`: the run's working
   directory is the module, and `.env` stays at the root next to `docker-compose.yml`.

Mandatory order: parent → modules → main class → configuration → docs → CI.
A swapped order leaves the build broken halfway through generation.

## 4.6 · Checkstyle — maven

1. Resolve the versions on Maven Central, same rule as step 3:

   ```bash
   curl -sS 'https://repo1.maven.org/maven2/org/apache/maven/plugins/maven-checkstyle-plugin/maven-metadata.xml' \
     | grep -o '<release>[^<]*</release>'
   curl -sS 'https://repo1.maven.org/maven2/com/puppycrawl/tools/checkstyle/maven-metadata.xml' \
     | grep -o '<release>[^<]*</release>'
   ```

   They go into `<checkstyle.plugin.version>` and `<checkstyle.version>` in the root
   POM. They're two versions distinct on purpose: the plugin's and the tool it runs —
   without the second, the plugin runs an old Checkstyle that doesn't know half the
   checks.

   **JaCoCo's version is resolved here too, not skipped.** Unlike failsafe,
   `spring-boot-starter-parent` does **not** manage `org.jacoco:jacoco-maven-plugin`
   (confirmed empty against the parent's `dependencyManagement`) — leaving it unpinned
   resolves whatever's newest at build time, non-reproducibly:

   ```bash
   curl -sS 'https://repo1.maven.org/maven2/org/jacoco/jacoco-maven-plugin/maven-metadata.xml' \
     | grep -o '<release>[^<]*</release>'
   ```

   Goes into `<jacoco.version>` in the root POM.
2. Write `<project>/config/checkstyle/checkstyle.xml` with the shape of
   `templates/checkstyle.xml.example`. Fixed path — it's what the root POM's
   `configLocation` points to, via `${maven.multiModuleProjectDirectory}`. Don't swap
   it for a relative path: it resolves at the root and fails in every submodule.
3. Write `<project>/config/checkstyle/checkstyle-test.xml` with the shape of
   `templates/checkstyle-test.xml.example` — the `checkstyle-test` execution's
   `configLocation`, same fixed path discipline.

## 8 · Verify — maven

**build.tool: maven** — the `starter.tgz` from step 3 **already brings** `mvnw`,
`mvnw.cmd`, and `.mvn/wrapper/` — don't run `mvn -N wrapper:wrapper`. Confirm the
wrapper exists and works before anything else:

```bash
./mvnw -v                       # failure here = starter.tgz didn't extract the wrapper
./mvnw -q spotless:apply        # before any build: Initializr's files are tab-indented, and
                                # `spotless:check` at `verify` would fail on them
./mvnw -q clean test-compile    # boundaries + Checkstyle (validate phase)
./mvnw -q test                  # Initializr smoke test — only the `*Test`s
./mvnw -q checkstyle:check      # Checkstyle only, to isolate violations
./mvnw clean verify             # includes the `*IT`s via failsafe
```

In `verify`, the freshly generated project **has no `*IT` at all** — it doesn't
generate business code, so it doesn't generate integration tests. Zero ITs discovered
is the expected result here, unlike what held when this step used to generate
examples. What you're confirming is that `maven-failsafe-plugin` made it into the
POM: without it, the first `*IT` that `test-architect` designs compiles and never runs.

```bash
grep -c maven-failsafe-plugin pom.xml    # must be ≥ 1
```

With `features.observability` active, confirm the piece that registers the `Tracer` bean
made it in — the bridge and the exporter alone don't, and the gap otherwise surfaces only
at the first use case that injects it:

```bash
./mvnw -q dependency:tree | grep -E 'micrometer-tracing-bridge|opentelemetry-exporter-otlp|spring-boot-starter-opentelemetry'
# all three lines present on Spring Boot 4 — see references/dependency-catalog.md
```

