# 0204 · `archunit-installer` installs ArchUnit and the coverage gate on Gradle too, and `schema` refuses an exported file citing a skill that never travels

- **Date:** 2026-11-07
- **Scenario:** Issue #194, triaged at 715e4da: `archunit-installer` cannot install ArchUnit or the JaCoCo gate in `build.gradle`; its territory and procedure are Maven-only, and exported files still cite the non-exported `dependency-catalog.md`.
- **Decision:** Form 3 + Form 7c — option 1: Gradle path in `.claude/agents/archunit-installer.md` (two fragments in `.claude/skills/test-architect/templates/`), five citations fixed, `checkExportCitations` in `.claude/hooks/ArchHook.java`
- **State:** approved by Lucas Fernandes, on 2026-11-07

## Reproduced on disk

The confirmed rows of the `issue-verifier` table (`/triage-issue 194`, layers `static`, at 715e4da). Refuted, already-fixed and unproven rows, and the issue's proposed fix, are not inputs.

| # | Claim | Evidence |
|---|---|---|
| 1d | `archunit-installer`'s `write_allow` lists only the POMs plus two test files | `.claude/schemas/extensions.json:613-618` |
| 1e | `guard write` refuses `archunit-installer` an Edit of `build.gradle` | exit 2, "❌ agent `archunit-installer` is class `installer` — build.gradle is outside its territory." |
| 1f | The whole procedure is Maven-only | `.claude/agents/archunit-installer.md:92` (`<archunit.version>`), `:136` (`./mvnw -q test`), `:139-160` (`jacoco-maven-plugin` `check`), `:167` (`./mvnw -q clean verify`); `test-architect/SKILL.md` names no Gradle path |
| 1g | Reached from `/new-feature-implement`'s pre-flight | `.claude/skills/new-feature-implement/SKILL.md:129` |
| 2r | Exported files still cite files of a skill in `export.skills.exclude` | `rest-api-architect/templates/IdempotencyAspect.java.example:56` (`dependency-catalog.md`), `modular-monolith.yaml:195` |

Found while designing, same defect class as 2r (grep of every exported skill, agent, rule and blueprint for `<excluded-id>/(SKILL.md|references/|templates/)`):

| File | Cites |
|---|---|
| `.claude/skills/rest-api-architect/templates/IdempotencyAspect.java.example:42` | `@.claude/skills/project-bootstrap/templates/checkstyle.xml.example` |
| `.claude/skills/docker-architect/SKILL.md:59` | `project-bootstrap/SKILL.md` step 4.10 |
| `.claude/skills/sonarqube-setup/SKILL.md:84` | `project-bootstrap/references/build-maven.md` § 4.6 |

`.claude/skills/arch-adopt/SKILL.md:270` also matches, on purpose: it reads `$SRC/...`, the fetched tree, where `project-bootstrap` exists. It is already in `export.body_transforms.residue_exempt`.

Why the export never warned: its residue scan (`ArchHook.java` export, `residue_markers`) looks for `.claude/decisions/` and `.claude/blueprints/` only, and is a warning printed in the target, after the fact. A dead citation to an excluded skill reached a project three times: `commons-logging-installer.md` (fixed by 0200, which named it "same dead citation as #194"), then these five.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — Symptom | On a Gradle project the pre-flight's ArchUnit install is refused by `guard`, and the main thread wrote `build.gradle` itself | create nothing |
| 2 — Trigger | `/new-feature-implement` pre-flight › `test-architect` setup mode › agent | 1, 2, 4, 5, 6 as new pieces — the pieces exist |
| 7 — Mandatoriness | Territory: already a guarantee (`guard`). Dead citations: three observed leaks, so a check, not prose | prose-only fix for the citations |
| 8 — Destination | Both: the agent, `test-architect` and `rest-api-architect` travel; the check runs here | — |
| 9 — Integration | `mapstruct-installer` and `commons-logging-installer` already edit `build.gradle*` from Groovy fragments (0160, 0200); `project-bootstrap`'s `build.gradle.parent.example:96-101` already promises setup mode wires `jacocoTestCoverageVerification` | a new agent |
| 16 — Existing mode | `schema`'s `checkExportManifest` cross-checks the manifest, never the content of exported files | 7a alone |
| 17 — CI | User chose a new `ArchUnitWiringTest` in `templates.yml`, plus `AgentTerritoryTest` cases; and the citation check in `schema` with its own test | — |

