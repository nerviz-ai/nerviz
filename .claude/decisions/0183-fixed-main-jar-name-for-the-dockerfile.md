# 0183 · The main module's jar has a fixed name, `app.jar`, and the Dockerfile copies that

- **Date:** 2026-10-24
- **Scenario:** Issue #149, triaged at 3c38633: the Dockerfile's `COPY` jar path carries the project version and no build template pins the jar name, so a version bump breaks `docker build` unnoticed.
- **Decision:** Option 1, not a new Form: `templates/pom.main-build.xml.example` and `templates/build.gradle.main-boot.example` in `project-bootstrap`, the Dockerfile jar path fixed to `app.jar`, a `migrations` entry, and `.claude/.ci/DockerfileJarTest.java`
- **State:** approved by Lucas Fernandes (maintainer), on 2026-10-24

## Reproduced on disk

The `issue-verifier` table of `/triage-issue 149`, confirmed rows only, at `3c38633`:

| # | Claim | Evidence |
|---|---|---|
| 1 | `scaffold.md` § 4.10 fills `{{MAIN_MODULE_JAR_PATH}}` with `<artifactId>-<version>.jar` | `.claude/skills/project-bootstrap/references/scaffold.md:212-219` |
| 2 | Both Dockerfile exemplars copy the jar by that path | `templates/Dockerfile.example:16`, `templates/Dockerfile-gradle.example:17` |
| 3 | No `finalName` / `archiveFileName` anywhere under `.claude/skills`, `.claude/rules`, `.claude/blueprints` | `templates/pom.module.xml.example:42-46` has an empty `<build>`; neither Gradle exemplar sets `bootJar` |
| 4 | A version bump changes the jar name, so the `COPY` fails | Maven's default `finalName` is `${artifactId}-${version}`, `bootJar`'s is `<name>-<version>.jar`; nothing overrides either |
| 5 | Nothing generated builds the image | no image step in `templates/ci.yml.example`, `ci-gradle.yml.example`, or step 8 |

Found while designing, not in the issue: in a Maven `multi-module` layout no text of
`project-bootstrap` says where the `contains_main` module's `spring-boot-maven-plugin` goes.
`pom.parent.xml.example` does not declare it, `pom.module.xml.example:44` says "does NOT go
here", and only `.claude/.ci/BlueprintSkeletonTest.java:345` writes it — into the test's own
POMs, which its header says prove nothing about the templates.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | Confirmed by the triage, rows 1-5 | create nothing |
| 5 — nature | A build fragment and a path — boilerplate, not a norm | 4, 5 (invariant 3: it lives in `templates/`) |
| 7 — mandatoriness | A pinned name leaves nothing to drift; no hook reads a Dockerfile, and none is needed (invariant 6, mirror) | 7, 8 |
| 8 — destination | The generated project — the Dockerfile and build files are written there | — |
| Jar name | `app.jar`, Maven and Gradle alike — the same name the `COPY` already lands on | — |
| Existing projects | A `migrations` entry: `/arch-adopt` prints the note and prompt, never runs them | — |
| Plugin gap | Fixed in the same change: the `finalName` lives in the same `<build>` block | — |
| 17 — CI | A new test in `templates.yml` that runs `docker build` before and after a version bump | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | `templates/pom.main-build.xml.example` + `templates/build.gradle.main-boot.example`, steps and Dockerfile updated | 9 | **Approved** |
| 2 | Prose only in `build-maven.md`, `build-gradle.md`, `scaffold.md` | 6 | Rejected — boilerplate in a reference, and a test could only prove its own copy of the fragment |
| 3 | Glob in the Dockerfile (`COPY …/target/*.jar`) | 3 | Rejected — Gradle also writes `-plain.jar`; a `COPY` of two sources onto one file fails |
| 4 | create nothing | 1 | Rejected — the symptom is confirmed |

### Option 1 — two build exemplars for the `contains_main` module (score 9)

**Motivator:** rows 1-3 of the triage, axis 8.

