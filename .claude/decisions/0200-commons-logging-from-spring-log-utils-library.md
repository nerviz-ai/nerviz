# 0200 · Logging and masking come from the `dev.nerviz:spring-log-utils` library, not from vendored templates

- **Date:** 2026-11-07
- **Scenario:** Issue #203, triaged at 5ac9cf8: `new-feature-implement`'s pre-flight and `commons-logging-installer` decide logging is installed with `find . -type d -iname commons -o -type d -path '*shared/logging'`, which always matches the exported `.claude/skills/new-feature-implement/templates/commons/` in every generated project, so detection never reflects `src/`; the vendored classes cannot follow a fix, while `dev.nerviz:spring-log-utils:3.0.0` (Boot 4 BOM, still declaring unused `jackson-databind:2.19.0` and `guava`) now exists as a candidate.
- **Decision:** Option 1 — `.claude/agents/commons-logging-installer.md` installs the library at the version pinned in `commons_logging` of `.claude/schemas/extensions.json`; no new piece
- **State:** approved by Lucas Fernandes, on 2026-11-07

## Reproduced on disk

Checked by `issue-verifier` at 5ac9cf8 (v0.50.1), layers static + scratch export of `modular-monolith`, plus a follow-up check of the reporter's second comment. Only confirmed rows are carried; the issue's proposed fix is not an input.

| # | Claim | Evidence |
|---|---|---|
| 1 | The installer copies sixteen exemplars (12 production, 3 tests, 1 `AutoConfiguration.imports`) | `.claude/agents/commons-logging-installer.md:55-56`; 16 files in `templates/commons/` |
| 5 | A fix to an aspect reaches no project already generated | The installer translates templates into `src/` once; `export` writes `.claude/` only |
| 6 | Pre-flight decides "not installed" from that `find` | `.claude/skills/new-feature-implement/SKILL.md:144,149-151` |
| 8 | The `find` matches `.claude/skills/new-feature-implement/templates/commons` in every project | Scratch export: fresh shell and installed state print the same two lines (`src/.../shared/logging` and the `.claude/` one); with `shared/logging` removed only the `.claude/` line. Same `find` in `commons-logging-installer.md:90`. Present since `337fc79`, not a regression |
| N1 | `dev.nerviz:spring-log-utils` 2.0.0 and 3.0.0 are on Maven Central, `<release>` 3.0.0 | `maven-metadata.xml`, lastUpdated `20261107135732` |
| N2-N3 | 3.0.0 POM declares `spring-boot-starter-aspectj` (runtime, unversioned) and imports `spring-boot-dependencies:4.1.2`; the Gradle `.module` has no platform dependency | Downloaded POM and `.module` |
| N4 | 3.0.0 still declares `com.fasterxml.jackson.core:jackson-databind:2.19.0` and `com.google.guava:guava:33.4.8-jre`; no class of the jar references either (`javap`) | Jar, bytecode major 65 |
| 3/10 | The jar ships 11 classes under `dev.nerviz.spring.log.utils.*` and an `AutoConfiguration.imports` listing the three aspects; no `LoggingOptions` | `unzip -l` |
| side | `ArchitectureTest.java.example:232-233` names `commons.logging.LogMask` / `commons.logging.MaskSensitiveData`, while the exemplars declare them in `.interfaces` / `.annotations`, so `COMMONS_INSTALLED` is always false and the masking rules always pass vacuously | `LogMask.java.example`, `MaskSensitiveData.java.example` package lines, checked in the main thread |

Library classes (3.0.0):

```
dev.nerviz.spring.log.utils.commons.LoggingCommonsMethods
dev.nerviz.spring.log.utils.properties.HttpMethodProperties
dev.nerviz.spring.log.utils.properties.GlobalProperties
dev.nerviz.spring.log.utils.features.enums.MaskedType
dev.nerviz.spring.log.utils.features.annotations.LogExecution
dev.nerviz.spring.log.utils.features.annotations.HttpMethodLogExecution
dev.nerviz.spring.log.utils.features.annotations.MaskSensitiveData
dev.nerviz.spring.log.utils.features.aspect.GlobalHttpMethodLogAspect
dev.nerviz.spring.log.utils.features.aspect.LogExecutionAspect
dev.nerviz.spring.log.utils.features.aspect.HttpMethodLogExecutionAspect
dev.nerviz.spring.log.utils.features.interfaces.LogMask
```

