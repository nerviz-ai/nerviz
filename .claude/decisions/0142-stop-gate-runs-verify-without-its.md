# 0142 · The `tests` Stop gate runs `verify`, without integration tests and coverage

- **Date:** 2026-10-12
- **Scenario:** Issue #126, triaged at 2186f1c — the `tests` Stop gate runs Maven only up to
  `test`, so `checkstyle-test` and `spotless:check` (bound to `verify`) never run, and the gate
  reports green on a tree CI's `./mvnw clean verify` rejects.
- **Decision:** Form 7c — `tests()` in `.claude/hooks/ArchHook.java` hands Maven `tests.stop_maven_args` of `.claude/schemas/extensions.json` (`verify -DskipITs -Djacoco.skip=true`); a `migrations` entry `stop-gate-runs-verify`
- **State:** approved by Lucas Fernandes, on 2026-10-12

## Reproduced on disk before classifying

From `issue-verifier`'s table at `2186f1c` (layer: static), confirmed rows only:

| # | Fact | Evidence |
|---|---|---|
| 1 | The Stop gate runs `mvnw -q -o -pl <modules> -am test` | `.claude/hooks/ArchHook.java:233` |
| 2 | `checkstyle-test` and `spotless:check` are bound to `verify` | `project-bootstrap/templates/pom.parent.xml.example:155-161`; `rules/code-quality.md:77` |
| 3 | A gate on `test` passes where CI's `verify` fails | follows from 1 and 2 |
| 4 | `0113` and `0097` rest on the executor's own `verify` to catch a restricted identifier or a multi-call throw lambda in `src/test`; `0116:22` the same premise | `0113:79`, `0097:92-93` |
| 5 | The executor specifies `verify` for every test run and its close | `agents/java-spring-boot-developer.md:256-261`, `:857`, `:908` |
| 9 | `naming.md` forbids `record` as an identifier; `testing.md` holds the one-call lambda | `rules/naming.md:85`, `rules/testing.md:84` |
| 10 | The generated root `CLAUDE.md` Tests row is the test command, not `verify` | `root.CLAUDE.md.example:23` |
| 11 | `git-publish` has no build step | `.claude/skills/git-publish/SKILL.md` |
| 12 | The Stop entry's `timeout` is 600 | `settings.json.example:279-281` |
| 13 | Unchanged at v0.25.2 / `HEAD` | no commit since v0.25.1 touches either file |