**Pros:** the pin lives in an exemplar a CI test reads byte for byte (invariant 3). One
`<build>` block for the `contains_main` module — `spring-boot-maven-plugin` plus
`<finalName>app</finalName>` — applied in both layouts, which also closes the plugin gap.
Gradle: `bootJar { archiveFileName = 'app.jar' }`. The Dockerfile's source becomes
`{{MAIN_MODULE_DIR}}target/app.jar` (Gradle `build/libs/app.jar`): no version, no artifactId.

**Cons:** two new exemplars.

**Points cut in the rubric:** § 8 criterion 5 → two more files.

**CI:** new `.claude/.ci/DockerfileJarTest.java`, its own job in `.github/workflows/templates.yml`,
its paths in both trigger lists: Initializr starter, Maven and Gradle, single- and
multi-module; applies the exemplars, fills the Dockerfile, `docker build`, bumps the
version, `docker build` again. Red: an exemplar without the pin fails the second build.

### Option 2 — prose only (score 6)

Boilerplate XML and Gradle inside a reference (strains invariant 3), and a test would have to
hardcode the fragment — proving its own copy, the gap `BlueprintSkeletonTest`'s header
already names. **CI:** the same test, proving less.

## References

| Claim | Source |
|---|---|
| Versioned path, no pin, no image build | `issue-verifier` table, rows 1-5, at `3c38633` |
| Boilerplate lives in `templates/` | `@CLAUDE.md` invariant 3 |
| No hook for something a pinned name already guarantees | `@CLAUDE.md` invariant 6, mirror |
| A network-and-minutes test goes in path-filtered `templates.yml`, paths in both lists | `0099`; `.github/workflows/templates.yml:47-91` |
| A convention change that leaves old code behind is a printed migration | `migrations` block of `.claude/schemas/extensions.json` |

## Propagation

| File | Change |
|---|---|
| `.claude/skills/project-bootstrap/templates/pom.main-build.xml.example` | new: the `<build>` of the `contains_main` module, Boot plugin and `<finalName>app</finalName>` |
| `.claude/skills/project-bootstrap/templates/build.gradle.main-boot.example` | new: the Boot plugin and `bootJar { archiveFileName = 'app.jar' }` |
| `.claude/skills/project-bootstrap/templates/Dockerfile.example`, `Dockerfile-gradle.example` | comment only: the jar name is fixed by the exemplar above |
| `.claude/skills/project-bootstrap/references/scaffold.md` | § 4.10: `{{MAIN_MODULE_JAR_PATH}}` is `target/app.jar` / `build/libs/app.jar`, with the module path in multi-module |
| `.claude/skills/project-bootstrap/references/build-maven.md`, `build-gradle.md` | single-module adds the pin to the Initializr's file; multi-module gives the `contains_main` module the full exemplar |
| `.claude/schemas/extensions.json` | `migrations.entries` += `dockerfile-fixed-jar-name`, all nine blueprints |
| `.claude/.ci/DockerfileJarTest.java` | new |
| `.github/workflows/templates.yml` | job `dockerfile-jar`; the six paths in both trigger lists |
| `docs/en/07-ci-validate.md`, `docs/pt-br/07-ci-validate.md` | `dockerfile-jar` row in the `templates.yml` table |

The templates reach a generated project only through what `project-bootstrap` writes from them; the skill is a creation skill and stays in `export.skills.exclude`, so no `export` entry changes.

Goes to the generated project: **yes, as output** — step 4.10 of `project-bootstrap` writes the `Dockerfile` and the build files from these templates; existing projects get the `migrations` entry through `/arch-adopt`.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `templates · dockerfile-jar › versioned path fails after a bump, fixed name builds before and after, Maven and Gradle` | The old versioned `COPY` source builds and then breaks on a bump; the pinned name builds and survives two bumps; `scaffold.md` names `app.jar` | green on the tree (Maven and Gradle, 2026-10-24) · red with the exemplar's `<finalName>` set to `app-${project.version}`: `❌ maven: docker build failed for the fixed name (target/app.jar, version 0.0.2)` |
| `validate › schema` | the `migrations` entry (id pattern, known blueprints, fields), via `MigrationsSchemaTest` | green on the tree |

Not covered: the multi-module layout (placement of the same fragment is prose; `blueprints.yml`
builds that layout with its own POMs). Existing projects are not touched by CI — the migration
prompt is printed by `/arch-adopt`, never run.
