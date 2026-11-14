# 0205 · `guard bash` resolves relative targets against the shell's working directory, cuts segments at a newline, and the executor writes a Gradle build file

- **Date:** 2026-11-09
- **Scenario:** Issue #198, triaged at a5638d1: `guard bash` ignores a preceding `cd`, a newline does not end a segment, and `agent_classes.executor.write_allow` has no `build.gradle`.
- **Decision:** Form 7c in the existing `guard bash` mode — `.claude/hooks/ArchHook.java` (`guardBash`, `bashWriteTargets`, `addBashTarget`, `bashSegments`) plus `guard.bash_write_shapes.cwd_commands` in `.claude/schemas/extensions.json` — and a widened `agent_classes.executor.write_allow` with the matching body of `.claude/agents/java-spring-boot-developer.md`
- **State:** approved by Lucas Fernandes, on 2026-11-09 — option 1; release bump: patch

## Reproduced on disk

From the `issue-verifier` table of `/triage-issue 198`, layer `static`, at `a5638d1` (= v0.51.4). Only confirmed rows.

| # | Claim | Evidence |
|---|---|---|
| 1 | executor `write_allow` is `src/**, **/src/**, docs/use-cases/UC-*/UC-*-spec.md, pom.xml, **/pom.xml` | `.claude/schemas/extensions.json:598`, unchanged since v0.50.0 |
| 2 | `bashWriteTargets` reads each segment alone, with no working-directory state | `ArchHook.java:9818` loop; `:9891` `ROOT.resolve(raw)` |
| 3 | `cd src/main/java && cat > Foo.java <<EOF` refused for `java-spring-boot-developer` | exit 2, "`Foo.java` is outside its territory" |
| 4 | control `cat > src/main/java/Foo.java <<EOF` | exit 0 |
| 5 | `cd src/main/java && sed -i "" s/a/b/ Foo.java` refused | exit 2 |
| 6 | a newline does not end a segment: `sed -i "" s/a/b/ src/main/java/Foo.java⏎python3 - <<EOF` reads `python3` as a `sed` operand | exit 2, "`python3` is outside its territory"; with `;` exit 0. Javadoc `:9794` says newlines cut segments; code `:10138-10181` only flushes the token at end of line |
| 7 | the refusal tells the agent to stop | `ArchHook.java:9650` |
| 8 | executor Edit of `build.gradle` / `build.gradle.kts` | exit 2; `pom.xml` control exit 0 |
| 9 | the installers already carry `build.gradle*` | `extensions.json:615-616, 626-627, 634` (0204) |
| 11 | the agent accepts a Gradle project but its build-file prose is Maven-only | `java-spring-boot-developer.md:207` vs `:150-153, 167, 201-206` |
| — | not raised by the reporter: fail-open | `cd src && cat > ../CLAUDE.md <<EOF` exit 0 — `ROOT/../CLAUDE.md` lands outside `ROOT` and is dropped at `:9892`; plain `cat > CLAUDE.md` exit 2 |

Earlier report of defect 1: #54 (closed 2026-10-06). Only its BSD `sed -i ''` half landed (0118); the `cd` half never did.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | 8+ legal executor writes refused across 2 real runs; one write outside the territory admitted | create nothing |
| 7 — mandatoriness | territory is a guarantee (0058/0059/0063) | 1-5 (prose) |
| 16 — existing mode | `guard bash` already runs the check; the defect is in how it learns the path | a new mode, a new registration |
| 8 — destination | both: `ArchHook.jar` and `extensions.json` travel whole through `export` | — |
| base directory (maintainer) | the payload's `cwd` (ROOT when absent), then each literal `cd`/`pushd` in the command | in-command `cd` only |
| non-literal `cd` (maintainer) | relative targets after it are unresolvable and pass; `guard sweep` covers them at `Stop` | blocking |
| Gradle (maintainer) | territory and agent body together | territory only; a separate record |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | Track the working directory and newlines in `guard bash`; widen executor `write_allow` with the Gradle body | 8 | Proposed |
| 2 | After any `cd`, treat every relative target as unresolvable | 5 | Rejected — removes the false positive but keeps the fail-open case, and throws away a path that is literally readable |
| 3 | Create nothing in the guard; tell the executor in prose to write root-relative paths, and add only the Gradle globs | 3 | Rejected — invariant 6: prose against a defect in the guarantee itself; the fail-open stays; 0204 rejected territory-only Gradle |

### Option 1 (score 8)