Not inputs: row 6 (the title's claim that the executor closes on `test`) is refuted; row 7
is partial; rows 8 and 14 (the reporter's run, the 20.88 s vs 83.86 s timing) are unproven.
The issue's proposed fix was judged by the verifier and is not carried over.

Found while designing, not in the table: `arch-adopt` installs no Checkstyle, so an adopted
project has no `checkstyle-test` execution; `jacoco-maven-plugin`'s `check` (wired by
`test-architect` setup) defaults to `verify`; no template sets `argLine`, so
`-Djacoco.skip=true` leaves surefire's command line intact. And outside this decision:
`ArchHook.java` has no Gradle path — `wrapper()` looks for `mvnw` only, so `check`, `format`
and `tests` do nothing in a Gradle project. A separate issue.

`0097` chose `verify` for both executions so the edit-time compile check would not pay them on
every edit; it weighed the `check` hook, not the `Stop` gate. `0107` and `0116` changed when the
gate runs, not which phase.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — Symptom | Rows 1-3: the gate prints `✅ Tests green` on a tree CI rejects | create nothing |
| 7 — Mandatoriness | Build gate; it already is a hook | 1–6 |
| 8 — Destination | Generated project. `ArchHook.java`, the jar and `extensions.json` travel whole; this repo has no wrapper and `tests` returns before Maven | — |
| 14 — Lifecycle event | `Stop`, unchanged | — |
| 15 — Reaction | Block, exit 2, unchanged | Form 8 |
| 16 — Existing mode | `tests` owns the gate; only what it hands Maven changes | a new mode |
| — Phase | `verify` without integration tests and coverage (option 1), chosen by the maintainer after the trade-offs of options 1-3 | options 2, 3 |
| — Existing projects | A `migrations` entry: the first `Stop` after an update may block on old `spotless`/`checkstyle-test` violations in a changed module | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | `tests` hands Maven `tests.stop_maven_args` from `extensions.json` — `verify -DskipITs -Djacoco.skip=true` | 9 | **Approved** |
| 2 | `test checkstyle:check@checkstyle-test spotless:check` | 4 | Rejected — restates the POM in the hook, false red in an adopted project |
| 3 | `verify`, as CI runs it | 7 | Rejected — Docker and minutes at every turn end, coverage blocking mid-feature |
| 4 | create nothing — the executor already runs `verify` | 3 | Rejected — the guarantee prints green; prose does not fix it |

### Option 1 — `verify -DskipITs -Djacoco.skip=true`, as data (score 9)

**Motivator:** axis 16 — the gate exists; the phase it stops at is the defect.

**What changes:** `tests()` reads `tests.stop_maven_args` (a string array) from
`.claude/schemas/extensions.json` and runs `mvnw -q -o -pl <modules> -am <args…>`. A missing or
empty key blocks with exit 2 naming the key — never a silent fallback. Green prints the args it
ran (`✅ verify green (-DskipITs -Djacoco.skip=true) — CI's clean verify also runs the ITs and
coverage`), so the line never claims more than ran. Red keeps the filtered tail, which already
matches Checkstyle's and Spotless's `[ERROR]` lines. `statusMessage` of the Stop entry, in both
settings files, says `verify`. A `migrations` entry for every blueprint.

**Pros:** the POM becomes the single owner of what `verify` checks — a later execution bound
there (as `0113`'s was) reaches the gate with no edit here. No Docker. In an adopted project it
runs only what that POM binds, never a foreign Checkstyle config. The flags are data, so a
project can add a `-D…skip` for a network-bound plugin of its own.

**Cons:** each firing adds `package` (and Boot's repackage) to the cost — not measured. Still
not CI: integration tests and coverage stay there, which the green line and a pitfall entry say.
An adopted project with a network-bound plugin at `verify` blocks offline until it adds a skip.

**Points cut in the rubric:** 9 (partly) — a heavier build on every `Stop` with a changed
`.java`.

**CI:** `ModuleMapTest` (`validate` › `hooks-cross-platform`) — its `tests` case expects
`-pl . -am verify -DskipITs -Djacoco.skip=true` and copies the real `extensions.json` into the
project; a new case with an edited `tests.stop_maven_args` proves the args come from the data,
and one with the key removed proves exit 2. `TestsDeferTest` unchanged (it asserts whether
Maven is called). The `migrations` entry: `design` › `frontmatter schema` (`required_fields`,
`id_pattern`).

### Option 2 — explicit goals (score 4)

Restates in the hook what the POM already binds — a norm in two places (invariant 2), and the
same class of bug as #126 for the next execution bound to `verify`. `arch-adopt` installs no
Checkstyle, so `checkstyle:check@checkstyle-test` runs the plugin's default config, or fails
offline with the plugin absent: a false red on every turn of an adopted project. Cheapest per
firing.

**CI:** the same `ModuleMapTest` case, with the explicit goals.

### Option 3 — full `verify` (score 7)

Identical to CI and catches a broken IT before the push. Docker must be up at every turn end or
the gate blocks; minutes per firing against `timeout: 600`; JaCoCo's `check` (80 % lines, 70 %
branches) blocks mid-feature before the tests exist — a block on a judgment that is sometimes
wrong. Cut: 9 fully, 5 (Docker becomes a dependency of every turn).

**CI:** the same `ModuleMapTest` case.

### Option 4 — create nothing (score 3)

The executor's prose already says `verify`, but the gate is the guarantee and it prints green —
the observed failure is the guarantee lying, which prose cannot fix. Cut: 1, 4, and 9's mirror
(a gate that exists and proves less than it says).

**CI:** nothing to add.

## References

| Claim | Source |
|---|---|
| A rule that must always hold is a hook | `@CLAUDE.md` invariant 6 |
| A mode reads its lists from `extensions.json` | `@CLAUDE.md` invariant 10; `tests.writer_agent_max_minutes` (`0116`) as precedent |
| `checkstyle-test` and `spotless:check` at `verify`, and why not `validate` | `0097`; `pom.parent.xml.example` |
| Failsafe honours `skipITs`; `jacoco.skip` skips every JaCoCo goal | failsafe's and jacoco-maven-plugin's goal parameters (`best-practices-links.md` of `test-architect` for JaCoCo) |
| The jar is what the hook runs; rebuild after editing the source | `@CLAUDE.md` § Known pitfalls; `0075` |
| A convention change that leaves existing projects behind is a `migrations` entry | `migrations` block of `extensions.json`; `arch-adopt` |
| A stub `mvnw` that records its arguments proves what reaches Maven | `ModuleMapTest`, `TestsDeferTest` (`0116`) |

## Propagation

| File | Change |
|---|---|
| `.claude/hooks/ArchHook.java` | `tests()` reads `testsStopArgs()`; missing key blocks naming it; green and red lines print what ran; header line says `verify` |
| `.claude/hooks/ArchHook.jar` | rebuilt from the source |
| `.claude/schemas/extensions.json` | `tests.stop_maven_args` and its `$comment`; `migrations` entry `stop-gate-runs-verify` |
| `.claude/settings.json`, `project-bootstrap/templates/settings.json.example` | the `tests` Stop entry's `statusMessage` — no flags in it, so it never restates the data |
| `.claude/.ci/ModuleMapTest.java` | the `tests` case expects the data's args from a copy of the real `extensions.json`; one case with edited args, one with the key removed (exit 2, no Maven call) |
| `docs/pt-br/11-pitfalls.md`, `docs/en/11-pitfalls.md` | part 2: a green `Stop` is not a green CI — ITs and coverage stay with the executor and CI |
| `docs/*/01-*.md`, `docs/*/07-ci-validate.md`, `README.md` | the `tests` row and the `ModuleMapTest` row |

Not changed: the executor's prose (already `verify`), `root.CLAUDE.md.example`'s Tests row
(row 10 — a command to run tests, not the gate), `git-publish` (row 11 — no gate state to read).

Goes to the generated project: **yes** — `ArchHook.java`, `ArchHook.jar` and `extensions.json`
are copied whole by `export`; the `statusMessage` through `settings.json.example`.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · hooks-cross-platform` › `ModuleMapTest` | `tests` hands Maven `-am` + `tests.stop_maven_args` from the project's own `extensions.json`; an edited list reaches Maven as written; a missing key exits 2 with no Maven call | green on the tree (3 new cases ✅) · red with `List.of("test")` injected in place of `testsStopArgs()`: all 3 fail by name (`got -q -o -pl . -am test`, `exit 0, expected 2`) |
| `validate · hooks-cross-platform` › `TestsDeferTest` | the deferral of `0116` is unchanged | green |
| `validate · design` › `frontmatter schema` | the `migrations` entry's fields and id (`MigrationsSchemaTest` green) | green |

Every `.claude/.ci/*Test.java` that `validate.yml` calls ran green after the jar was rebuilt;
`build --verify` ✅.