User answers: residue → *fix the five and add a `schema` check derived from `export.skills.exclude`*; CI → *new wiring test*.

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | Gradle path in `archunit-installer` (territory + procedure + two fragments in `test-architect/templates/`) + citation fixes + `schema` check (Form 7c) | 9 | **Approved** |
| 2 | Same Gradle path, citations fixed by hand, no check | 7 | Rejected by the user — the next dead citation ships in silence, as three already did |
| 3 | `build.gradle*` in the territory only | 3 | Rejected — moves the failure: the procedure has no Gradle declaration, gate or wrapper call (verifier, proposed-fix line) |
| 4 | Create nothing; the agent refuses Gradle with a clear message | 3 | Rejected — Gradle is a generated build tool (`build.gradle.*.example`, `ci-gradle.yml.example`), and the parent template already promises the gate |

### Option 1 (score 9)

**Motivator:** axes 1 and 9 — observed refusal, and two installers already solving Gradle the same way.

**Pros:** follows the 0160/0200 shape (fragment per build tool, Kotlin DSL "same lines in its own syntax"); takes the XML out of the agent body (invariant 3); the Gradle template's promise becomes true; a whole defect class gets a gate here.

**Cons:** one more network test in `templates.yml`; a 7c extension of `schema` and a jar rebuild.

