# 0155 · Virtual threads on whenever the project targets JDK 21 or later

- **Date:** 2026-10-14
- **Scenario:** "onde setar a obrigacao do uso de java.virtual.threads true se a versao do java for
  21+" — the property is `spring.threads.virtual.enabled`.
- **Decision:** Form 4 — `.claude/rules/virtual-threads.md`; the two properties in
  `project-bootstrap/templates/application.yml.example`; Form 7c — the `Virtual threads` line of
  `ArchHook.java doctor`, reading `doctor.virtual_threads`, never gated; the `virtual-threads`
  migration
- **State:** approved by Lucas Fernandes, on 2026-10-14 ("aprovo 1 e mantenha o kee-alive");
  implemented on the branch `code-quality-improvement`

## What the context showed

| Concern | Today |
|---|---|
| Target JDK of a new project | `project-bootstrap` step 3 always sends `javaVersion=21` to the Initializr (§ Dependencies: JDK 21+), so every generated project qualifies; the condition only bites in a project `arch-adopt` installs into, which may target 17 |
| Who turns them on | Nobody. `application.yml.example` has no `spring.threads` key; `jobs-architect/templates/application-jobs.yml.example` carries it commented out, as an alternative to sizing the scheduler pool |
| What the repository assumes | `0150` § Out of scope and `0151` § What the context showed: "Generated projects do not enable virtual threads; Tomcat's pool is the scarce resource". `rules/inbound-admission.md` § Concurrency cap: "Sized below the request thread pool" |
| Already written for the on case | `graphql-api-architect/references/pitfalls.md` (a controller method leaves the request thread), `http-client-architect` (`RestClient` on virtual threads covers fan-out), `rules/scheduling.md` § Triggers ("a pool sized to the job count or virtual threads") |
| What `doctor` can already read | `yamlScalars` (dotted scalars of a YAML file) and `compose.app_config_globs` (which files are application config) |

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — Symptom | A standard set in advance; no failure observed | Form 7a with a gate (a guarantee against a failure nobody observed — the mirror of invariant 6) |
| 7 — Mandatoriness | A norm, the template, and a `doctor` warning that never gates | Form 8; a gated `doctor` label |
| 8 — Destination | New projects, and existing ones through a `migrations` entry | — (both: rule and template travel through `export`) |
| 9 — Scope of the norm | The property only; `inbound-admission.md` and `scheduling.md` get the minimum edit that stops them contradicting it. Pinning on JDK 21–23 and the database pool as the scarce resource stay out | A broader `concurrency.md` |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | `rules/virtual-threads.md` + template + `doctor` `Virtual threads` line (never gated) + `migrations` entry | 8 | **Approved**, `spring.main.keep-alive` included |
| 2 | The same without the `doctor` line | 6 | Rejected — nothing reports a line removed or a profile turning it off |
| 3 | The same with `Virtual threads` in `doctor.gate.labels` | 4 | Rejected in the interview — gates a project's CI on a configuration line nobody saw fail |
| 4 | A paragraph in an existing rule (`scheduling.md`, `observability.md`) | 3 | Rejected — package territories (`scheduling.md`) or unrelated topics; the norm would load where the property is never written |
| 5 | create nothing | 2 | Rejected — the repository states the opposite in two records and a rule, and nothing writes the property |

### Option 1 — `rules/virtual-threads.md` (score 8)

**Motivator:** axes 7 and 8 — a norm with a single owner, the line already in every new
project, a report where a person reads, and a path for the projects that exist.

**Pros:** one owner for the norm (invariant 2) that `inbound-admission.md` and
`scheduling.md` cite; new projects carry it from step 6 with no condition, since bootstrap pins
21; `doctor` reads the target JDK from the build file and the property from every
`application*.yml|yaml|properties`, so drift shows on `/arch-doctor` in a project and in this
repository's CI through a test; adopted projects get a prompt that checks the JDK before it
touches anything.

**Cons:** a new `doctor` line is Java (Form 7c) for a failure nobody observed — tolerated because
`doctor` runs on demand, never on an event, and never gates. Proposes `spring.main.keep-alive:
true` beside the property: one line more than "the property only", taken because Spring Boot's
own documentation recommends it whenever virtual threads are on — a job-only application
otherwise exits once its last platform thread ends. The JDK is read from the build file
(`<java.version>`, `sourceCompatibility`, `JavaLanguageVersion.of`), never from the JVM `doctor`
runs on: a project built for 17 on a JDK 21 machine is still a 17 project.

**Points cut in the rubric:** criterion 9 — a seventh-plus `doctor` line; small, runs on demand.

