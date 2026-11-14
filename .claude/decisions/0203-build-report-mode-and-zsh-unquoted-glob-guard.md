# 0203 · The executor reads a build through `build-report`, whatever the build tool, and `guard bash` refuses an unquoted glob flag under zsh

- **Date:** 2026-11-07
- **Scenario:** Issue #202, triaged at 386b0aa: the executor's build-output shape is Maven-only (Gradle log, totals, coverage and the background wait unspecified), and `guard bash` does not check a command for an unquoted zsh glob.
- **State:** approved by the user (Lucas Fernandes), on 2026-11-07
- **Decision:** Option 1 — Form 7c twice: a new read-only mode `build-report` in `.claude/hooks/ArchHook.java` (invoked by the executor, never by a hook), and a second refusal inside the existing `guard bash`; the executor body rewritten to be build-tool agnostic.

## Reproduced on disk

The confirmed rows of the `issue-verifier` table, checked at `386b0aa` (no commit since touches a cited file).

| # | Fact | Evidence |
|---|---|---|
| 1 | § What enters the context is Maven-only | `.claude/agents/java-spring-boot-developer.md:292` `./mvnw -q verify > "$L"`; `:299`, `:300`, `:306` read `target/` paths; `:313` greps `^\[ERROR\]\|Caused by:` |
| 2 | Input check 4 accepts a Gradle project | `java-spring-boot-developer.md:207-208` — `build.gradle(.kts)` when the project carries `gradlew` |
| 3 | Unchanged since v0.50.1 | `git log 5ac9cf8..HEAD` on the agent: only 74d7139, one tick line |
| 4 | Nothing says how to run or wait on a build | no foreground / `timeout` / `run_in_background` instruction for a build in the body; the run that filed the issue backgrounded the build and then `sleep 100`, which the harness blocked |
| 5 | Gradle runs improvise the log filter | the only filter is Maven's (`:313`); a Gradle log has no `[ERROR]` prefix |
| 6 | The zsh quoting prose already exists | `project-bootstrap/templates/root.CLAUDE.md.example:110-111` |
| 7 | `shell_globs` is read by `schema` only, over extension files | `ArchHook.java:5817-5818`; `extensions.json` `shell_globs.scope` |
| 8 | `guard bash` lets an unquoted glob flag through | stdin `grep -rn x src --include=*.java` → exit 0, no output |
| 9 | The agent body names neither `zsh` nor `glob` | grep, no hit |
| 10 | The tokenizer already carries a `quoted` flag and skips heredoc bodies | `ArchHook.java` `record Tok(String text, boolean quoted)`, `bashSegments` |
| 11 | No duplicate | #167 (other text), 0180 item 4 (the build-file line only), #198, #201 |

Found while designing, same owner: `java-spring-boot-developer.md:223-231` (§ Discovering the layout) decides single- vs multi-module from `pom.xml` count and gives only `./mvnw` compile commands — the same Maven-only gap one section earlier. Two facts the issue's proposal got wrong (verifier): the Gradle template emits no JaCoCo CSV (`build.gradle.parent.example:120-123`, `xml.required = true` only), and the agent has no Monitor tool (`tools: Read, Write, Edit, Bash`).

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — Symptom | Measured: a Gradle run improvised three log filters and a backgrounded build; `--include=*.java` sent unquoted twice in one run, and 0196 row 5c before it | create nothing |
| 5 — Nature | A: a fixed parse of report files, the same every run, over two layouts — a program, not prose. B: a refusal before a command runs | 4, 5 for A |
| 7 — Mandatoriness | B: the agent ignored the root `CLAUDE.md` line it already had, and prose quoting regressed once (0037 → 0196) — a guarantee | 1–5 for B |
| 8 — Destination | Both: the agent, `ArchHook.java`/`.jar` and `extensions.json` travel whole; `guard bash` is registered in both settings files | — |
| 14–16 — Event, reaction, mode | B: `PreToolUse`/`Bash`, block, inside the existing `guard bash` — no new registration, no second JVM. A: no event — the executor calls it, like `test-diff` | 7a for A |
| User choice A | A2 — a `build-report` mode over the report files, not Gradle prose next to each Maven block | A1 |
| User choice B | B2 — refuse only when the session's shell is zsh, never under bash | B1, B3 |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | `build-report` mode + zsh-gated glob refusal in `guard bash` + agent body | 8 | **Approved** |
| 2 | `build-report` mode + agent body only (B3: a line in the body, no guard) | 6 | Rejected by the user's B2 — the prose already exists in root `CLAUDE.md` and was ignored |
| 3 | Gradle prose next to each Maven block (A1) + B2 | 5 | Rejected by the user's A2 — two owners of the report paths, no CSV in the Gradle template, nothing checks the Gradle forms |
| 4 | B1 — refuse on every shell | 5 | Rejected by the user — under bash it blocks a command that works |
| 5 | Create nothing | 1 | Rejected — every symptom is measured |