**Motivator:** axes 1, 7 and 16 — a defect in an existing guarantee, owned by `ArchHook.java`.

**Shape — working directory.**

- `extensions.json` → `guard.bash_write_shapes` gets `"cwd_commands": ["cd", "pushd"]` and `"cwd_unknown_commands": ["popd"]`. Invariant 10: no command name in the Java. Its `$comment` gains one sentence: "`cwd_commands` move the directory relative targets resolve against — start at the payload's `cwd`, ROOT when absent; a literal operand moves it, an operand holding an `unresolvable_markers` entry, `-`, or none makes it unknown, and so does a `cwd_unknown_commands` entry: while it is unknown a relative target is unresolvable and passes, an absolute one is still judged (0205)."
- `guardBash` reads `cwd` from the hook payload's top level (not `tool_input`). Absolute → that `Path`, normalized. Missing, blank or relative → `ROOT`.
- `bashWriteTargets(shapes, cmd)` becomes `bashWriteTargets(shapes, cmd, base)`; `base` may be `null` (unknown). Per segment, in order: collect targets with the current `base`, then, if the segment's head (after `bashCommandName`) is in `cwd_commands`, update `base` from its first non-flag operand: none, `-`, or a token holding a marker → `null`; absolute → that path; relative → `base == null ? null : base.resolve(op).normalize()`. A head in `cwd_unknown_commands` → `null`.
- **Subshell.** An unquoted word starting with `(` opens a group: strip the `(`, push the current `base` on a stack. An unquoted word ending with `)` closes it: strip the `)`, and after that segment restore the pushed `base`. `(cd src && echo x > a.txt); echo y > CLAUDE.md` judges `CLAUDE.md` at the root.
- `addBashTarget(out, markers, tok, base)`: an absolute `raw` resolves on its own; a relative one with `base == null` returns; else `base.resolve(raw)`. The `startsWith(ROOT)` test stays — a path outside the repository is still not this repository's to police — but the fail-open case now resolves to `ROOT/CLAUDE.md` and is judged.
- The other caller(s) of `bashWriteTargets` (if `guard sweep` or `schema` reuse it) pass `ROOT`.
- Javadoc of `bashWriteTargets` gains a paragraph with the three sentences of this record's *Why* and the 0205 citation.

**Shape — newline.** In `bashSegments`, at the end of each line, outside a heredoc:

- inside an open quote: append `'\n'` to the token and carry on — the quoted word continues on the next line;
- the line ends with an unescaped `\` (today `:10150` appends it as a character because `i + 1 < line.length()` is false): drop it, keep the token open and the segment open — a line continuation;
- otherwise: flush the token, and push `cur` as a segment when non-empty — the newline is a separator, as the javadoc already says.

The heredoc line itself (`cat > f <<EOF`) ends its segment the same way.

**Shape — Gradle.**

- `agent_classes.executor.write_allow` → `["src/**", "**/src/**", "docs/use-cases/UC-*/UC-*-spec.md", "pom.xml", "**/pom.xml", "build.gradle", "**/build.gradle", "build.gradle.kts", "**/build.gradle.kts"]`. Its `$comment` gains: "The build file is `pom.xml` or `build.gradle(.kts)`, rooted and under `**/` like `src/**`, since `project-bootstrap` generates both (0179/0180); the agent body bounds what it may write there (0205)."
- `java-spring-boot-developer.md`, `**Writes:**` bullet that starts `` `pom.xml` — **only** `` becomes:

  > - The build file — `pom.xml`, or `build.gradle` / `build.gradle.kts` when the project carries `gradlew` — **only** a dependency the spec declares as a requirement (… same list of partials …). Maven: one `<dependency>` element per row, with no version when the Boot parent manages it. Gradle: one line per row in the `dependencies { }` block, with the configuration the row's scope maps to (`implementation`, `runtimeOnly`, `testImplementation`) and no version when the `io.spring.dependency-management` plugin manages it — `implementation 'g:a'` in Groovy, `implementation("g:a")` in Kotlin. Nothing else in the file: not a plugin, not a repository, not a property, not a version bump. (… the lessons-learned-013 § 10 sentences stay unchanged, with `pom.xml` read as "the build file" …)

- `**Does not write:**` bullet `` `pom.xml` beyond the dependencies the spec declares `` → "The build file beyond the dependencies the spec declares".
- Input validation 3b: "the only thing entitling this agent to touch `pom.xml`" → "to touch the build file"; "becoming a `pom.xml` edit" → "becoming a build-file edit".
- Any remaining `pom.xml`-only sentence about adding a declared dependency in `:150-210` is read the same way. Sentences about Maven-only commands (`./mvnw`) are left alone.

**Why this is Form 7c** (javadoc of `bashWriteTargets`, three sentences): The check already exists as `guard bash` on `PreToolUse/Bash`, and it is wrong about where a relative path lands, so the fix belongs inside it, not in a second mode or in prose. A shell keeps a working directory across segments and across calls, and a target read against `ROOT` after a `cd` both refuses a legal write and admits an illegal one. Treating every target after a `cd` as unresolvable (option 2) was rejected: it drops a literally readable path and keeps the fail-open.

**Pros:** fixes the owner of all three defects; closes the fail-open; keeps 0063's bar (what cannot be read passes); no new registration, no extra process.

**Cons:** criterion 5 again — `cd` has spellings (`builtin cd`, `cd -P`, `CDPATH`) the parser will not learn; flags are skipped, the rest falls into "unknown → pass". Whether the payload's `cwd` follows Bash's `cd` across calls is not verified here; when it does not, it equals `ROOT` and nothing gets worse.

**Points cut in the rubric:** criterion 5 (open-ended spellings).

**CI:** `validate · hooks-cross-platform › BashGuardTest` and `› AgentTerritoryTest`, new cases below. No new step, no new workflow.

### Option 2 (score 5)

After any `cd`/`pushd`, every relative target is unresolvable. Ends the false positives with almost no code, but `cd src && cat > ../CLAUDE.md` stays exit 0, and a literal path is discarded for no reason.

### Option 3 (score 3)

Executor prose "always write root-relative paths, no `cd`" plus the four Gradle globs. Invariant 6 — the guard stays wrong for every other skill and agent; the fail-open stays; 0204 already rejected widening a territory without the procedure that bounds it.

## Test cases (Phase 4 step 9)

Every case runs against `.claude/hooks/ArchHook.jar`. "Red" is the result on the jar built from `a5638d1`, which is what proves the case.

`.claude/.ci/BashGuardTest.java`, executor cases via the existing `agentBash` helper (session `report`). A helper variant `agentBashCwd(hook, session, agent, command, cwd, wanted, label)` adds `"cwd":"<cwd>"` at the payload's top level.

| Id | Command (cwd) | Want | Red at a5638d1 | Defect it catches |
|---|---|---|---|---|
| D1 | `cd src/main/java && cat > Foo.java <<EOF⏎x⏎EOF` | 0 | 2 | `cd` ignored |
| D2 | `cd src/main/java && sed -i '' s/a/b/ Foo.java` | 0 | 2 | `cd` ignored, `sed` shape |
| D3 | `cd src && cat > ../CLAUDE.md <<EOF⏎x⏎EOF` | 2 | 0 | fail-open |
| D4 | `cd docs && echo x > notes.md` | 2 | 2 (as `notes.md`) | `cd` into a forbidden place still judged — asserts the message names `docs/notes.md` |
| D5 | `cd $D && echo x > CLAUDE.md` | 0 | 2 | unknown base → pass |
| D6 | `cd $D && echo x > <ROOT>/CLAUDE.md` (absolute, built in the test) | 2 | 2 | absolute still judged while base is unknown |
| D7 | `(cd src && echo x > main/a.txt); echo y > CLAUDE.md` | 2 | 2 | group restores the base — asserts the message names `CLAUDE.md`, not `src/CLAUDE.md` |
| D8 | `pushd src/main/java && echo x > A.java && popd && echo y > B.java` | 0 | 2 | `pushd` moves, `popd` makes unknown |
| N1 | `sed -i '' s/a/b/ src/main/java/Foo.java⏎python3 - <<EOF⏎print(1)⏎EOF` | 0 | 2 | newline not a separator |
| N2 | `ls⏎sed -i '' s/a/b/ docs/x.md` | 2 | 0 | newline not a separator (second line's write unseen) |
| N3 | `sed -i '' s/a/b/ \⏎src/main/java/Foo.java` | 0 | 2 | line continuation kept as `\` |
| N4 | `git commit -m "a⏎sed -i x CLAUDE.md"` | 0 | 0 | a newline inside quotes stays a character |
| P1 | `cat > Foo.java <<EOF⏎x⏎EOF` (cwd `<ROOT>/src/main/java`) | 0 | 2 | payload `cwd` read |
| P2 | `echo x > notes.md` (cwd `<ROOT>/docs`) | 2 | 2 | payload `cwd` read — asserts `docs/notes.md` in the message |

D4, D7 and P2 need the stderr check: they assert that the refusal names the resolved path. If the helper only compares exit codes, add a `wantedText` argument as `shell(...)` already has.

`.claude/.ci/AgentTerritoryTest.java`, `java-spring-boot-developer`, Edit through the existing `write` helper:

| Id | `file_path` | Want | Red at a5638d1 |
|---|---|---|---|
| G1 | `build.gradle` | 0 | 2 |
| G2 | `build.gradle.kts` | 0 | 2 |
| G3 | `app/build.gradle.kts` | 0 | 2 |
| G4 | `settings.gradle` | 2 | 2 (control: only the build files) |

## Verification (Phase 4 step 11, main thread)

```
java .claude/hooks/ArchHook.java build
java .claude/hooks/ArchHook.java build --verify
java .claude/.ci/BashGuardTest.java
java .claude/.ci/AgentTerritoryTest.java
java .claude/hooks/ArchHook.java schema
java .claude/hooks/ArchHook.java doctor
claude plugin validate .claude/skills
```

Red run: the same two tests against the jar of `a5638d1` (`git stash` the jar, or run before the rebuild) must fail by the ids above.

## References

| Claim | Source |
|---|---|
| what cannot be read passes; the bar is not completeness | `.claude/decisions/0063-bash-write-enforcement.md` Option 1 |
| `sed -i ''` and `script_flags` | `.claude/decisions/0118-skill-model-pin-audit-tail-and-bsd-sed.md` |
| a Gradle build file is widened together with the procedure | `.claude/decisions/0204-archunit-installer-on-gradle-and-dead-citations-to-excluded-skills.md` |
| the executor owns the build file's declared dependencies | `.claude/decisions/0060-lessons-learned-013-shipping-defects.md`, `0062-lessons-learned-013-ownership.md` |
| Gradle is a generated build tool | `project-bootstrap/templates/build.gradle.*.example` (`io.spring.dependency-management`) |
| no list in the Java | `@CLAUDE.md` invariant 10 |
| hook payload carries `cwd` at the top level | Claude Code hooks reference, common input fields — not re-verified in this repo; fallback `ROOT` makes it safe either way |
| earlier report of defect 1 | issue #54 |

## Propagation

| File | Change |
|---|---|
| `.claude/hooks/ArchHook.java` | `guardBash` reads the payload `cwd`; `bashWriteTargets(shapes, cmd, base)` tracks the directory (`cwd_commands`, `cwd_unknown_commands`, `( )` groups) with `moveBase`; `addBashTarget` takes `base`; `bashSegments` cuts at a newline, joins quoted newlines, drops a trailing-backslash continuation; `bashTargets` (sweep) passes `ROOT`; javadoc updated |
| `.claude/hooks/ArchHook.jar` | rebuilt with `build`; `build --verify` green |
| `.claude/schemas/extensions.json` | `bash_write_shapes.cwd_commands`/`cwd_unknown_commands` and the `$comment` sentence; `executor.write_allow` gains the four Gradle globs and the `$comment` sentence |
| `.claude/agents/java-spring-boot-developer.md` | Writes bullet (Maven and Gradle forms), Does-not-write bullet, input validation 3b read as "the build file" |
| `.claude/.ci/BashGuardTest.java` | D1-D8, N1-N4, P1-P2 and the `agentBashCwd` helper (cwd, stderr needle) |
| `.claude/.ci/AgentTerritoryTest.java` | G1-G4 |
| `docs/pt-br/11-pitfalls.md`, `docs/en/11-pitfalls.md` | unchanged — neither says targets resolve against the project root |

Goes to the generated project: **yes** — `export` copies `ArchHook.java`, `ArchHook.jar`, `schemas/extensions.json` and the agent whole; no template changes.

## CI coverage

`validate · hooks-cross-platform › BashGuardTest` gains D1-D8, N1-N4 and P1-P2; `› AgentTerritoryTest` gains G1-G4. Red on the jar and schema of `a5638d1`: D1, D2, D5, D8, N1, N3, P1 (exit 2 for 0), D3, N2 (exit 0 for 2), D4, D7, P2 (exit 2 but the message names `notes.md` or `main/a.txt)` instead of the resolved path), G1-G3 (exit 2 for 0). D6, N4 and G4 are green before and after by design. No new step, no new workflow.