**CI:** new `.claude/.ci/VirtualThreadsDoctorTest.java`, `validate.yml` › `hooks-cross-platform`,
next to the other `doctor` line tests: Maven 21 with the property → ✅; Maven 21 without it → ⚠️
and `doctor gate` still exits 0; a profile file setting `false` → ⚠️ naming the file; Maven 17 →
⚪; Gradle `sourceCompatibility` 21 → read; no build file (this repository) → ⚪. Spawns
`ArchHook.jar`. The `migrations` entry: `MigrationsSchemaTest` + `schema`. The rule: `schema`
(frontmatter, `paths`, export).

### Option 2 — without the `doctor` line (score 6)

Persuasion only: rule, template, migration. Nothing reports a project that removed the line or a
profile that turns it off. Rejected in the interview's axis 7. **CI:** `schema` +
`MigrationsSchemaTest` only.

## References

| Claim | Source |
|---|---|
| The property is `spring.threads.virtual.enabled`; requires Java 21, "Java 24 or later is strongly recommended" | docs.spring.io/spring-boot/reference/features/spring-application.html § Virtual threads, read 2026-10-14 |
| Virtual threads are daemon threads; `spring.main.keep-alive: true` recommended "in all cases" | same page, same section |
| With virtual threads on, the scheduler ignores the pool keys | `jobs-architect/references/tool-decision-matrix.md`, lines 30–32 |
| Every new project targets 21 | `project-bootstrap/SKILL.md` § 3 (`javaVersion=21`) |
| A hook against a failure nobody observed is the mirror of invariant 6 | `@CLAUDE.md` invariant 6 |
| A rule's verification line names what checks it | `@.claude/rules/00-index.md` § Rules written |

## Supersedes

`0150` § Out of scope and `0151` § What the context showed say "Generated projects do not
enable virtual threads; Tomcat's pool is the scarce resource". That was true when written and is
not edited (records are history); from this record on, a project targeting JDK 21 has them on, and
the scarce resource is what the use case holds.

## Propagation

| File | Change |
|---|---|
| `.claude/rules/virtual-threads.md` | new: the norm, what changes when on, `grep` checks |
| `.claude/rules/00-index.md` | its row; `inbound-admission.md`'s row says "below the scarcest resource it holds" |
| `.claude/rules/inbound-admission.md` | intro and § Concurrency cap: sized below the scarcest resource the case holds — the request pool on platform threads, a connection pool on virtual threads |
| `.claude/rules/scheduling.md` § Triggers | the pool is a decision below JDK 21 only |
| `rest-api-architect/SKILL.md`, `graphql-api-architect/SKILL.md` | the cap's size "below the scarcest resource the case holds", no longer "below the request thread pool" |
| `rest-api-architect/templates/application-admission.yml.example` | "a waiting request holds what the cap protects" |
| `project-bootstrap/templates/application.yml.example` | `spring.threads.virtual.enabled: true`, `spring.main.keep-alive: true`, unconditional — bootstrap pins 21 |
| `jobs-architect/templates/application-jobs.yml.example`, `ScheduledJob.java.example`, `jobs-spec.md.example` | pool keys below JDK 21 only; the commented `threads.virtual` block removed — `application.yml` owns it |
| `.claude/schemas/extensions.json` | `doctor.virtual_threads`; `application*.yaml` in `export.derived_paths.derived_paths_exempt_globs`; migration `virtual-threads` |
| `.claude/hooks/ArchHook.java` + `ArchHook.jar` | `doctorVirtualThreads`, `configScalars` |
| `.claude/.ci/VirtualThreadsDoctorTest.java`, `.github/workflows/validate.yml`, `docs/*/07-ci-validate.md` | the test and its step |
| `docs/*/14-rules.md` | the rule's section (group Runtime) and a boundary question |

Goes to the generated project: **yes** — the rule, `ArchHook.java`/`.jar` and `extensions.json`
through `export`; `application.yml` through bootstrap step 6; an existing project through the
migration on its next `/arch-adopt` update.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · hooks-cross-platform › doctor warns when a JDK 21+ build lacks virtual threads, and never gates` | `VirtualThreadsDoctorTest`: ✅ Maven 21 with both, and bootstrap's exemplar; ⚠️ naming each missing key and a profile setting `false`; `.properties` both separators; Gradle `sourceCompatibility` and toolchain, a module's file; ⚪ Maven 17, no version, no build; `doctor gate` never names it | green · red with `keep-alive` removed from the exemplar (the exemplar case) · red with `min_feature` 22 (the Maven 21 case) |
| `validate · design › MigrationsSchemaTest`, `schema` | the migration's shape; the rule's frontmatter and exempt globs | green |
| `templates › JavaTemplatesTest` | the exemplars still compile in a fresh Initializr project | green |