### Option 1 — `build-report` + `guard bash` glob refusal (score 8)

**Motivator:** axis 5 for A (the reading is deterministic and the same across runs — a model
writing it per run is what produced three filters), axis 7 for B.

**Pros:** one owner for where reports live (`doctor.platform.upgrade.test_reports`, already
Maven + Gradle, already read by `test-diff`); the executor body loses every report glob, so no
zsh quoting site is left in it; JaCoCo XML, which both templates emit; a refusal whose message
names the fix instead of zsh's bare `no matches found`; no new registration.

**Cons:** two Java changes, a new test, a jar rebuild. B's gain is small — zsh already fails the
command in one turn. B's patterns cover `--include/--exclude/--exclude-dir` only, not
`find -name *.java`. B depends on `SHELL` reaching the hook process.

**Points cut in the rubric:** criterion 9 (cost per event) — none: B runs inside the JVM
`guard bash` already pays. Criterion "size" — `ArchHook.java` grows by one mode.

**CI:** new `.claude/.ci/BuildReportTest.java` in `validate` › `hooks-cross-platform`, next to
`TestDiffTest`; new cases in `.claude/.ci/BashGuardTest.java` (same job). Cases and the defect
each catches: § Test cases below.

### Option 2 — `build-report` + body line, no guard (score 6)

Same A. B as one sentence in the executor body. Rejected: the root `CLAUDE.md` of a generated
project already carries the sentence and the executor sent the unquoted form twice anyway.

### Option 3 — Gradle prose (score 5)

A Gradle command and its report globs beside each Maven block. Rejected: the report paths gain a
second owner (prose vs `test_reports`); `jacocoTestReport.csv` does not exist in a generated
Gradle project, so coverage would need `awk` over XML; every new glob is a new zsh quoting site;
no CI proves the Gradle forms.

## Design

### A · `build-report` mode

**Command:**
`java -jar .claude/hooks/ArchHook.jar build-report <project> [--log <file>] [--newer <file>]`