Unproven at triage: that the swap builds and tests green on Boot 4 (claim 4). The CI item of option 1 is what proves it.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — Symptom | Reproduced: detection matches `.claude/`; vendored classes never receive a fix | create nothing |
| 2 — Trigger | `/new-feature-implement` pre-flight (manual), then the installer agent | Form 1 |
| 7 — Mandatoriness | A pre-flight question, not a guarantee; no observed failure of a gating kind | Forms 7, 8 (anti-pattern 16) |
| 8 — Destination | Both: the pre-flight, the installer, the rule and the templates travel | — |
| 9 — Integration | `commons-logging-installer` stays the single owner of the logging install; `archunit-installer` loses its masking translation; `messaging-architect` templates import the library | — |
| Scope (maintainer) | B — replace the vendored classes with the library | option 2 |
| Library ownership (maintainer) | The maintainer owns `dev.nerviz:spring-log-utils`; the installer writes exclusions of Jackson 2 and Guava instead of waiting for an upstream release | — |
| Version source (maintainer) | Pinned in `extensions.json`. First answered "runtime only", then revised after the comparison: runtime `<release>` ships a broken or next-major release to users before any CI here sees it, while a pinned version means every project is born from a combination CI already ran; the maintainer publishes both ends, so the bump is a step of the library's release | option 3, runtime `<release>`, hybrid major-cap |
| Existing projects (maintainer) | A `migrations` entry with the swap prompt; detection still accepts vendored classes as installed | — |
| Masking architecture rules (maintainer) | Remove `sensitive_dtos_implement_log_mask` and `sensitive_fields_are_marked` from `ArchitectureTest`; § Masking candidates is verified by review | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | `commons-logging-installer` installs the library; detection by build file or installed `LogMask.java`; templates retired | 7 | **Approved** |
| 2 | Fix detection and the `ArchitectureTest` FQNs only, keep vendoring | 6 | Rejected — a fix to an aspect would still reach no generated project |
| 3 | Version read from `project-bootstrap/references/dependency-catalog.md` (the issue's proposal) | 3 | Rejected — breaks invariant 9 |

### Option 1 — library through the existing installer (score 7)

**Motivator:** axis 1 (both halves of the symptom) and axis 8 (both destinations).

**Design:**

- **Data** — a new top-level block `commons_logging` in `.claude/schemas/extensions.json`, travelling with the file (exact JSON in § Phase 4 specification). Read by the installer and the pre-flight, never by a mode. The version is pinned; a bump is a PR here, which triggers `templates.yml` through its existing `extensions.json` path. The exclusion list's `$comment` says: delete each entry once the pinned release no longer declares it.
- **Pre-flight** (`new-feature-implement/SKILL.md` step 1) — the logging gap is: no build file contains `spring-log-utils`, **and** no `LogMask.java` exists under any `src/main/java`; both searches prune `./.claude`. Either signal → installed.
- **Installer** (`commons-logging-installer.md`) — adds `coordinate:version` with the exclusions to the build file of the module `packages.map.commons.logging` names (multi-module: the `commons` module, compile scope on Maven, `api` on Gradle when the module applies `java-library`, otherwise `implementation` in that module and in each module whose blueprint entry `depends_on` it; single-module: the root build). Writes no `src/`, no `AutoConfiguration.imports` (the jar ships one). Verifies with `./mvnw -q test-compile` or `./gradlew compileJava`.
- **Territory** — `agent_classes.installer.overrides.commons-logging-installer.write_allow` becomes the build files only: `pom.xml`, `**/pom.xml`, `build.gradle`, `**/build.gradle`, `build.gradle.kts`, `**/build.gradle.kts`.
- **Templates** — the 16 files of `new-feature-implement/templates/commons/` are deleted and listed in `export.retired`.
- **Scaffold** — `project-bootstrap/references/scaffold.md` stops writing the `commons.logging` `package-info.java`; blueprints keep the `packages.map.commons.logging` key (it says where the dependency goes in a multi-module build and where vendored classes live in an older project).
- **Architecture test** — `ArchitectureTest.java.example` drops `LOG_MASK`, `MASK_SENSITIVE`, `COMMONS_INSTALLED`, `isOnClasspath`, `SENSITIVE_NAMES` and the two masking rules; `archunit-installer.md` drops step 6a.
- **Norm** — `.claude/rules/logging.md` § Masking mechanism names the library and its packages instead of "the project's cross-cutting `commons` package"; § How to verify says DTO masking is checked by review; `00-index.md`'s `logging.md` row verifier drops "masked DTO fields" from ArchUnit.
- **Messaging templates** — `KafkaConsumerAdapter.java.example` and `KafkaRetryableTopicListener.java.example` import `dev.nerviz.spring.log.utils.features.annotations.LogExecution`, condition "only if the build declares `spring-log-utils` or the project's commons package has `LogExecution`".
- **Migration** — a new `migrations` entry `commons-logging-from-library`, every blueprint: note + prompt to replace vendored classes with the dependency, rewrite imports, delete the vendored package and its tests, remove the vendored `AutoConfiguration.imports` lines, run the build.
- **Docs** — every `docs/en` / `docs/pt-br` page and `docs/diagrams/facts.json` that describes the vendored install; diagrams re-rendered by `Diagrams.java`.

**Pros:** a fix to the aspects reaches every project by a version bump; 16 templates leave this repository; detection reflects the build, never `.claude/`; the data travels, so invariant 9 holds.

**Cons:** the build no longer checks that a DTO with personal data is masked (maintainer's choice); every library release needs a bump PR here; the owner of the masking mechanism moves outside this repository; wide blast radius across docs.

**Points cut in the rubric:** criterion 4 (enforcement: the masking ArchUnit check is dropped), criterion 5 (maintenance: a bump per library release, two repositories to keep in step), criterion 7 risk (many derived docs to keep in step). 9 − 2.3 ≈ 7.

**CI:**
- `templates.yml` › `java-templates` › the step running `JavaTemplatesTest.java` — the commons part is replaced: a fresh Initializr Boot 4 project on Maven and on Gradle, the library at `commons_logging.version` with the exclusions, a fixture using `@LogExecution`, `@HttpMethodLogExecution`, `LogMask` and `@MaskSensitiveData` compiled, and a failure when `jackson-databind` 2.x or `guava` resolves on the runtime classpath. Path filter only — a bump edits `extensions.json`, already a trigger path.
- `validate` › `AgentTerritoryTest` — commons-logging-installer may write `build.gradle` and is refused `src/main/java/**/logging/**`.
- `validate` › `schema` — the `migrations` entry and the `export.retired` list.

### Option 2 — detection and FQNs only (score 6)

Fixes the `find` and the always-vacuous masking guard (the FQNs gain `.interfaces` / `.annotations`), keeps vendoring. Leaves claim 5 unaddressed: a fix to an aspect still reaches no generated project. CI: `JavaTemplatesTest` unchanged; `AgentInstructionsTest`/`schema` unchanged.

### Option 3 — version from `dependency-catalog.md` (score 3)

`project-bootstrap` is in `export.skills.exclude`, so a generated project's installer cannot read the file (invariant 9; same dead citation as #194). Capped at ≤ 4.

## References

| Claim | Source |
|---|---|
| `project-bootstrap` does not travel | `.claude/schemas/extensions.json` `export.skills.exclude`; `@CLAUDE.md` invariant 9 |
| Data block read by a skill or agent, never by a mode | `.claude/schemas/extensions.json` `doctor.platform.upgrade.engine` |
| `extensions.json` already triggers `templates.yml` | `.github/workflows/templates.yml:66` |
| A removed exported file needs a `retired` entry | `.claude/schemas/extensions.json` `export.retired` |
| A migration is printed by `arch-adopt`, never run | `.claude/schemas/extensions.json` `migrations.$comment`; `@.claude/decisions/0104-use-case-subpackage-per-aggregate.md` |
| Installer territory keyed on `agent_type` | `@.claude/decisions/0059-agent-classes-territory-schema.md` |
| Versions are never written from memory | `@CLAUDE.md` invariant 8 (Java/Boot literally; followed in spirit for the library) |
| A rule may name a library, not a skill | `@CLAUDE.md` invariant 1; `.claude/rules/lombok.md` precedent |

## Phase 4 specification

Everything the implementer needs; text not here does not exist for it.

### 1 · `extensions.json` block (top level, beside `migrations`)

```json
"commons_logging": {
  "$comment": "Single owner of where a generated project's logging and masking classes come from: the published library, never vendored templates. Read by `/new-feature-implement`'s pre-flight (detection) and by `commons-logging-installer` (install), never by a mode. `version` is pinned on purpose — every project is born from the combination `templates.yml` › `java-templates` last compiled; a bump is a PR here, and editing this file triggers that job. `exclusions` are dependencies the pinned release declares and no class of its jar references (checked with `javap` on 3.0.0): delete an entry once the pinned release no longer declares it. `vendored_file` is how a project generated before this block is still recognised as installed — the `commons-logging-from-library` migration moves it to the library. Design: .claude/decisions/0200-commons-logging-from-spring-log-utils-library.md",
  "coordinate": "dev.nerviz:spring-log-utils",
  "version": "3.0.0",
  "exclusions": ["com.fasterxml.jackson.core:jackson-databind", "com.google.guava:guava"],
  "build_token": "spring-log-utils",
  "vendored_file": "LogMask.java",
  "packages": {
    "annotations": "dev.nerviz.spring.log.utils.features.annotations",
    "interfaces": "dev.nerviz.spring.log.utils.features.interfaces",
    "enums": "dev.nerviz.spring.log.utils.features.enums"
  }
}
```

### 2 · Pre-flight, `new-feature-implement/SKILL.md` step 1

Replace the second command (line 144) with two commands, both pruning `./.claude`; quote every glob (`schema`'s shell-glob check, `0196`):

```bash
find . -path ./.claude -prune -o \( -name 'pom.xml' -o -name 'build.gradle' -o -name 'build.gradle.kts' \) -print 2>/dev/null | xargs grep -l 'spring-log-utils' 2>/dev/null | head -1
find . -path ./.claude -prune -o -path '*/src/main/java/*' -name 'LogMask.java' -print 2>/dev/null | head -1
```

The gap sentence becomes: "Second **and** third command empty → the logging library is not installed (`commons_logging` of `.claude/schemas/extensions.json`); a `LogMask.java` under `src/` is a project from before the library, already installed." The fourth command is the old third (mappers). Keep the body under the class's line limit.

### 3 · `commons-logging-installer.md`

- `description`: `Adds the dev.nerviz:spring-log-utils logging/masking library (LogExecution, HttpMethodLogExecution, MaskSensitiveData, LogMask and their aspects) to an already-generated Spring Boot project's build, at the version and with the exclusions that commons_logging of .claude/schemas/extensions.json names. Invoked by /new-feature-implement's pre-flight check — never directly by the user.`
- `## Why this is an agent (Form 3)` — same reason, new wording of the work: "edit one or more build files, run the build up to twice, iterate until green. None of it needs the conversation that triggered it — the `/new-feature-implement` run that detected the gap doesn't need the build files and a build log landing in its own context." Keep the `omitClaudeMd` paragraph.
- `## Contract`: territory sentence = the build files only (`pom.xml`, `build.gradle`, `build.gradle.kts`, rooted and under `**/`). Input precondition: none beyond a build file; the `commons` shell is no longer required. Reads: build files, the active blueprint's `packages.map.commons.logging` and `modules` (`depends_on`), `commons_logging` of `extensions.json`, `.claude/rules/logging.md`. Writes: the dependency declaration with its exclusions, no `<version>` omitted — the pinned version is written, since the library is not in the Boot BOM. Does not write: anything under `src/`, `application.yml`, `docker-compose.yml`. Keep `**Executor:** yes` and the pattern-catalog line as they are (class-wide).
- `## Procedure`: (1) build system and modules by `find . -path ./.claude -prune -o -name 'pom.xml' -print` / `build.gradle*`; (2) target module per § Design; (3) already declared → report `already installed`, stop; (4) write the dependency with each exclusion (Maven `<exclusions>`; Gradle `exclude group: …, module: …`); (5) `./mvnw -q test-compile` or `./gradlew compileJava`, up to two attempts; (6) summary. No translation of templates, no tests written.
- `## Failure mode`, `## Summary format`, `## References` updated to match; references cite `0200`.

### 4 · Territory, `agent_classes.installer.overrides.commons-logging-installer.write_allow`

```json
"write_allow": [
  "pom.xml", "**/pom.xml",
  "build.gradle", "**/build.gradle",
  "build.gradle.kts", "**/build.gradle.kts"
]
```

### 5 · Removals

- Delete the 16 files of `.claude/skills/new-feature-implement/templates/commons/` and add each path to `export.retired`.
- `project-bootstrap/references/scaffold.md` § the `commons` shell: stop writing the logging `package-info.java`; one sentence saying the logging classes come from the library through `commons-logging-installer`. `project-bootstrap/references/dependency-catalog.md:20-25,38`: the same correction (aspects come from the library, `spring-boot-starter-aspectj` arrives transitively).
- Blueprint comments that say "installed once, later, by `commons-logging-installer`" (`hexagonal.yaml:56`, `modular-monolith.yaml:115-117`, and any sibling) say instead that the library dependency goes in this module. `packages.map.commons.logging` keys stay.
- `test-architect/templates/ArchitectureTest.java.example`: remove `SENSITIVE_NAMES`, `LOG_MASK`, `MASK_SENSITIVE`, `COMMONS_INSTALLED`, `isOnClasspath`, `sensitive_dtos_implement_log_mask`, `sensitive_fields_are_marked` and every import only they use. `archunit-installer.md`: remove step 6a and any later reference to it.

### 6 · Norm, `.claude/rules/logging.md`

- § Masking mechanism, the sentence "Lives in the project's cross-cutting `commons` package (named per this project's own `CLAUDE.md`), alongside two logging annotations" → "Comes from the `dev.nerviz:spring-log-utils` library — `@MaskSensitiveData` and the `@LogExecution` / `@HttpMethodLogExecution` annotations in `dev.nerviz.spring.log.utils.features.annotations`, `LogMask` in `…features.interfaces` — alongside a global aspect". No skill or agent named (invariant 1).
- § How to verify, first paragraph → "§ Masking candidates is checked by review: a DTO in the inbound REST adapter's DTO package with a field matching the derivation implements `LogMask` and marks the field with `@MaskSensitiveData`."
- `.claude/rules/00-index.md`, the `logging.md` row's verifier → `ArchUnit (no logging API in the domain) + the domain module's forbidden imports + review (masked DTO fields)`.

### 7 · Messaging templates

`messaging-architect/templates/KafkaConsumerAdapter.java.example` and `KafkaRetryableTopicListener.java.example`: the import becomes `dev.nerviz.spring.log.utils.features.annotations.LogExecution`; the condition becomes "ONLY IF the build declares `spring-log-utils` (or, in a project from before the library, the commons package has a `LogExecution` annotation — keep that project's import)".

### 8 · Migration entry (append to `migrations.entries`, every blueprint id)

```json
{
  "id": "commons-logging-from-library",
  "blueprints": ["<every blueprint id under .claude/blueprints/, excluding custom-template>"],
  "note": "The logging and masking classes (LogExecution, HttpMethodLogExecution, MaskSensitiveData, LogMask and their aspects) now come from the dev.nerviz:spring-log-utils library at the version commons_logging of .claude/schemas/extensions.json pins, instead of being copied into the project's commons logging package. A project that installed the copies keeps working — the pre-flight still treats them as installed — but a fix to an aspect reaches it only through the library. The masking ArchUnit rules (sensitive_dtos_implement_log_mask, sensitive_fields_are_marked) were removed from the template; an ArchitectureTest that has them keeps them until you remove them.",
  "prompt": "Replace this project's copied logging classes with the dev.nerviz:spring-log-utils library. Read `commons_logging` in .claude/schemas/extensions.json for the coordinate, version and exclusions — never from memory. First list, and change nothing yet: (1) the package holding LogExecution, HttpMethodLogExecution, MaskSensitiveData, LogMask, MaskedType and the three aspects (the root CLAUDE.md names the commons logging package), with its tests; (2) every import of that package outside it, with file and line; (3) the lines of src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports that name those aspects; (4) any app.logging.* property and any use of LoggingOptions, which the library does not expose. Then, after I confirm: add the dependency with its exclusions to the build file of the module that holds that package; rewrite each import in (2) to the library's package — annotations to dev.nerviz.spring.log.utils.features.annotations, LogMask to dev.nerviz.spring.log.utils.features.interfaces, MaskedType to dev.nerviz.spring.log.utils.features.enums; delete the package in (1) and its tests, keeping its package-info.java; remove the lines in (3), deleting the file if nothing is left; report (4) without changing it. Change no other behavior, then run ./mvnw verify (Gradle: ./gradlew build)."
}
```

### 9 · CI

- `.claude/.ci/JavaTemplatesTest.java` — the commons part is replaced; `AssignedIdEntity` stays. Cases, each with the defect that makes it fail by name:
  1. Maven: fresh Initializr Boot 4 project (the request the test already makes), dependency `commons_logging.coordinate:version` read from `.claude/schemas/extensions.json` with every `exclusions` entry; fixture sources — a `record` implementing `LogMask` with a `@MaskSensitiveData` field, a `@Service` method with `@LogExecution`, a `@RestController` method with `@HttpMethodLogExecution` — compile with `./mvnw -q test-compile`. Red: set `version` to a non-existent `9.9.9` → resolution failure named.
  2. Maven: `./mvnw -q dependency:list` output contains no `jackson-databind:jar:2.` and no `guava`. Red: remove the `com.google.guava:guava` exclusion from `extensions.json` → fails naming `guava`.
  3. Gradle: same project as `type=gradle-project`, same fixture, `./gradlew -q compileJava`, and `./gradlew -q dependencies --configuration runtimeClasspath` with neither `jackson-databind:2.` nor `guava`. Red: same as 2.
- `.github/workflows/templates.yml`: drop the two `.claude/skills/new-feature-implement/templates/commons/**` path lines, add `.claude/agents/commons-logging-installer.md` to both trigger lists; rename the step to `spring-log-utils + AssignedIdEntity in a fresh Initializr project`.
- `.claude/.ci/AgentTerritoryTest.java`, commons-logging-installer cases: `src/main/java/com/acme/commons/logging/LogExecution.java` → expect **2** (refused); `build.gradle` → 0; `commons/build.gradle.kts` → 0; `commons/pom.xml` → 0. Red: put `**/src/main/java/**/logging/**` back in `write_allow` → the refusal case fails.
- `validate` › `schema`: covers the migration entry, `export.retired` and the agent's frontmatter and sections. Red: delete one `export.retired` path while its file is gone → confirm `schema` names it (if it does not, record that in § CI coverage, do not add a check).

### 10 · Docs and diagrams

Every page under `docs/en/` and `docs/pt-br/` that describes the vendored install or the masking ArchUnit rules (`00`, `01`, `02`, `03`, `07`, `08`, `09`, `10`, both languages), `docs/diagrams/facts.json`, then `java .claude/skills/nerviz-diagrams/scripts/Diagrams.java` to re-render and `… check` green. `README.md` piece counts if a count changes. `.github/ISSUE_TEMPLATE/bug_report.yml` only if it names the vendored templates.

### 11 · Verification (main thread re-runs all)

```
java .claude/hooks/ArchHook.java schema
java .claude/hooks/ArchHook.java doctor
java .claude/hooks/ArchHook.java build --verify
java .claude/.ci/AgentTerritoryTest.java
java .claude/.ci/JavaTemplatesTest.java
java .claude/skills/nerviz-diagrams/scripts/Diagrams.java check
claude plugin validate .claude/skills
grep -rn 'templates/commons' .claude docs README.md CLAUDE.md .github
```

The last `grep` returns only `export.retired` entries and decision records. No commit.

## Propagation

| File | Change |
|---|---|
| `.claude/schemas/extensions.json` | new top-level `commons_logging` block; `commons-logging-installer` `write_allow` reduced to the six build-file globs; the 16 `new-feature-implement/templates/commons/*` paths added to `export.retired`; `migrations` entry `commons-logging-from-library` (nine blueprint ids, custom excluded) |
| `.claude/skills/new-feature-implement/templates/commons/` | 16 files deleted |
| `.claude/skills/new-feature-implement/SKILL.md` | pre-flight: two detection commands (build file naming `spring-log-utils`, `LogMask.java` under `src/`, both pruning `./.claude`) and the gap sentence; contract and reference lines name the library, not the templates |
| `.claude/agents/commons-logging-installer.md` | rewritten: description, Why, Contract, Procedure, Failure mode, Summary format, References — adds the dependency with exclusions to a build file, writes nothing under `src/` |
| `.claude/agents/archunit-installer.md` | step 6a (masking rules) removed |
| `.claude/skills/test-architect/templates/ArchitectureTest.java.example` | `SENSITIVE_NAMES`, `LOG_MASK`, `MASK_SENSITIVE`, `COMMONS_INSTALLED`, `isOnClasspath`, `SENSITIVE_TYPES`, the two predicates, both masking rules, the masking header note and the imports only they used removed |
| `.claude/skills/project-bootstrap/references/scaffold.md` | no logging `package-info.java` written; one sentence naming the library |
| `.claude/skills/project-bootstrap/references/dependency-catalog.md` | `commons` paragraph and the Boot 4 starter note say the aspects come from the library and the starter arrives transitively |
| `.claude/blueprints/{hexagonal,clean-architecture-multi-module,onion,modular-monolith}/*.yaml` | comments only: the library dependency goes in this module; `packages.map.commons.logging` keys untouched |
| `.claude/rules/logging.md`, `.claude/rules/00-index.md` | § Masking mechanism names the library and its packages; § How to verify says DTO masking is checked by review; index verifier updated |
| `.claude/skills/messaging-architect/templates/KafkaConsumerAdapter.java.example`, `KafkaRetryableTopicListener.java.example` | `LogExecution` import and condition |
| `.claude/.ci/JavaTemplatesTest.java`, `.claude/.ci/AgentTerritoryTest.java`, `.github/workflows/templates.yml` | see § CI coverage |
| `docs/en` and `docs/pt-br` `00`, `01`, `03`, `07`, `14` | vendored install and masking ArchUnit rules replaced by the library; `docs/diagrams/**` re-rendered (`layers`, `new-feature-implement`, PNG re-exported) |

Goes to the generated project: **yes** — the pre-flight, the installer, the rule, the templates and the `extensions.json` block travel through `export`.

## CI coverage

| Pipeline · job › step | What it proves | Green run · red run with the defect |
|---|---|---|
| `templates.yml` › `java-templates` › `spring-log-utils + AssignedIdEntity in a fresh Initializr project` · case 1 | The pinned `dev.nerviz:spring-log-utils` with its exclusions resolves and a fixture using `@LogExecution`, `@HttpMethodLogExecution`, `LogMask` and `@MaskSensitiveData` compiles on Maven | green: `✅ 32 files compile on Maven with dev.nerviz:spring-log-utils:3.0.0.` · red (`version` set to `9.9.9`): `Could not find artifact dev.nerviz:spring-log-utils:jar:9.9.9` then `❌ case 1 · Maven test-compile with dev.nerviz:spring-log-utils:9.9.9 exited 1` |
| same step · case 2 | `./mvnw dependency:list` of a lean project holds neither `jackson-databind:jar:2.` nor `guava` (run without `-q`, which prints no dependency line, and with a check that the listing names the library) | green: `✅ Maven runtime graph holds neither jackson-databind 2.x nor guava.` · red (`guava` exclusion removed): `❌ Maven runtime graph resolves `guava` … com.google.guava:guava:jar:33.4.8-jre:runtime` |
| same step · case 3 | Same fixture on a `gradle-project`: `./gradlew -q compileJava` and the `runtimeClasspath` without `jackson-databind:2.` and `guava` (a `(c)` constraint line is not a resolution) | green: `✅ Gradle compiles the fixture, and its runtimeClasspath holds neither jackson-databind 2.x nor guava.` · red (`guava` exclusion removed): `❌ Gradle runtime graph resolves `guava` … com.google.guava:guava:33.4.8-jre` |
| `validate` › `AgentTerritoryTest` | `commons-logging-installer` is refused `src/main/java/**/logging/**`, and may write `build.gradle`, `commons/build.gradle.kts`, `commons/pom.xml` | green: `✅ Agent territory enforced: deny by default per agent_type…` · red (`**/src/main/java/**/logging/**` back in `write_allow`): `❌ commons-logging-installer is refused a logging class under src/main — expected exit 2, got 0` |
| `validate` › `schema` | The `migrations` entry, the agent frontmatter and sections, the export manifest against the files on disk | green: exit 0 · red (one new `export.retired` path deleted while its file is gone): **not caught** — `schema` checks only retired entries that still exist on disk, so a retired path missing from the list passes silently. No check added, as specified |
| `docs` › `Diagrams.java check` | Committed diagrams equal a fresh render of the data | green: `✅ 20 rendered file(s) of 5 view(s) equal a fresh render; dist/ hashes match their SVG.` · red (before re-render): `❌ 2 committed diagram file(s) do not match the data they cite` |
