# 0177 · After the fetch, the fetched copy of the running skill governs — and `/platform-upgrade` names the pins, subclasses, Docker and Jackson cases a run left silent

- **Date:** 2026-10-19
- **Scenario:** Issue #163, triaged at `9a57459`: `platform-upgrade` runs the plugin's vendored
  `SKILL.md` while it executes the jar and data of the newest tag fetched into `$SRC`. Nothing in § 2
  hands over to `$SRC`'s `SKILL.md`, and the mode guard (an unknown mode exits 0) only catches a `$SRC`
  older than the text.
- **Decision:** Form 2 — the existing `.claude/skills/arch-adopt/SKILL.md` gains § 3b, and
  `.claude/skills/platform-upgrade/SKILL.md` cites it and carries the other confirmed rows. Option 1.
- **State:** approved by Lucas Fernandes, on 2026-10-19

## Reproduced on disk

From the `issue-verifier` tables of `/triage-issue 163` at `9a57459` (= `v0.41.2`), layers `static` and
`static+scratch`. Only the rows it confirmed. No commit since `9a57459` touches a cited file. The
issue's proposed fix is not an input to this record.

| # | Claim | Evidence |
|---|---|---|
| 1 | Nothing in § 2 says to follow `$SRC`'s `SKILL.md` once fetched | `platform-upgrade/SKILL.md:69-70`. The default ref is the newest tag (`arch-adopt/SKILL.md:73-76`). An installed plugin at `v0.41.0` vendors a byte-identical copy of `v0.41.0`'s file |
| 2 | The `v0.41.0` text lacks 4b, 5b, 8b, `--initializr` and `test-diff compare` | `git diff --stat v0.41.0 v0.41.2`: +148 lines in the skill, +43 in `extensions.json`, +259 in `ArchHook.java` |
| 3 | The vendored copy lags the newest tag until a person merges the marketplace sync | `release.yml:20-21`; `0175:233`; `0176:200` |
| 4 | The compatibility guard is a hand-kept grep of mode names | `SKILL.md:81`. `ArchHook.jar <unknown-mode>` → exit 0, `Unknown mode: …` (`ArchHook.java:99`) |
| 6 | § 2 sends the reader to `arch-adopt` § 2-3 to fetch | `SKILL.md:64` |
| 11 | Gradle `AddDependency` appends a second `dependencies {}` block when the build declares its dependencies inside `ext {}` | Boot 3.3.4 / Gradle 8.10.2 fixture through pass 1. A control fixture with a top-level block got the line inside it. Step 5's diff review does not name the case (`SKILL.md:183-187`) |
| 12 | Step 8b's discovery finds only an abstract `@SpringBootTest` base | the grep at `SKILL.md:274` returns only the base. `@SpringBootTest` is `@Inherited`. Maven `-Dtest='<Base>' -Dsurefire.failIfNoSpecifiedTests=false` exits 0 with no report, which is silent. Gradle `--tests '*<Base>'` exits 1 with `No tests found for given includes` |
| 14 | A transitive Jackson 2 keeps `com.fasterxml.jackson.databind` compiling, so the step 8 row never fires | the trigger gap: the row fires only on an error naming the package (`SKILL.md:262`). Whether a given library pulls Jackson 2 on 4.1.1 is unproven |
| 16 | Pinned overrides stay in place silently | after pass 1 on the fixture, `set('testcontainers.version', "1.21.4")` and `io.spring.dependency-management` `'1.1.6'` were unchanged (the Initializr's 4.1.1 starter uses `1.1.7`), and no warning was printed |
| 17 | The pass 2 count is rules, not matches; the report says "applied" | `ArchHook.java:1253-1257`: `count = renamed.size()`, then `… property rename(s) written to …`. `SKILL.md:321` says `<n> property renames applied` |
| 18 | Docker is an unstated prerequisite | step 1 checks `java -version` and the wrapper only (`SKILL.md:55-59`) |
| 19 | The wrapper files are not labelled as tool output | step 5b (`SKILL.md:204-207`) |

These rows are left out of this record:
- Refuted: the claim that the procedure made the run read data from the plugin's ref. Both refs say to read `$SRC`'s file; the cause is row 1.
- Confirmed but outside this run's scope: Spring Cloud AWS (8b covers it), the OAuth2 type (`engine.migration_guide`), and steps 3-4 written as prose.
- Not a defect: the Jackson 3 rename, which is by design (`0175:228-230`).
- Needs evidence: the `opus` pin.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | One run used two versions of the procedure: the `v0.41.0` text against the `v0.41.2` jar and data. It skipped the baseline, the BOM moves and the wrapper step that the data expected | create nothing |
| 2 — trigger | `/platform-upgrade` and `/arch-adopt`, typed by a person, in a project the plugin reached | 1, 4, 5 |
| 7 — mandatoriness | Cannot be a guarantee. A project below the floor has no `.claude/`, so it has no hook. The plugin ships only `SKILL.md` files and no hooks. The only executable that runs is `$SRC`'s jar, and it starts after the text is chosen | 7, 8 |
| 8 — destination | Both: `arch-adopt` and `platform-upgrade` travel into the project (`export.skills.include`), and the plugin vendors them | — |
| 9 — integration / owner | The fetch belongs to `arch-adopt` § 2-3. `platform-upgrade` and `new-project` cite it. The rule goes where the fetch is, so all three inherit it (maintainer's answer) | the platform-upgrade-only option |
| — on a mismatch | Follow the fetched text and say so in the report, without a question (maintainer's answer) | — |
| — unknown mode exit code | Out of this run. No observed failure came from it: the grep caught what it guards (maintainer's answer; anti-pattern 16) | the exit-1 option |
| — scope | The hand-off, plus every other confirmed row of the triage that the question listed: rows 11, 12, 14, 16, 17, 18 and 19 (maintainer's answer) | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | Hand-off in `arch-adopt` § 3b, cited by `platform-upgrade` § 2, plus the `platform-upgrade` text fixes | 9 | **Approved** |
| 2 | Hand-off in `platform-upgrade` § 2 only, plus the same text fixes | 7 | Rejected — `arch-adopt` keeps the same lag, and a second copy of the rule would follow (invariant 2) |
| 3 | The default ref becomes the plugin's synced ref | 4 | Rejected: contradicts `0054` (newest tag by default) and gives up the newest data on purpose |
| 4 | A hook that refuses a run whose text and `$SRC` differ | 2 | Rejected: nothing executes it. The target has no `.claude/` and the plugin ships no hooks |
| 5 | Unknown mode exits 1 | 5 | Rejected in the interview: no observed failure. The grep stays as long as old refs exist |
| 6 | Create nothing | 1 | Rejected: the symptom is reproduced on disk |

### Option 1 — `arch-adopt` § 3b + `platform-upgrade` (score 9)

**Motivator:** axis 9. The fetch has one owner, so the rule about what governs after it lives there.

**Shape.** Written as its own step, `arch-adopt` § 3b *The fetched copy governs*, not as a closing
paragraph of § 3: with `--source <path>` step 2 skips "this step and the next" (2 and 3), and the hand-off
has to run there too. The step:
- When `$SRC/.claude/skills/<running skill>/SKILL.md` differs from the running file (`cmp -s` against `${CLAUDE_SKILL_DIR}/SKILL.md`), read the fetched one.
- Continue in it from the first step after the one that fetched. Never re-run a step already done.
- The report's `Procedure` line says which text ran.
- No such file at that ref → the running copy goes on, and the guards that follow judge the ref as they already do.
- `--source <path>` is the same case: that path is `$SRC`.

`platform-upgrade` § 2 replaces "ships only this file" with a citation of that paragraph. `new-project`
inherits it through § 3, which it already runs. On `platform-upgrade`, every confirmed row of the
triage gets a sentence where its step lives:
- Step 1: Docker, when the build uses Testcontainers.
- Step 5: pins over a Boot-managed version, and the appended `dependencies {}` block.
- Step 5b: the wrapper files as Gradle's output.
- Step 7: what the pass-2 count is.
- Step 8: Jackson 2 that still compiles.
- Step 8b: the concrete subclasses of an abstract base, and why the base alone runs nothing.
- Report: the `Procedure`, `Pins` and `Pass 2` lines.

**Pros:**
- One owner (invariant 2).
- It covers the same shape in `arch-adopt`, whose own copy in a project or the plugin lags the same way.
- It has precedent: `new-project` already hands over to the fetched `init-project` (`0163`).
- The text, the jar and the data come from one ref again. That also holds when `--ref` is older than the text: the older text then runs against its own older jar.

**Cons:**
- It is persuasion: the model must follow the hand-off. There is no guarantee to buy here (axis 7).
- It widens what runs from the network from the jar and data to the procedure text too. That is the same trust `arch-adopt` already takes when it runs the fetched `export`.
- The vendored copies only carry the paragraph after the marketplace sync of the tag that ships it. Until then the old text runs once more.

**Points cut in the rubric:**
- 8 (trust surface): the fetched text now steers the run, half a point.
- 4 (enforcement): none cut, since no guarantee was available.
- Rounded: 9.

**CI:**
- `validate` › `schema` already covers both skills' frontmatter, class and sections.
- `templates` › `PlatformUpgradeTest` gets a case. The fixture's `@SpringBootTest` becomes an abstract base with one concrete subclass. Step 8b selects the subclass, and the test asserts that the context run left at least one JUnit report. On Maven, a run that selects the base alone exits 0 and writes nothing, and the test must fail by name on that.
- The hand-off itself is prose the model follows: nothing in CI can hold it. The marketplace's `vendored copies match their source` check holds that the copy is the tag's.

### Option 2 — `platform-upgrade` § 2 only (score 7)

The same paragraph, written in `platform-upgrade` alone.
- **Cut:** criterion 5, because `arch-adopt` keeps the same lag and would need a second copy later (invariant 2), and criterion 7, because propagation is incomplete for the shape.
- **CI:** the same as option 1.

## References

| Claim | Source |
|---|---|
| The plugin vendors only `SKILL.md` files and fetches the rest | `@.claude/decisions/0054-deterministic-export-provenance-plugin.md`; `arch-adopt/SKILL.md` § 2 case 2 |
| The newest tag is the default ref | `0054` table, row *Version* |
| A fetched-tree hand-off already exists | `@.claude/decisions/0163-create-project-from-the-plugin.md`; `new-project/SKILL.md` step 6 |
| The vendored copy lags until its tag is synced | `0175:233`; `0176:200`; `.github/workflows/release.yml:20-21` |
| A rule that must hold where no hook runs can only be persuasion | `references/decision-matrix.md` § 1 and § 2.2; `@CLAUDE.md` invariant 6 |
| Unknown mode exits 0 since the first commit, with no recorded reason and no test | `git log -S` on `ArchHook.java:99` → `55588bd`; no `.claude/.ci/*Test.java` mentions it |

## Propagation

| File | Change |
|---|---|
| `.claude/skills/arch-adopt/SKILL.md` | § 3b *The fetched copy governs*; a `Procedure` line in the report |
| `.claude/skills/platform-upgrade/SKILL.md` | § 2 cites `arch-adopt` § 3b and drops "ships only this file"; step 1 Docker; step 5 pins and the `ext {}` case; step 5b wrapper files; step 7 what the count is; step 8 Jackson 2 that still compiles; step 8b subclasses and the report check; report lines `Procedure`, `Pins`, `Pass 2`, `Wrapper` |
| `.claude/.ci/PlatformUpgradeTest.java` | the fixture's context test is an abstract base and one subclass; `contextRun` is step 8b |
| `docs/en/15-platform-upgrade.md`, `docs/pt-br/15-platform-upgrade.md` | steps 2 and 8b |
| `docs/en/07-ci-validate.md`, `docs/pt-br/07-ci-validate.md` | the `platform-upgrade` row of the templates table |

`export.skills.include` and `skill_classes` need nothing: both skills were already listed. Nothing in
`extensions.json` changed, and no mode of `ArchHook.java` did, so the jar is untouched. Not changed here:
the marketplace repository. The vendored copies carry § 3b only after the tag that ships it is synced,
which is the lag this record is about; the first run after that sync is the first that fixes itself.

Goes to the generated project: **yes** — `arch-adopt` and `platform-upgrade` are both in
`export.skills.include`, so a project updating itself reads § 3b from its own copy.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate` › `schema` | both skills keep their frontmatter, class and required sections | green on the tree |
| `templates` › `platform-upgrade › Maven and Gradle fixtures, 3.x to the Initializr's line, build green` (`PlatformUpgradeTest`) | step 8b resolves the concrete subclass of an abstract `@SpringBootTest` base, a selection of the base alone runs nothing, and a report exists for the class that ran | green locally on 2026-10-19 on Maven (3.5.16 → 4.1.1) and Gradle · red with the context selection narrowed to the base, failing by name: `maven — the context run exited 0 and wrote no report: the selection ran nothing`, and on Gradle `the @SpringBootTest class is red after the upgrade` |

Nothing tests the hand-off itself: it is prose a model follows, and no job runs a model. What holds
the vendored copy to the tag is `nerviz-ai/marketplace` › `validate.yml` › `vendored copies match their
source`. The unknown-mode exit code was left out on purpose (anti-pattern 16): no failure was observed
from it, and § 3b removes the case it guarded against, a text older than its jar.
