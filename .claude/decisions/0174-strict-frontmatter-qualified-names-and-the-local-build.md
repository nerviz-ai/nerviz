# 0174 · Frontmatter a strict YAML parser accepts, qualified names in the boundary match, and what the local build does not check

- **Date:** 2026-10-17
- **Scenario:** lessons-learned-022 § 9, three defects of the generated project to fix at the source: (1) `agents/mapstruct-installer.md` has frontmatter a strict YAML parser rejects, and `schema` must refuse any such frontmatter in agents, skills and rules; (2) a fully qualified name with no `import` escapes `check` and `doctor gate`; (3) `./mvnw verify` accepts a forbidden import, so "only the build is guaranteed" in `root.CLAUDE.md.example` is false, or the check moves into the local build
- **Decision:** options 1A, 2A and 3A — Form 7c on existing modes, no new mode: `strictYamlDefects()` called by `schema`'s `checkFrontmatter()`, a qualified-name match in `forbiddenImports()` shared by `check` and `doctor gate`, the `## Under another agent` sentence of `project-bootstrap/templates/root.CLAUDE.md.example` rewritten, two `migrations` entries; `agents/mapstruct-installer.md` quoted
- **State:** approved by the maintainer, on 2026-10-17

Numbered 0174, not 0168: 0168-0173 are taken on `github-copilot/compliance`, not yet merged here.

## Reproduced on disk

| # | Claim | Evidence |
|---|---|---|
| 1 | `mapstruct-installer.md`'s `description` is a plain scalar containing `: ` | Ruby 2.6 Psych over every `.claude/skills/*/SKILL.md`, `.claude/agents/*.md`, `.claude/rules/*.md`: one failure, `mapping values are not allowed in this context at line 2 column 117`; the other 63 parse |
| 2 | `schema` cannot see it | `ArchHook.java` `frontmatter()` has no YAML library and reads only the text before the first `:` of each unindented line; `checkFrontmatter()` checks names, `required` and `enum` |
| 3 | No plain scalar in the tree is silently truncated by ` #` | same sweep, 0 hits |
| 4 | `check` and `doctor gate` share one match, and it reads only `import` lines | `forbiddenImports()`: `(?m)^\s*import\s+(static\s+)?<prefix>`; called by `check()` and `doctorBoundarySweep()` |
| 5 | `@org.springframework.stereotype.Component` with no import passes `doctor gate` and `./mvnw verify` | lessons-learned-022 `evidence/G1-build.txt` |
| 6 | `./mvnw verify` refuses no forbidden import until ArchUnit is installed | `project-bootstrap` SKILL.md § Next steps: "until they do, `verify` checks neither dependency direction nor coverage"; `ArchitectureTest.java.example` is `test-architect` setup's |
| 7 | The false sentence is in three places | `root.CLAUDE.md.example:81`; `0139` line 98 (history, not rewritten); `.claude/.ci/AgentInstructionsTest.java:16` comment |

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | Observed, not anticipated: Copilot's `/agent` drops the agent; G1 shows the FQN passing every layer | create nothing (for 1 and 2) |
| 7 — mandatoriness | (1) and (2) are gated checks already (`schema`, `doctor gate`) that miss a case; (3) is a sentence that promises one | 1-6 for (1) and (2) |
| 8 — destination | Both: `schema`, `check`, `doctor gate`, the agent and the template travel | — |
| 16 — existing mode | `schema` and `forbiddenImports()` cover the checks; no new mode | 7c as a new mode |
| 17 — CI | `validate` › `frontmatter schema` runs `schema`; `BoundarySweepTest` and `BoundaryTest` exercise the match | — |

## Options evaluated

### Defect 1 — strict frontmatter

| # | Option | Score | Verdict |
|---|---|---|---|
| 1A | Quote the agent's `description`; `schema` checks every frontmatter against a strict YAML subset | 9 | **Approved** |
| 1B | Fix the file only | 4 | Rejected — the next colon ships the same defect, unseen by the only runtime the author tests in |
| 1C | `schema` requires `description` to be quoted or a block scalar | 5 | Rejected — one field and one shape of the many a colon or an indicator breaks |