**Javadoc of the mode's method (the `## Why` of Form 7c):**
> `build-report` — reads the JUnit XML and JaCoCo XML reports a build left under `<project>` and
> prints the totals, the coverage, the failed tests with their first lines, and — when no test
> failed and `--log` is given — the log lines matching `build_report.log_patterns`. Invoked by
> the `java-spring-boot-developer` agent after every test run, by hand, never by a hook. Form 7c
> because the reading is the same every run over two build tools' layouts: written as shell in
> the agent body it was Maven-only, and a Gradle run improvised three different filters (issue
> #202). The closest rejected form is Gradle prose beside each Maven block: a second owner of the
> report paths, a CSV the Gradle template never writes, and nothing in CI to prove it.
> Design: .claude/decisions/0203-build-report-mode-and-zsh-unquoted-glob-guard.md

**Inputs (data, invariant 10):**
- Test reports: `doctor.platform.upgrade.test_reports` (unchanged, single owner — the
  `$comment` there adds "also read by `build-report`").
- New block `build_report` in `.claude/schemas/extensions.json`, next to `shell_globs`:

```json
"build_report": {
  "$comment": "Read by ArchHook.java `build-report`, which the java-spring-boot-developer agent runs after every test run. Test reports come from `doctor.platform.upgrade.test_reports` (one owner of where JUnit XML lives, Maven and Gradle). `coverage_reports` are the JaCoCo XML files each build template writes: Maven's `report` goal at `verify`, Gradle's `jacocoTestReport` (`xml.required = true`; the CSV is off). LINE and BRANCH counters directly under `<report>` are summed across files. At most `max_failures` failed cases are printed, each with its first `failure_lines` lines. `log_patterns` (a regex, found per line) select the lines of `--log` printed when no report shows a failed test — a compile error, a context that did not start, a gate — at most `log_max_lines`. Maven prefixes errors `[ERROR]`; Gradle prints `* What went wrong:`, `<file>.java:<n>: error:` and `> ` continuation lines. Design: .claude/decisions/0203-build-report-mode-and-zsh-unquoted-glob-guard.md",
  "coverage_reports": ["**/target/site/jacoco/jacoco.xml", "**/build/reports/jacoco/test/jacocoTestReport.xml"],
  "max_failures": 20,
  "failure_lines": 12,
  "log_patterns": ["^\\[ERROR\\]", "Caused by:", "^\\* What went wrong:", "\\.java:\\d+: error:", "^> ", "FAILED$"],
  "log_max_lines": 60
}
```

**Behaviour:**
1. Walk `<project>` with the same file walk `testReports` uses (skip `.git`, `node_modules`) —
   share it: extract `reportFiles(root, globs)` and have `testReports` call it.
2. `--newer <file>`: only report files whose mtime is **≥** that file's mtime count; the others
   are counted as `stale` and ignored. This is what stops a report left by an earlier run (a
   resumed run, a module that failed to compile this time) from reading as this run's result.
3. JUnit XML: the factory `testReports` already uses (`disallow-doctype-decl`). For each
   `<testcase>`: `failure` or `error` child → failed (separately counted as failures / errors),
   `skipped` → skipped, else passed. Print `class#name — <message attribute>` and the first
   `failure_lines` lines of the element's text, at most `max_failures` cases, then `… and N more`.
4. JaCoCo XML carries `<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">`:
   the `disallow-doctype-decl` factory **refuses it**. Use a second factory with doctype allowed,
   `http://apache.org/xml/features/nonvalidating/load-external-dtd` = false,
   `external-general-entities` and `external-parameter-entities` = false. Sum `missed`/`covered`
   of the `LINE` and `BRANCH` `<counter>` elements that are **direct children of `<report>`**
   (nested ones are per package/class and would double count).
5. `--log <file>`, only when no counted report shows a failed test: print the lines matching any
   `log_patterns`, at most `log_max_lines`. A missing log file is a line saying so, not an error.
6. Output (stdout), exactly this shape:

```
build-report · reports <n> · coverage files <c> · stale <s>
tests <t> · failures <f> · errors <e> · skipped <k>
coverage lines <x.x>% · branches <y.y>%
failed (<shown> of <total>):
  <class>#<name> — <message>
      <line 1 … line failure_lines>
log (<shown> lines matching build_report.log_patterns):
  <line>
```
   `coverage none — no JaCoCo XML report` when no coverage file counts; `tests none — no test
   report` when no test report counts (the build stopped before the tests: the log section is
   what explains it). The `failed` section only when there is one; the `log` section only per
   point 5.
7. Exit 0 whenever it printed a report — it is a reader; the build's own exit code is the
   verdict. Exit 2 on a usage error, a project that is no directory, or an unparseable file
   (named) — through its own catch, since `main`'s top-level catch exits 0.
8. Add the mode to the header comment's mode list, the dispatch `switch`, and the usage text.

### A · Executor body (`.claude/agents/java-spring-boot-developer.md`)

**§ Discovering the layout** (lines 223-231) — replace the `find . -maxdepth 2 -name pom.xml`
line and the paragraph after it with:

````markdown
```bash
find src -type d \( -name domain -o -name application -o -name infrastructure -o -name adapter \) 2>/dev/null
ls gradlew mvnw 2>/dev/null; find . -maxdepth 2 \( -name pom.xml -o -name 'build.gradle*' \) -not -path './.claude/*'
```

Record the result as this run's **destination map** and use it in every `Write`. The build tool
is the wrapper present: `gradlew` → Gradle, otherwise Maven. More than one build file: it's
multi-module, and a layer compiles with `./mvnw -q -pl <module> test-compile` or
`./gradlew -q :<module>:compileTestJava`. Only one: single-module, `./mvnw -q test-compile` or
`./gradlew -q compileTestJava` — `-pl domain` doesn't exist there and fails with
`Could not find the selected project`.
````

**§ What enters the context** (lines 287-319) — replace from "**Tests run into a log…**" through
"…together with the answer." with:

````markdown
**Tests run into a log, never into the context.** Use this shape for every test run: Block 4's
full build, and every focused rerun. The log lives under `$TMPDIR`, not `target/` or `build/`:
both are outside this agent's territory, and `clean` deletes them.

| Build tool | Full build | Focused rerun |
|---|---|---|
| Maven (`mvnw`) | `./mvnw -q verify` | `./mvnw -q verify -Dtest='<Class>' -Dsurefire.failIfNoSpecifiedTests=false` |
| Gradle (`gradlew`) | `./gradlew -q check` | `./gradlew -q test --tests '<Class>'` (an `*IT`: `integrationTest`) |

```bash
T="${TMPDIR:-/tmp}"; : > "$T/uc-build.start"; <build command> > "$T/uc-build.log" 2>&1; echo "EXIT=$?"
java -jar .claude/hooks/ArchHook.jar build-report . --log "$T/uc-build.log" --newer "$T/uc-build.start"
```

Run it in the foreground with the Bash tool's `timeout` at `600000`. Never `run_in_background`,
never `sleep` to wait on a build: this agent has no tool that waits on a background command, and
the harness refuses the `sleep`. A build that reaches the timeout is a failed run — report it
with the `build-report` output, never relaunch it in the background.

`EXIT=0` → green; the totals and coverage the final summary reports are `build-report`'s
`tests` and `coverage` lines. `EXIT≠0` → its `failed` section names the failing tests with their
first lines; with no `failed` section, its `log` section holds the build's error lines — a
compile error, a Spring context that did not start, or the JaCoCo or Checkstyle gate.

That output is the default, not a ceiling. When it does not explain the failure, read the one
report file or the region of the log that does, with `sed -n` or `Read` and an offset. Never pipe
a build through `| tail -N`: it puts the noise into the context together with the answer.

**The shell may be zsh**, which aborts a command with `no matches found` when a glob is left
unquoted. Quote every glob: `--include='*.java'`, `-name '*.java'`. `guard` refuses an unquoted
glob flag under zsh.
````

Keep the paragraph above ("Every turn of this agent re-reads…", `0117`) and everything from
"**Each file enters the context once per group…**" on, unchanged. The `$T` variable name and
both file names are free; the territory is unchanged (`$TMPDIR` is already allowed — the
documented log case in `BashGuardTest`).

Then grep the body for any remaining `target/` report path, `jacoco.csv`, `Tests run:` or
`-reports/*.txt` and fold it into `build-report`; report each one found in the subagent report.

### B · `guard bash` unquoted-glob refusal

**Data** — in `extensions.json`, under `guard`, beside `force_push`:

```json
"unquoted_glob": {
  "$comment": "Read by ArchHook.java `guard bash` (bashUnquotedGlob), after the force-push check and before any write target: when the basename of the first non-empty variable of `shell_env` is in `shells`, a token written with no quote and no backslash escape that matches any of `shell_globs.patterns` exits 2 — zsh aborts such a command with `no matches found` before it runs, and its message names neither the flag nor the fix. Any other shell passes the word through, so nothing is refused there. `shell_globs.exempt_patterns` exempts the whole command. Heredoc bodies and quoted strings are not commands and are not read; a `sh -c` string runs in that shell, not zsh, and is not re-parsed. Not caught, by the bar 0063 set: a glob outside the flag forms `shell_globs.patterns` names (`find -name *.java`). Design: .claude/decisions/0203-build-report-mode-and-zsh-unquoted-glob-guard.md",
  "shell_env": ["SHELL"],
  "shells": ["zsh"]
}
```

`shell_globs.$comment` — replace "Read by ArchHook.java `schema`." with "Read by ArchHook.java
`schema`, over files, and by `guard bash` (`guard.unquoted_glob`), over the command a tool is
about to run."

**Java** — in `guardBash`, after the force-push block:

```java
String glob = bashUnquotedGlob(asMap(get(sch, "guard", "unquoted_glob")), asMap(get(sch, "shell_globs")), cmd);
if (glob != null) {
    String fixed = glob.replaceFirst("=(.*)$", "='$1'");
    err("guard: unquoted glob blocked — `" + glob + "`: zsh aborts the command with \"no matches found\" before it runs.");
    err("  Quote it: " + fixed + ". Rule: guard.unquoted_glob and shell_globs.patterns in " + SCHEMA_FILE);
    System.exit(2);
}
```

`bashUnquotedGlob(ug, globs, cmd)`: null when `ug` or `globs` is null, when no `shell_env`
variable is set, when the basename of the first set one is not in `shells`, or when any
`exempt_patterns` regex finds in `cmd`. Otherwise for every segment of `bashSegments(cmd)`, for
every token with `quoted() == false`, return the token text if any `patterns` regex finds in it;
null at the end. Javadoc: the three `## Why` sentences — Form 7c inside `guard bash`, axis 7 (the
root `CLAUDE.md` line was ignored twice in one run), closest rejected form a sentence in the
agent body (prose quoting already regressed, 0037 → 0196) — plus "Invoked at `PreToolUse`,
matcher `Bash`, through the registration `guard bash` already has".

**Tokenizer change** — in `bashSegments`, the backslash branch sets `quoted = true` as well:
`--include=\*.java` is a valid zsh spelling and must not be refused. A `\>` then stops reading as
a redirect operator, which is the shell's own semantics. Every existing `BashGuardTest` case must
stay green — run it before and after.

### Test cases (Phase 4 step 9) — each with the defect that must make it fail

**`.claude/.ci/BuildReportTest.java`** (new; single-file, `java` only; spawns
`java -jar .claude/hooks/ArchHook.jar build-report`; fixtures written into a temp dir):

| # | Fixture | Expected | Defect injected → must fail |
|---|---|---|---|
| 1 | Maven: `target/surefire-reports/TEST-a.xml` (3 pass, 1 skip), `target/failsafe-reports/TEST-b.xml` (2 pass), `target/site/jacoco/jacoco.xml` **with the JaCoCo DOCTYPE**, nested package counters plus report-level LINE 20/80, BRANCH 10/30 | `tests 6 · failures 0 · errors 0 · skipped 1`, `coverage lines 80.0% · branches 75.0%` | JaCoCo parsed with the `disallow-doctype-decl` factory → exit 2 / no coverage line; or nested counters summed → wrong % |
| 2 | Gradle multi-module: `m1/build/test-results/test/TEST-a.xml`, `m1/build/test-results/integrationTest/TEST-b.xml`, `m1/build/reports/jacoco/test/jacocoTestReport.xml`, `m2/…` the same | totals and coverage summed over both modules | the Gradle glob dropped from `test_reports` or `coverage_reports` → totals or coverage short |
| 3 | 25 failing cases, one `error` | `failures 24 · errors 1`, `failed (20 of 25)`, `… and 5 more`, each with message and at most 12 lines | `max_failures` or `failure_lines` ignored |
| 4 | A report older than the `--newer` file plus a fresh one | only the fresh one counted, `stale 1` | no mtime filter → the stale report counted |
| 5 | No test report; a log with `[ERROR] x`, `* What went wrong:`, `Foo.java:3: error: y`, and 200 noise lines | `tests none — no test report`, the three lines in `log`, no noise | a `log_patterns` entry missing; or the log printed whole |
| 6 | Test reports with a failure and `--log` given | `failed` section, no `log` section | log printed despite a failed test |
| 7 | No coverage file | `coverage none — no JaCoCo XML report`, exit 0 | exit ≠ 0 or no coverage line |
| 8 | A malformed XML report | exit 2, the file named on stderr | the top-level catch's exit 0 |

**`.claude/.ci/BashGuardTest.java`** (new cases; every new case sets `SHELL` on the spawned
process explicitly, and existing cases keep what they inherit):

| # | `SHELL` | Command | Expected | Defect → must fail |
|---|---|---|---|---|
| G1 | `/bin/zsh` | `grep -rn x src --include=*.java` | exit 2, stderr names `--include='*.java'` | check absent → exit 0 |
| G2 | `/bin/zsh` | `grep -rn x src --include='*.java'` | exit 0 | quoting ignored → exit 2 |
| G3 | `/bin/zsh` | `grep -rn x src --include=\*.java` | exit 0 | backslash not marking `quoted` → exit 2 |
| G4 | `/bin/bash` | `grep -rn x src --include=*.java` | exit 0 | no shell gate → exit 2 |
| G5 | `/usr/local/bin/zsh` | `ls && grep -r x . --exclude-dir=*tmp*` | exit 2 | basename not taken, or second segment unread |
| G6 | `/bin/zsh` | `cat > "$TMPDIR/x.md" <<'EOF'` / `--include=*.java` / `EOF` | exit 0 | heredoc body read |
| G7 | `/bin/zsh` | `git commit -m "use --include=*.java"` | exit 0 | quoted string read |
| G8 | unset | `grep -r x . --include=*.java` | exit 0 | null `SHELL` throws, or is treated as zsh |

Run each green, then once with its defect injected (edit the jar's input, not the test), and
see it fail by name, before reporting.

## References

| Claim | Source |
|---|---|
| A mode the agent calls, not a hook, has precedent | `test-diff` (`ArchHook.java` 1397-1416, `0176`) |
| Report globs already cover both tools | `extensions.json` `doctor.platform.upgrade.test_reports` |
| `testReports` refuses a DOCTYPE | `ArchHook.java` `testReports`, `disallow-doctype-decl` |
| Gradle template writes JaCoCo XML only | `project-bootstrap/templates/build.gradle.parent.example:120-123` |
| Maven writes `jacoco.xml` at `verify` | `pom.parent.xml.example` `report` execution, phase `verify` |
| `./gradlew check` runs `test`, `integrationTest`, checkstyle and the coverage gate | `build.gradle.parent.example:106-148`; gate wired into `check` by `test-architect` |
| The agent has no waiting tool | `java-spring-boot-developer.md:5` `tools: Read, Write, Edit, Bash` |
| A second refusal in `guard bash` has precedent | `bashForcePush`, `0076` |
| The Bash tool runs the user's shell; zsh aborts an unmatched glob, bash passes it | `0196` row 5c; `shell_globs.$comment` |
| A guarantee only for an observed failure | `@CLAUDE.md` invariant 6 (mirror) — observed: issue #202, `0196` row 5c |
| Lists are data | `@CLAUDE.md` invariant 10 |
| 0196 never weighed a check on the commands run | `0196` § Options evaluated |

## Out of scope, recorded

- Gradle coverage covers only `test`: `jacocoTestReport` depends on `test` alone
  (`build.gradle.parent.example:117-124`), while Maven's agent also instruments the `*IT`s run
  by Failsafe. The same project reports different coverage per build tool. A separate decision;
  `build-report` reads whatever the template writes.
- `schema`'s line regex flags `--include=\*.java`, which zsh accepts; harmless (it asks for a
  quote) and left as is.

## Propagation

Written in Phase 4.

| File | Change |
|---|---|
| `.claude/hooks/ArchHook.java` | `build-report` mode (`buildReport`, `buildReportUsage`, `percent`); `reportFiles` extracted from `testReports`; `bashUnquotedGlob` and its call in `guardBash`; the backslash branch of `bashSegments` sets `quoted`; header mode list; dispatch |
| `.claude/hooks/ArchHook.jar` | rebuilt; `build --verify` green |
| `.claude/schemas/extensions.json` | `build_report`; `guard.unquoted_glob`; `shell_globs.$comment` and the `upgrade` `$comment` (`test_reports`) amended |
| `.claude/agents/java-spring-boot-developer.md` | § Discovering the layout, § What enters the context |
| `.claude/.ci/BuildReportTest.java` | new, cases 1-8 |
| `.claude/.ci/BashGuardTest.java` | G1-G8, a `shell(...)` helper that sets `SHELL` and JSON-escapes the command |
| `.github/workflows/validate.yml` | `hooks-cross-platform`: step `build-report reads both build tools' reports` |
| `docs/pt-br/07-ci-validate.md`, `docs/en/07-ci-validate.md` | new row and mermaid node `H31`; `BashGuardTest` row (37 cases, G1-G8); command list |
| `docs/pt-br/11-pitfalls.md`, `docs/en/11-pitfalls.md` part 2 | `guard bash` refuses an unquoted glob flag under zsh |
| `CLAUDE.md` § Commands | `build-report` row |

Goes to the generated project: **yes** — `export` copies the agent, `ArchHook.java`/`.jar` and
`extensions.json` whole; `guard bash` is already registered in
`project-bootstrap/templates/settings.json.example`. No `migrations` entry: no generated code is
left behind.

## CI coverage

| Job › step | What it proves | Green | Red (defect injected, fails by name) |
|---|---|---|---|
| `validate` › `hooks-cross-platform` › `build-report reads both build tools' reports` (`BuildReportTest`) | Cases 1-8: Maven and Gradle totals and coverage summed, the JaCoCo DOCTYPE read, report-level counters only, `max_failures` / `failure_lines`, `--newer`, log lines only without a failed test, "none" lines, exit 2 on a bad report | local run, 18 checks green | JaCoCo through the `disallow-doctype-decl` factory (case 1); nested counters summed (1); Gradle test glob dropped (2); Gradle coverage glob dropped (2); `max_failures` ignored (3); `failure_lines` ignored (3); no mtime filter (4); a `log_patterns` entry dropped (5); log printed whole (5); log despite a failed test (6); no-coverage exits 2 (7); parse error exits 0 (8) |
| `validate` › `hooks-cross-platform` › the existing `BashGuardTest` step | G1-G8 | local run, 37 cases green | check absent (G1, G5); quoting ignored (G2, G3, G7); backslash not marking `quoted` (G3); no shell gate (G4); basename not taken (G1, G5); second segment unread (G5); raw-text scan reads heredoc and quoted strings (G3, G6, G7); null `SHELL` treated as zsh (G8) |

Not yet run on the CI platform matrix (Linux, macOS, Windows): the first push does. G3's proof
needed `shell(...)` to JSON-escape the command itself; a first draft passed G6 and G7 vacuously
because the JSON it sent was invalid.
