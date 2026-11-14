# 0185 · The wrapper's build-tool line is shown, and the generated project warns that an IDE importing through it may refuse it

- **Date:** 2026-10-24
- **Scenario:** Issue #147, triaged at 96f3be8: a generated Maven project keeps whatever Maven distribution the Initializr's wrapper pins; no step names that line, no pitfall warns an IDE's embedded Maven may refuse it, and verify covers only `./mvnw`
- **Decision:** Option 1 — edit `project-bootstrap` (`SKILL.md`, `references/verify-and-report.md`) and its three templates (`README.md.example`, `README.pt-br.md.example`, `root.CLAUDE.md.example`); no new piece
- **State:** approved by the maintainer, on 2026-10-24

## Reproduced on disk before classifying

Checked against `96f3be8` by `issue-verifier` (layer static); no commit since touches a cited file.

| Claim | On disk |
|---|---|
| The wrapper comes untouched from `starter.tgz`; `project-bootstrap` never edits it | Confirmed, `project-bootstrap/references/build-maven.md:91-93` ("don't run `mvn -N wrapper:wrapper`"); same for Gradle, `build-gradle.md:97-98` |
| Nothing in the run checks an IDE import | Confirmed, § 8 Verify of both build references runs only `./mvnw` / `./gradlew` |
| The run never names the build tool's version | Confirmed, `project-bootstrap/SKILL.md:359` — `Versions (resolved by the Initializr): Java <x> · Spring Boot <y> · <build tool>`; the README's `## Stack and versions` and the root `CLAUDE.md` header carry the same three values |
| No pitfall covers IDE import or the wrapper | Confirmed, `templates/root.CLAUDE.md.example` § Known pitfalls; nothing in `docs/*/11-pitfalls.md` |
| Versions resolve at runtime, so a new build-tool line reaches every project the day the Initializr adopts it | Confirmed, `CLAUDE.md` invariant 8 |

Not inputs: the IntelliJ failure (`unproven` in the verifier's table — it needs IntelliJ) and the
latest-Maven-line claim (`unproven`, needs Maven Central). The maintainer reproduces the IDE
failure by hand (interview, axis 1); that is an observation, not a cause, and no file this
decision writes states the cause or names a Maven version.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | The maintainer reproduces it: a generated Maven project fails to import in the IDE while `./mvnw` is green | 9 (create nothing on anticipation) |
| 2 — trigger | Generating a project (`project-bootstrap` § 8 and the Output contract); the person then opens it in an IDE | 6, 7, 9 |
| 5 — nature | A fact the person needs at the moment the import fails, and one value the run already prints | 1, 2, 3 (no new procedure) |
| 7 — mandatoriness | Not a guarantee: no hook can see an IDE import | 7, 8 |
| 8 — destination | The generated project, as output of `project-bootstrap`'s templates | — |
| 10 — cost | Minutes, once the person knows where to look | weighs against a migration for existing projects |
| — build tool | Maven and Gradle, symmetric: the Gradle wrapper is pinned the same way | — |
| 17 — CI | No job renders `root.CLAUDE.md.example` or `README.md.example`; an IDE import is not runnable in CI | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | Wrapper line in the report, README and root `CLAUDE.md` header + one pitfall in `root.CLAUDE.md.example` | 9 | **Approved** |
| 2 | Pitfall only | 7 | Rejected — the way out without the value; the person opens `maven-wrapper.properties` to learn it |
| 3 | Wrapper line only | 6 | Rejected — the value without the way out |
| 4 | create nothing | 4 | Rejected — the symptom is reproduced and nothing points at the wrapper |
| 5 | Pin `distributionUrl` to a known-good line | 3 | Rejected — writes a build-tool version from memory; ages the day the IDE catches up (invariant 8, in spirit) |

### Option 1 — line shown + pitfall (score 9)

**Motivator:** axis 1 (reproduced) and axis 5 — the person needs the line's value and the way out at the same moment.

**Pros:** the value is `{{wrapperVersion}}`, read in step 3 from the `distributionUrl` the `starter.tgz` extracted (it exists before step 6 writes the root `CLAUDE.md`, which § 8's `-v` output would not), never written. The pitfall lives in one owner, the generated project's root `CLAUDE.md` template, which is what that project reads. Covers both build tools with one sentence.

**Cons:** five files of one skill change (`SKILL.md`, `verify-and-report.md`, three templates). Existing projects get nothing: `arch-adopt` does not rewrite the root `CLAUDE.md`, and a `migrations` entry for a minutes-cost pitfall is ceremony (axis 10).

**Points cut in the rubric:** 7 (propagation) — no CI renders the edited templates.

**CI:** nothing testable — an IDE import needs the IDE, and no job renders `root.CLAUDE.md.example` or `README.md.example`. `design` › `no hardcoded Spring/Java version outside decisions/` still runs over the edited files.

### Option 2 — pitfall only (score 7)

The way out without the value: the person reads "the wrapper's line" and has to open `maven-wrapper.properties` to learn it. Cut: 1 (form fit, half the symptom), 5.

### Option 3 — wrapper line only (score 6)

The value without the way out: nothing tells the person an IDE may refuse it. Cut: 1, 4, 5.

### Option 4 — create nothing (score 4)

Defensible only because the cost is minutes; but the symptom is reproduced, and nothing in the project points at the wrapper. Cut: 1, 4, 6, 7, and the observed failure stays.

### Option 5 — pin the wrapper distribution (score 3)

Rejected. `project-bootstrap` would write a build-tool version it did not resolve, which the IDE outgrows and nobody revisits. Invariant 8 names Java and Spring Boot; the same reason holds for the build tool.

## References

| Claim | Source |
|---|---|
| Versions are resolved at runtime, never from memory | `CLAUDE.md` invariant 8 |
| The pitfall has one owner, the generated project's root `CLAUDE.md` | `CLAUDE.md` invariant 2; `CLAUDE.md` § Known pitfalls (its first bullet) |
| The wrapper is the Initializr's, untouched | `project-bootstrap/references/build-maven.md:91-93`, `build-gradle.md:97-98` |
| The README carries the same values as the report | `project-bootstrap/references/verify-and-report.md` § 8.5 (`{{outputContractBlock}}`) |
| A triaged scenario starts from the verifier's confirmed rows | `0103` |

## Propagation

| File | Change |
|---|---|
| `.claude/skills/project-bootstrap/SKILL.md` | Step 3 reads `distributionUrl` from the extracted wrapper into `{{wrapperVersion}}`; the Output contract line reads `<build tool> (wrapper <z>)` |
| `.claude/skills/project-bootstrap/references/verify-and-report.md` | `{{wrapperVersion}}` added to the README placeholders |
| `.claude/skills/project-bootstrap/templates/README.md.example`, `README.pt-br.md.example` | `Build: {{buildTool}} (wrapper {{wrapperVersion}})` |
| `.claude/skills/project-bootstrap/templates/root.CLAUDE.md.example` | The header carries the wrapper; one Known pitfalls bullet |

Goes to the generated project: **yes, as output** — `project-bootstrap` step 6 and 8.5 write these templates into every new project. `export` is untouched (the templates are creation-skill files). Existing projects get nothing, on purpose: `/arch-adopt` does not rewrite the root `CLAUDE.md`, and a `migrations` entry for a pitfall that costs minutes is ceremony.

## CI coverage

Nothing added, because nothing here is testable in CI: an IDE import needs the IDE, and no job renders `root.CLAUDE.md.example` or `README.md.example`. Still running over the edited files: `validate` › `design` › `no hardcoded Spring/Java version outside decisions/`, and `frontmatter schema`.