**1A.** `checkFrontmatter()` gets a line scanner over the frontmatter block — no YAML library, same as `frontmatter()`. Refused, each by name and line: a plain scalar holding `: ` or ending in `:` (the observed defect), a plain scalar starting with an indicator (`` ` `` `@` `&` `*` `!` `%` `?` `,` `]` `}` `|`/`>` after text), a quote not closed on its line or with trailing text, an invalid `\` escape in a double-quoted scalar, a tab in indentation, an indentation matching no open level, a duplicate key at one level (Psych accepts duplicates; `js-yaml` and `yaml`, the Node parsers, refuse them). Continuation lines of a plain scalar are checked like its first line. A flow collection must close on its line. What it accepts is a subset of YAML: anything outside it (multi-line quoted scalars, anchors, tags) is refused as "outside what schema reads", and the tree has none. Pros: one check, the one every agent, skill and rule already passes through, in this repo and in every generated project; Claude Code unchanged. Cons: a hand-written scanner — the subset is its whole contract, so the test must name each refused shape. **CI:** a new `.claude/.ci/FrontmatterYamlTest.java` in `validate` › `hooks-cross-platform`, one fixture per refused shape and one per accepted shape the tree uses (`paths:` list, a 7b `hooks:` map, a `>-` block, a quoted value with `: `); the tree itself stays green under `validate` › `frontmatter schema`.

**1B.** Fixes today's file; the next description with a colon ships the same defect, and Claude Code, the only runtime the author tests in, never shows it. **CI:** nothing new.

**1C.** Cheaper than 1A, but covers one field and one defect shape; `argument-hint` and any other scalar can fail the same way.

### Defect 2 — fully qualified names

| # | Option | Score | Verdict |
|---|---|---|---|
| 2A | `forbiddenImports()` also matches a qualified name in code, comments and literals stripped | 8 | **Approved** |
| 2B | `jdeps` over compiled classes | 3 | Rejected — needs compiled classes, which `doctor gate` never builds (0140) |
| 2C | Change nothing; ArchUnit covers it once installed | 3 | Rejected — nothing refuses the name until ArchUnit, which the pre-flight lets be skipped |

**2A.** The single owner of the match changes, so `check` and `doctor gate` change together and cannot disagree (0140). Before the regex: drop `//` and `/* */` comments, string, char and text-block literals, and the `package` line; then a prefix preceded by no identifier character and no `.` is a hit — `@org.springframework.stereotype.Component`, `org.springframework.util.Assert.notNull(…)`, `extends jakarta.persistence…`. The violation names its kind (`import` or `qualified name`). Pros: closes the hole observed in G1 at edit time and in CI; no compilation. Cons: a lexer in Java to keep right — a `"` inside a char literal or a text block are its edge cases; a project already using an FQN where its module forbids it goes red in CI after `/arch-adopt`, so it needs a `migrations` entry. **CI:** cases added to `BoundarySweepTest` (FQN annotation and FQN call red with the kind; the same prefix in a `//` comment, a Javadoc `{@link}`, a string and a text block green; `check <path>` blocks the FQN file) — same job and step.

**2B.** Sees every real dependency, but needs compiled classes — `doctor gate` runs no compilation by design (0140) — and misses `SOURCE`-retention annotations.

**2C.** ArchUnit is installed by `/new-feature-implement`'s pre-flight, behind "Skip for this run"; under another agent, maybe never (0140). Until then nothing refuses the FQN.

### Defect 3 — the local build

| # | Option | Score | Verdict |
|---|---|---|---|
| 3A | Rewrite the sentence: CI is guaranteed, the local build is not until ArchUnit; `doctor gate` runs locally | 8 | **Approved** |
| 3B | Bind the boundary sweep into `verify` / `check` of the generated build | 5 | Rejected — a new plugin and mode in both build templates, never reaching a project already generated, duplicating ArchUnit |
| 3C | Install ArchUnit at bootstrap | 3 | Rejected — no business packages to translate at bootstrap (0030) |

**3A.** `root.CLAUDE.md.example` § Under another agent: outside Claude Code, what is guaranteed is CI — the `architectural boundaries` job (`java .claude/hooks/ArchHook.java doctor gate`, runnable locally before a push) and Checkstyle; `./mvnw verify` / `./gradlew build` refuse a forbidden dependency only once `test-architect` installed the ArchUnit test. Same correction in the `AgentInstructionsTest` comment; `0139` stays as history. A `migrations` entry carries the corrected paragraph to projects already generated, since the root `CLAUDE.md` is not re-exported. Pros: the promise matches what runs; zero build cost. Cons: the local build still lets the import through. **CI:** nothing testable as prose; the claim it makes is proven by `BoundarySweepTest` and `DoctorGateTest`.

**3B.** An `exec-maven-plugin` execution in `verify` and a Gradle task before `check`, running a narrow mode (not `doctor gate`, which also gates hook jar, schema and `.mcp.json` — a `.claude/` drift would fail the local build). Pros: the sentence becomes true. Cons: a new plugin in both build templates and a new mode; the build depends on `.claude/hooks/`; never reaches a project already generated (the build file is not re-exported, the reason 0140 rejected a `ci.yml` step); duplicates ArchUnit once installed. **CI:** `BlueprintSkeletonTest` injects a forbidden import and expects `verify` and `build` red, on Maven and Gradle.

**3C.** ArchUnit's rules are written from the business packages that exist; with none, `test-architect` setup has nothing to translate (0030).