**Points cut in the rubric:** criterion 5 (maintenance: two fragments to keep in step with `rules/testing.md`'s numbers — the agent body already restated them).

**CI:** `templates.yml` › new job step `ArchUnitWiringTest.java`; `validate` › `design` › `AgentTerritoryTest`; `validate` › new step `ExportCitationSchemaTest.java`; `XmlTemplatesTest` already walks every `*.xml.example` under `.claude/skills/`.

### Option 3 (score 3)

`guard` would let the Edit through, and the agent would still write `<archunit.version>` into a file with no `<properties>` and run `./mvnw`, which does not exist.

## Design

### A · Territory — `.claude/schemas/extensions.json`

`agent_classes.installer.overrides.archunit-installer.write_allow` becomes exactly:

```json
"write_allow": [
  "pom.xml", "**/pom.xml",
  "build.gradle", "**/build.gradle",
  "build.gradle.kts", "**/build.gradle.kts",
  "src/test/java/**/ArchitectureTest.java",
  "**/src/test/java/**/ArchitectureTest.java",
  "src/test/java/**/TestcontainersConfiguration.java",
  "**/src/test/java/**/TestcontainersConfiguration.java"
]
```

Its `$comment` stays. In the class `$comment`, "The class default is the POMs, which the two Java installers both edit" becomes "The class default is the POMs; each Java installer replaces it with its own list, build.gradle and build.gradle.kts included".

### B · Fragment — `.claude/skills/test-architect/templates/archunit-build.gradle.example` (new)

```groovy
// EXEMPLAR — what `archunit-installer` merges into a Gradle build (Groovy DSL; an adopted
// Kotlin DSL build gets the same lines in its own syntax). A fragment: each block goes into
// the block of the same name that already exists, or is added beside it.
//
// Placement:
//   - `ext` — the root build.gradle, top level.
//   - `testImplementation` — the `dependencies` of the module with `contains_main: true`;
//     single-module: the root `dependencies`.
//   - `jacocoTestCoverageVerification` and the `check` wiring — beside the existing
//     `jacocoTestReport` block: inside `subprojects` on a multi-module build, top level on a
//     single-module one.
//
// Version: repo1.maven.org's `maven-metadata.xml` `<release>` for archunit-junit5, at install
// time, never from memory. ArchUnit is not in the Spring Boot BOM, so the version is explicit.
//
// Limits: `.claude/rules/testing.md` § Coverage. Never lowered to make the build pass.
// Class directories: the same set `jacocoTestReport` already filters, so Maven and Gradle
// measure the same classes. A module with no execution data skips the task (Gradle's own
// `onlyIf`), as Maven's `check` skips a module with no `jacoco.exec`.

ext {
    archunitVersion = '<!-- resolved: com/tngtech/archunit/archunit-junit5/maven-metadata.xml <release> -->'
}

dependencies {
    testImplementation "com.tngtech.archunit:archunit-junit5:${archunitVersion}"
}

jacocoTestCoverageVerification {
    dependsOn tasks.named('test')
    classDirectories.setFrom(jacocoTestReport.classDirectories)
    violationRules {
        rule {
            element = 'BUNDLE'
            limit {
                counter = 'LINE'
                value = 'COVEREDRATIO'
                minimum = 0.80
            }
            limit {
                counter = 'BRANCH'
                value = 'COVEREDRATIO'
                minimum = 0.70
            }
        }
    }
}

tasks.named('check') {
    dependsOn tasks.named('jacocoTestCoverageVerification')
}
```

### C · Fragment — `.claude/skills/test-architect/templates/archunit-pom.xml.example` (new)

The XML that today sits in `archunit-installer.md` steps 1, 2 and 8, moved out of the body (invariant 3), unchanged in content: a leading `<!-- EXEMPLAR … -->` comment with the same placement/version/limits notes as B (root POM `properties` → `<archunit.version>`; root `dependencyManagement` → `com.tngtech.archunit:archunit-junit5`, scope `test`, `${archunit.version}`; the `contains_main: true` module → the dependency without `<version>`; root `jacoco-maven-plugin` → the `check` execution in `verify`, BUNDLE, LINE 0.80, BRANCH 0.70, "don't touch the `<excludes>` already configured"). It must be well-formed XML with a single root element so `XmlTemplatesTest` parses it — wrap the four pieces in one `<archunit-fragment>` root, with a comment saying the wrapper is not copied.

### D · `.claude/agents/archunit-installer.md`

- **Why section:** "edit the POM twice, run `./mvnw` up to three times" → "edit the build file twice, run the wrapper (`./mvnw` or `./gradlew`) up to three times"; "every `./mvnw` build log" → "every build log".
- **Contract › Class:** "the POMs, `ArchitectureTest.java`, and `TestcontainersConfiguration.java`" → "the build files (`pom.xml`, `build.gradle`, `build.gradle.kts`, rooted and under `**/`), `ArchitectureTest.java`, and `TestcontainersConfiguration.java`".
- **Reads:** "root POM, module POMs" → "the root and module build files"; add both fragments `.claude/skills/test-architect/templates/archunit-pom.xml.example` and `archunit-build.gradle.example`.
- **Writes:** build-tool neutral — "the ArchUnit version and dependency (Maven: `<archunit.version>` + `dependencyManagement` in the root POM, the bare dependency in the `contains_main: true` module; Gradle: `ext.archunitVersion` in the root build, `testImplementation` in that module), the coverage gate (Maven: `jacoco-maven-plugin`'s `check` execution; Gradle: `jacocoTestCoverageVerification` wired into `check`)".
- **New step 0 — build tool.** `pom.xml` at the root → Maven; `build.gradle` or `build.gradle.kts` at the root → Gradle (Kotlin DSL: the fragment's lines in Kotlin syntax). Both or neither → stop with the new failure message below. The wrapper is `./mvnw` or `./gradlew`.
- **Steps 1, 2, 8:** keep the `curl` of step 1; "write it into …" and the placement come from the fragment of the detected tool, "placement as its header says"; the XML block of step 8 is removed and replaced by "merge the gate from the fragment". The paragraph on not lowering the numbers and not touching `<excludes>` stays, plus "(Gradle: the `classDirectories` of `jacocoTestReport`)".
- **Step 7:** `./mvnw -q test` / `./gradlew -q --console=plain test`.
- **Step 9:** `./mvnw -q clean verify` / `./gradlew -q --console=plain clean build`; read the result with `java -jar .claude/hooks/ArchHook.jar build-report .` (0203: totals and JaCoCo coverage on both layouts) instead of the raw log; on a failed gate report the real numbers.
- **Failure mode, new:**
  ```
  ❌ archunit-installer stopped — no single build tool at the project root
  Found: <pom.xml and build.gradle | neither>. One root build file decides Maven or Gradle.
  ```
- **Summary format:** `- ./mvnw test: green` → `- <./mvnw | ./gradlew> test: green`; the gate line → `- Coverage gate: wired (80% line / 70% branch) — <./mvnw clean verify | ./gradlew clean build> → <PASS <n%>/<n%> | FAIL <n%>/<n%>>`.
- **References:** add `@.claude/decisions/0204-archunit-installer-on-gradle-and-dead-citations-to-excluded-skills.md`.

### E · `.claude/skills/test-architect/SKILL.md`

Build-tool-neutral wording, no new procedure (setup mode only delegates): line 35 "(version in the POM, … JaCoCo's `check` execution)" → "(version in the build file, … the coverage gate: JaCoCo's `check` execution on Maven, `jacocoTestCoverageVerification` on Gradle)"; the same at line ~51 and in the setup-mode paragraph ("wires the JaCoCo `check` execution" → "wires the coverage gate"); `**Unfiltered Bash:**` "`./mvnw` goals" → "`./mvnw` goals or `./gradlew` tasks"; **Writes** "the ArchUnit entries in the POM, the `jacoco-maven-plugin`'s `check` execution" → "the ArchUnit entries in the build file, the coverage gate".

### F · The five dead citations — exact replacements

| File | Before | After |
|---|---|---|
| `rest-api-architect/templates/IdempotencyAspect.java.example:41-42` | "both already in\n * `@.claude/skills/project-bootstrap/templates/checkstyle.xml.example`, scoped to this" | "both already in\n * the project's `config/checkstyle/checkstyle.xml`, scoped to this" |
| same, `:55-57` | "Dependency: `spring-boot-starter-aspectj` — Boot-version rename, owner:\n * `.claude/skills/project-bootstrap/references/dependency-catalog.md` § Warning —\n * Spring Boot 4 renamed starters." | "Dependency: `spring-boot-starter-aspectj` — the Spring Boot 4 name of\n * `spring-boot-starter-aop`, a rename recorded in `doctor.platform.upgrade.renames` of\n * `.claude/schemas/extensions.json`." |
| `docker-architect/SKILL.md:56-59` | "(`persistence-jpa` → Postgres, `observability` → the OTLP collector) — see\n`project-bootstrap/SKILL.md` step 4.10. A third path" | "(`persistence-jpa` → Postgres, `observability` → the OTLP collector). A third path" |
| `sonarqube-setup/SKILL.md:83-84` | "never from memory, same rule\n`project-bootstrap/references/build-maven.md` § 4.6 applies to every plugin:" | "never from memory, the same rule every\nplugin version follows:" |
| `blueprints/modular-monolith/modular-monolith.yaml:195` | "# the actual reason for this blueprint; see project-bootstrap step 4.7 and references/dependency-catalog.md" | "# the actual reason for this blueprint; its BOM and starter come from project-bootstrap step 4.7" |

Line breaks of the "After" column re-wrap to the paragraph's width; the words are fixed. `IdempotencyAspect.java.example` is a Java comment, so its edit keeps the ` * ` prefix.

### G · Form 7c — `schema` refuses an exported file citing an excluded skill

A new method `checkExportCitations(Map<String, Object> exp, List<String> errors)`, called from `checkExportManifest` **after** its `source_marker` early return — so it runs only in this repository, never inside a generated project, where no excluded skill ever existed to cite.

- **Scope, all read from the `export` block (invariant 10):** every file under `export.skills.from/<id>/` for each id in `export.skills.include`, restricted to the `export.skills.keep` entries; `export.agents.from/<id>.md` for each id in `export.agents.include`; every file of `export.rules.from` not in `export.rules.exclude`; every file matching `export.blueprint_copy` with `{id}` as any directory name. Files in `export.body_transforms.residue_exempt` are skipped.
- **Pattern, derived per id of `export.skills.exclude`:** `(?<![A-Za-z0-9_-])<id>/(SKILL\.md|references/|templates/)` — the id quoted with `Pattern.quote`. A skill's name in prose ("`project-bootstrap` step 4.7") is not a path and passes.
- **Error, one per hit:** `  <file>:<line> cites \`<match>\` — skill \`<id>\` is in export.skills.exclude, so the path does not exist inside a generated project. Cite a file that travels, or say it in prose.`
- **Known limit, recorded:** a bare relative path with no skill id (`references/dependency-catalog.md` alone) is not matched; the one such case (`modular-monolith.yaml:195`) is fixed by hand in F.
- **Javadoc, the three sentences:** "Form 7c of `claude-code-architect-designer`, run by `schema` at sweep time (Stop, or a manual run) — motivated by axis 7: a citation to a skill `export.skills.exclude` keeps out reached generated projects three times (0200, issue #194), and the export's residue scan only warns, in the target, and never looked for these paths. The closest rejected form was fixing the citations by hand alone, which is what let the second and third through. Design: .claude/decisions/0204-archunit-installer-on-gradle-and-dead-citations-to-excluded-skills.md"

Then `java .claude/hooks/ArchHook.java build`.

## Test cases (Phase 4 step 9)

**`.claude/.ci/AgentTerritoryTest.java`** — new cases under `installer · archunit-installer`: `build.gradle` → 0, `app/build.gradle` → 0, `build.gradle.kts` → 0, `settings.gradle` → 2 (still refused). Defect that fails them: drop `build.gradle` from the override.

**`.claude/.ci/ExportCitationSchemaTest.java`** (new, shape of `ShellGlobSchemaTest`: the real jar over a temp copy of the real tree):
1. tree as shipped → exit 0.
2. `.claude/skills/sonarqube-setup/SKILL.md` gets "\nsee `project-bootstrap/references/build-maven.md`\n" → exit 2, stderr names `sonarqube-setup/SKILL.md:` and `project-bootstrap`.
3. `.claude/agents/archunit-installer.md` gets "\n`@.claude/skills/init-project/SKILL.md`\n" → exit 2, names the agent file.
4. a rule gets "\nsee `project-bootstrap` step 4\n" (prose, no path) → exit 0.
5. `.claude/skills/arch-adopt/SKILL.md` gets "\n`$SRC/.claude/skills/project-bootstrap/SKILL.md`\n" → exit 0 (residue_exempt).
6. a decision record gets the same path as case 2 → exit 0 (not exported).
Defect that fails case 2 and 3: call to `checkExportCitations` removed. Registered in `validate.yml` › `design` beside `schema blocks a shell glob zsh aborts on`, step name `schema blocks an exported file citing a skill that never travels`.

**`.claude/.ci/ArchUnitWiringTest.java`** (new, shape of `MapStructWiringTest`): fresh Initializr projects (Maven and Gradle, `web`), ArchUnit version from `maven-metadata.xml`, merge `archunit-pom.xml.example` / `archunit-build.gradle.example` as their headers place them; add one class `Calc` with two branches and an `ArchitectureTest` using `com.tngtech.archunit` (one trivial rule) plus a `CalcTest`.
1. Maven: `CalcTest` covers both branches → `./mvnw -q verify` green.
2. Maven: `CalcTest` removed → red, and the output names the JaCoCo check ("Coverage checks have not been met").
3. Gradle: same green with `./gradlew -q --console=plain build`.
4. Gradle: same red, output names `jacocoTestCoverageVerification`.
Defect that fails cases 2 and 4: the `check` execution / the `tasks.named('check')` wiring removed from the fragment (red never happens). Registered in `templates.yml` as a job step `build fragments wire ArchUnit and a coverage gate that fails, Maven and Gradle`; trigger paths, in **both** lists: `.claude/skills/test-architect/templates/archunit-*`, `.claude/agents/archunit-installer.md`, `.claude/.ci/ArchUnitWiringTest.java`.

## Verification (main thread, step 11)

```
java .claude/hooks/ArchHook.java build
java .claude/hooks/ArchHook.java schema
java .claude/.ci/AgentTerritoryTest.java
java .claude/.ci/ExportCitationSchemaTest.java
java .claude/.ci/XmlTemplatesTest.java
java .claude/.ci/ArchUnitWiringTest.java
java .claude/hooks/ArchHook.java build --verify
claude plugin validate .claude/skills
```

## References

| Claim | Source |
|---|---|
| A Gradle installer works from a Groovy fragment, Kotlin DSL in its own syntax | `.claude/skills/new-feature-implement/templates/mapstruct/mapstruct-build.gradle.example`, 0160 |
| `build.gradle*` already in two installers' territory | `extensions.json` `agent_classes.installer.overrides` |
| Each override replaces the class default whole | `extensions.json` `agent_classes.installer.$comment` |
| Gradle template promises the gate from setup mode | `project-bootstrap/templates/build.gradle.parent.example:96-101` |
| Boilerplate goes to `templates/` | `@CLAUDE.md` invariant 3 |
| Lists a mode reads live in `extensions.json` | `@CLAUDE.md` invariant 10 |
| Export residue scan markers | `extensions.json` `export.body_transforms.residue_markers` |
| Schema-over-temp-tree test shape | `.claude/.ci/ShellGlobSchemaTest.java` (0196) |
| `build-report` reads both layouts | 0203 |

## Propagation

Touched (Phase 4): `.claude/schemas/extensions.json` (archunit-installer override + installer `$comment`), `.claude/agents/archunit-installer.md` (step 0, fragments, both wrappers, `build-report`, new failure mode), `.claude/skills/test-architect/SKILL.md` (build-tool-neutral wording), new `.claude/skills/test-architect/templates/archunit-build.gradle.example` and `archunit-pom.xml.example` (one `<archunit-fragment>` root), the five citations of table F (`IdempotencyAspect.java.example` x2, `docker-architect/SKILL.md`, `sonarqube-setup/SKILL.md`, `modular-monolith.yaml`), `checkExportCitations` in `.claude/hooks/ArchHook.java` (called from `checkExportManifest` after the `source_marker` return) and the rebuilt `.claude/hooks/ArchHook.jar`, `docs/en` and `docs/pt-br` `07-ci-validate.md` (three rows and two command lines each). `@CLAUDE.md` untouched: no new mode.

Goes to the generated project: **yes** — `archunit-installer`, `test-architect` (its templates), `rest-api-architect`, `docker-architect`, `sonarqube-setup`, the active blueprint and the rebuilt jar travel through `export`; the new check is inert there (source_marker).

## CI coverage

- `AgentTerritoryTest.java`: four cases added. Green on the tree. Red with the Gradle entries dropped from the override: 3 failures by name (`archunit-installer edits the root build.gradle`, `…and a module build.gradle`, `…and a Kotlin DSL build`); `settings.gradle` stays refused. Override restored.
- `ExportCitationSchemaTest.java` (new, `validate.yml` › `design`, step `schema blocks an exported file citing a skill that never travels`): 7 cases (the six of the design, the skill-name assertion split in two) green. Red with the `checkExportCitations` call commented out and the jar rebuilt: the 3 blocking cases failed by name (skill, skill-names-project-bootstrap, agent). Call restored, jar rebuilt.
- `ArchUnitWiringTest.java` (new): registered as job `archunit-wiring` in `templates.yml` (trigger paths in both `paths` lists: `archunit-*`, `archunit-installer.md`, the test). Green run here with network (archunit-junit5 1.5.1, Maven and Gradle). Red: Maven `check` execution turned into `report` goal, and Gradle `tasks.named('check')` wiring emptied, each failed by name ("a build with an untested class passed"). Fragments restored.
- `XmlTemplatesTest` parses both new XML fragment files.