## References

| Claim | Source |
|---|---|
| A norm and a check have one owner; `check` and `doctor gate` share the match | `@CLAUDE.md` invariant 2; `.claude/decisions/0140-boundary-sweep-in-doctor-gate.md` |
| A check about files that `extensions.json` can carry goes in `schema`, not a YAML step | `references/ci-coverage.md`; `0084` |
| The generated build file and root `CLAUDE.md` are not re-exported | `0128`; `0140` (rejected `check --all` in `ci.yml`) |
| A convention change that leaves existing projects behind is a `migrations` entry | `migrations.$comment` in `.claude/schemas/extensions.json` |
| A decision record is history, not rewritten | `.claude/decisions/README.md` |

## Propagation

| File | Change |
|---|---|
| `.claude/agents/mapstruct-installer.md` | `description` in double quotes; Psych parses it |
| `.claude/hooks/ArchHook.java` + `ArchHook.jar` | `strictYamlDefects()`, `valueDefect()`, `plainDefect()`, `cutComment()`, called from `checkFrontmatter()`; `codeOnly()` and the qualified-name match in `forbiddenImports()`; `check`'s message and the `Boundary violations` line say "depend on" / "imports or qualified names"; jar rebuilt under JDK 21 |
| `.claude/schemas/extensions.json` | `migrations` entries `boundary-qualified-names` and `root-claude-md-local-build`, every blueprint id; the `agent_instructions` comment no longer says the build refuses a forbidden import |
| `.claude/skills/project-bootstrap/templates/root.CLAUDE.md.example` | § Under another agent: "only CI is guaranteed", `doctor gate` runnable before a push, the local build refusing a forbidden dependency only once ArchUnit is installed |
| `.claude/.ci/FrontmatterYamlTest.java` | new — 16 refused shapes, 8 read shapes |
| `.claude/.ci/BoundarySweepTest.java` | qualified-name cases; the line's new wording |
| `.claude/.ci/AgentInstructionsTest.java` | header comment corrected |
| `.github/workflows/validate.yml` | step `schema refuses frontmatter a strict YAML parser refuses` in `hooks-cross-platform` |
| `docs/en/07-ci-validate.md`, `docs/pt-br/07-ci-validate.md` | node H24, a row for the new step, the `BoundarySweepTest` row, the local command list |
| `docs/en/11-pitfalls.md`, `docs/pt-br/11-pitfalls.md` | part 1 § Frontmatter: the lenient parser |
| `CLAUDE.md` | § Commands rows of `doctor gate`, `check <path>` and `schema` |

`0139` stays as written: its "only the build is guaranteed" is superseded here, not rewritten.

Goes to the generated project: **yes** — `ArchHook.java`, the jar, `extensions.json` (with both `migrations` entries) and the agent through `export`; the template on the next bootstrap. A project already generated gets the corrected sentence only through the `root-claude-md-local-build` prompt, since `export` never rewrites the root `CLAUDE.md`.

## Verified before committing

- Ruby 2.6 Psych as an oracle on every fixture of `FrontmatterYamlTest`: each refused shape fails in Psych except the duplicate key, which Psych reads and the Node parsers refuse; each read shape parses.
- The six exemplars under `.claude/skills/**/templates/*.java.example` that write a framework name qualified (`SpringBatchJob`, `MongoCollections`, `SpringDataRepository`, `ModularityTests`, `SearchAdapter`, `MongoPersistenceIT`) are adapter or test files, and each already imports from the same prefix: the match adds no red to a project built from them.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · hooks-cross-platform › schema refuses frontmatter a strict YAML parser refuses` | `FrontmatterYamlTest`: every shape refused by file and line in a rule, an agent and a skill; every shape the tree writes read | green on the tree · red, 5 cases by name, with the `: ` check disabled |
| `validate · design › frontmatter schema (extensions.json is the single owner — invariant 10)` | the tree's 64 skills, agents and rules read strictly | green · red (exit 2, `mapstruct-installer.md: line 3`) with the old agent file restored |
| `validate · hooks-cross-platform › doctor sweeps every source file for forbidden imports, with check's own match` | `BoundarySweepTest`: a qualified annotation and a qualified call after a `'"'` char literal named `qualified name`; a comment, a Javadoc link, a string, a text block and a longer name are not; `check <path>` blocks the name and not the mention | green · red, 4 cases, with the name match disabled · red, 3 cases, with the lexer keeping literals |
| `blueprints` (path-filtered on `ArchHook.java`, the jar, `extensions.json`, `project-bootstrap/**`) | every blueprint skeleton still passes `doctor gate` | runs on the PR |

The rewritten sentence is prose with nothing to test; the claim it makes — CI's `doctor gate` refuses — is what `BoundarySweepTest` and `DoctorGateTest` prove.
