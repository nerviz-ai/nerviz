# 0117 · `/new-feature` cost per entry scenario — where a run spends, and which cuts keep the design intact

- **Date:** 2026-10-03
- **Scenario:** "com base nessas informacoes de custo de execucao da skill new-feature — analise a skill e agents que sao executados dentro dela e veja possiveis pontos de otimizacao de custo mas sem perder a qualidade da execucao da skill atualmente [...] crie um relatorio de possiveis otimizacoes de tokens e custo para cada cenario de entrada dela"
- **Decision:** option 1 — `.claude/agents/java-spring-boot-developer.md` § What enters the context: test runs go to a log under `$TMPDIR`, failures and totals are read from the reports, and each file is read once. Option 2 is recorded as a deferred candidate, not rejected
- **State:** approved by Lucas Fernandes, on 2026-10-03
- **Goes to the generated project:** yes, for every option that edits a piece — `new-feature`, `java-spring-boot-developer` and the design skills all sit in `export.include`

## Measured, not estimated

Source: `demo-clean-arch-single-module/.claude/audit-usage/` (`history.jsonl`, `nodes.jsonl`,
the three reports the user named) and the session transcripts under
`~/.claude/projects/-~-Documents-GitHub-demo-clean-arch-single-module/`, read on
2026-10-03. Prices: the project's `pricing.json` (2026-10-01) — Opus 5.5 input 4 · output 20 ·
cache read 0.2 · cache write 5; Sonnet 5 input 2 · output 10 · cache read 0.2 · cache write 2.5
(USD/MTok).

The "USD 15 run" the scenario cites is `2026-10-01T12-05-08--new-feature.md`:
`/new-feature UC-005-complete-kyc-verification`, **input row 3** (implement an approved spec),
not a design run. The executor is USD 14.82 of USD 15.09.

### Cost per entry scenario

| Scenario (input row) | Runs measured | Cost | Where it goes |
|---|---|---|---|
| Empty argument — list (row 1) | 1 | USD 0.26 | Loading the 13k-token `SKILL.md` on Opus: 44k cache write, 1 `Bash` |
| Error rows 4–8 | 3 | USD 0.21–0.32 | Same: the body loads to print one error |
| New case, design only, base path (use case → domain → REST → persistence → tests) | 1 (`2026-10-02T12-34`) | USD 3.78 | Main thread on Opus, context 72k → 250k; every design turn rereads all earlier partials, templates and the skill bodies |
| New case with messaging and jobs, design only | 1 priced (`2026-10-01T11-07`), 3 unpriced | USD 6.16 | Same shape, two more skills; context reaches 285–360k before consolidation |
| New case, design + *Implement now* in the same run | 1 (`2026-10-02T23-56`, UC-007) | USD 6.96 | Design USD 3.62, executor USD 3.34 |
| Implement an approved spec (row 3) | 5 | USD 3.34 · 4.57 · 4.87 · 6.17 · **14.82** | **Executor: 94–98% of the run** |
| Resume a draft (row 2) | 1 unpriced | — | Only the missing partials; same per-skill shape |
| Short path · security (step 3b) · first-feature pre-flight | 0 | — | Not taken in the demo; no number |

*Note (0119):* the row 3 totals leave out the main thread's tail. The audit closed every
background implement run at the executor's hand-back, so the `git-publish` after it (two
confirmations at 250–370k context, about USD 0.3–0.5) is in no report. The executor's own cost
is complete. @.claude/decisions/0119-audit-ignores-subagent-handback-executor-single-report.md.

### Inside the executor

Eight `java-spring-boot-developer` transcripts. Cost is ~75–80% cache read: every turn rereads
the whole context, so the bill is **turns × context size**.

| Run | Turns | Cache read | Peak context | Output | `./mvnw` output | Spec + partials read | Same file reread | Cold cache rewrites (> 60k) |
|---|---|---|---|---|---|---|---|---|
| UC-005 (USD 14.82) | 203 | 58.8M | 477k | 115k | 191k chars, 34 calls | 92k chars | 48k chars | 1 (328k) |
| UC-006 (USD 4.87) | 178 | 47.7M | — | 85k | 122k chars, 22 calls | 75k chars | 46k chars | 0 |
| UC-003 (2026-09-30) | 183 | 67.2M | — | 193k | 17k chars | 117k chars | 40k chars | 0 |
| UC-007 (USD 3.34) | 67 | 11.4M | 267k | 44k | 54k chars | 72k chars | 30k chars | 0 |
| UC-004 | 115 | 17.7M | — | 46k | 30k chars | 62k chars | 18k chars | 0 |

- The test command is improvised, because the agent body names none for Block 4 —
  `./mvnw -q test 2>&1 | tail -250`, `tail -200`, `tail -150`. `-q` still prints stack traces
  and Spring/Testcontainers logs, so each run puts up to 30k chars into the context, and it
  stays there for every later turn.
- The spec and the partials are read in full, sometimes twice (`cat`, then `Read`). The
  executor needs them: consolidation is by reference.
- One cold cache rewrite in eight runs, and it happened during a long `Bash`.

### Inside the design phase (main thread, Opus 5.5)

Each design skill costs USD 0.2–0.8. Its turns run at 110k–360k context. The skills read
approved specs, templates and `src/` in full through `cat` — 60–120k chars in `use-case-design`
alone in four runs. A result over ~30k chars spills to a file and is then `Read` again
(32–34k-char `Read`s of `tool-results/` seen twice).

### Side findings — handled in 0118

Recorded here when found; checked and treated in
`@.claude/decisions/0118-skill-model-pin-audit-tail-and-bsd-sed.md`.

1. **`git-publish`'s `model: sonnet` did not apply.** Confirmed, with a wider cause: a skill's
   `model`/`effort` applies only when typed as `/name`. Invoked through the `Skill` tool, it
   keeps the caller's model. The bug is open upstream (anthropics/claude-code#98898).
   Documented as a runtime pitfall.
2. **The audit report puts consolidation, approval and delegation under the last chained
   skill.** In UC-007, that is `test-architect`: USD 1.91, of which ~USD 1.47 is
   consolidation. Confirmed and documented in the report's footnote. *Correction:* this record
   first said the audit also put post-run work under `git-publish`. That came from the
   analysis script written for this record, not from the audit, and it does not hold.
   `git-publish`'s USD 0.29–0.54 is its own: 3–6 calls at a context of 250–370k.
3. **`guard bash` read BSD `sed -i ''` as a path and blocked the executor twice.** Fixed in
   `guard bash`.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — Symptom | Measured: 9 priced runs, 8 executor transcripts. The executor is 94–98% of an implement run; the design phase costs USD 3.6–6.2 | Create nothing as the default |
| 5 — Nature | Procedure text inside an existing agent and an existing orchestrator | A new skill, agent, rule or hook |
| 7 — Mandatoriness | A cost discipline, not build/security/compliance. A run that tails 250 lines is wasteful, not wrong | Forms 7 and 8 |
| 8 — Destination | Both | Nothing to add to `export` — every touched piece already travels |
| 10 — Quality ceiling | **Low risk only**: nothing that changes a design decision | Effort reduction, consolidation on a smaller model |
| Levers accepted | **Configuration of isolated pieces** and **tool output**. Body trimming and on-demand loading of conditional blocks were offered and not chosen | Edits to the design skills' bodies for size |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | Executor tool-output discipline — `agents/java-spring-boot-developer.md` | 8 | **Approved** |
| 2 | Option 1 + executor split by block group — `new-feature/SKILL.md` § Executor offer + `java-spring-boot-developer.md` input | 7 | **Deferred** — a candidate for a later change; see § Reopening option 2 |
| 3 | Option 1 + targeted reads in the design skills | 5 | Not chosen — the only option that can change a design decision |
| 4 | Create nothing | 3 | Rejected — the waste was measured |
| 5 | A test-runner subagent (Sonnet `low` or Haiku) called by the executor | 3 | Rejected — see § Option 5 |

Rejected by the data, kept here so they are not proposed again without new numbers:

| Idea | Why not |
|---|---|
| `experimental.cacheTtl: 1h` on the executor | 1 cold rewrite in 8 runs. The 1h rate makes every cache write 1.6× (Sonnet 4.0 vs 2.5/MTok): on UC-005, +USD 1.21 in writes against −USD 0.75 for the one rewrite avoided. Net loss |
| Design skills on `sonnet` | Cache is per model (`@claude-help.md` § Prompt cache). Each switch inline pays a cold prefix of 100–360k, and Opus 5.5 cache read costs the same as Sonnet 5's. The saving is output only, and 0081 ranks a wrong partial the costliest defect downstream |
| `effort: medium` on the executor | Output is 44k–193k tokens per run and stays in the agent's context, so this is a real lever. It is out of the "low risk" ceiling: revisit with an A/B pair of audit reports |
| Trim the skill bodies / load conditional blocks on demand | Not chosen in the interview. Measured ceiling: it would mostly shrink the USD 0.21–0.32 of rows 1 and 4–8, plus ~13k tokens reread per design turn |

### Option 1 — executor tool-output discipline (score 8)

**Motivator:** axis 1. The `./mvnw` output and the rereads are the part of the executor's
context that adds no information, and the agent body names no test command, so each run
improvises one.

**Mechanism.** Edit `agents/java-spring-boot-developer.md`, and nothing else:

- **One named test command for Block 4 and every focused rerun.** Run the build into a log
  file. Print the exit code and the `Tests run:` summary line. On failure, print only the
  failing tests from `target/surefire-reports/*.txt`, capped at about 80 lines. Never use
  `| tail -N` on a test run. This names one command where today none is named, the same way
  `./mvnw -q test-compile` is already named per block.
- **Read once.** Read the spec and each partial one time, by section, when the step needs
  it. Do not `cat` a file and then `Read` it. To check a file after an `Edit`, use
  `grep -n` or a `Read` with offset/limit, not a full reread.

**Expected:** 5–10% of an executor run. UC-005: ~41k tokens of build output removed at
~60 remaining turns ≈ 2.5M cache reads ≈ USD 0.5, plus rereads ≈ USD 0.15. UC-006 ≈ USD 0.4.
The saving grows with the number of red test runs.

**Pros:** smallest change, one file, zero design risk. The agent can still read more when a
failure needs it. The cap is on what enters the context by default, not on what can be seen.

**Cons:** persuasion — the model can still type `tail -250`. A `PreToolUse` hook that
rewrote commands would be a guarantee against a judgment call, which is criterion 9 of the
rubric, so it is not proposed.

**Points cut in the rubric:** 4 (persuasion), rounded.

**CI:** `validate › schema` already proves the agent's frontmatter, class sections and
`export` entry. The behavior is not testable in CI, because it is what the model types. It is
measured on the next audit report: `./mvnw` chars per run, from the transcript.

### Option 2 — Option 1 + executor split by block group (score 7)

**Motivator:** axis 1. Cache reads ≈ turns × average context. One executor carries
everything from Block 1 to Block 4: 31–33k at start, 267–477k at the end.

**Mechanism.** In `new-feature/SKILL.md` § Executor offer, *Implement now* delegates two or
three times in sequence, not once:

1. Blocks 1–2: domain, persistence.
2. Block 3, then S, M and J when the spec has them.
3. Block 4: tests and `./mvnw verify`.

Each delegation passes the spec path and the block range. `java-spring-boot-developer.md`
gains that input. Its `### Resume` survey already starts a run from what is on disk, so a
later group treats earlier blocks as done, and that path is already supported. A group that
fails stops the chain, and the final report is the last group's report plus the earlier
ones' file lists.

**Expected:** each group restarts at ~58k (base + spec and partials) instead of carrying the
earlier blocks. Linear growth model on UC-005 and UC-006: −35–50% cache reads, so **−30–40%
of executor cost** (UC-005 ≈ −USD 5; UC-006 ≈ −USD 1.7). An estimate, to confirm with one
measured run before and after.

**Pros:** the largest lever measured, and it lands on the scenario that costs the most.
Reasoning tokens from Block 1 stop riding along into Block 4.

**Cons:**
- Quality risk is low to moderate, not zero. A decision taken while fixing Block 1's compile
  errors and not written to disk is invisible to group 3 — exactly what a resumed run already
  accepts.
- Three subagent starts: `SubagentStart`/`SubagentStop` hooks fire three times (`tests
  agent-start`, `context subagent`, `audit agent`).
- The final-report merge is new orchestration text.
- `@.claude/decisions/0116-tests-defers-while-a-writer-subagent-runs.md` defers the `tests`
  Stop gate while a writer runs. Between groups no writer runs, so the gate may fire between
  them. Check this before writing.

**Points cut in the rubric:** 5 (one more indirection), 6 (no multi-call executor precedent),
rounded.

**CI:** `validate › schema` covers both bodies. `AgentTerritoryTest` is unaffected, since
territory is keyed on `agent_type`, not on the call count. The 0116 interaction needs a case
in the `tests` gate test if that gate is touched; otherwise nothing is testable in CI.

### Option 3 — Option 1 + targeted reads in the design skills (score 5)

**Motivator:** axis 1. Design skills `cat` whole approved specs, templates and source files,
60–120k chars per skill. Those chars ride every later Opus turn of the run, and the amount
grows with the number of approved cases.

**Mechanism.** In `use-case-design`, `domain-modeling`, `rest-api-architect`,
`persistence-architect`, `messaging-architect`, `jobs-architect` and `test-architect`: read an
approved spec's component table and the section the step needs (`grep -n '^## '` then
`sed -n`), not the whole file. Read a template once.

**Expected:** USD 0.3–0.6 per design run today, more as `docs/use-cases/` grows.

**Cons:** this is the one option that can change a design decision. A skill that reads less
of an earlier case can miss an aggregate to reuse, and 0081 ranks a wrong partial the costliest
defect downstream. It touches seven bodies. It is at the edge of the ceiling the interview set.

**Points cut in the rubric:** 4 (persuasion), 5 (seven files), 3 (it shrinks a recurring read,
but its saving depends on project size), rounded down.

**CI:** `validate › schema` covers the seven bodies. Nothing behavioral is testable.

### Option 5 — a test-runner subagent (score 3)

Raised after the proposal: a dedicated agent that runs the tests, given the expected result,
on a cheaper model, and returns a short verdict to the executor.

- **It may not be callable at all.** The executor is itself a subagent, with `tools: Read,
  Write, Edit, Bash` and no `Agent`. The runtime documentation known at the time says a
  subagent does not spawn subagents. The local docs neither confirm nor deny it. If it is
  forbidden, only the main thread could call the runner, and the executor's write-and-test
  loop would break into one handback per test run.
- **The context it saves is the context option 1 saves for free.** The executor makes one
  turn per test run either way, `Bash` or `Agent`. Both return a short result in place of
  20–30k chars of log. The runner then adds its own cost: about 3–4 turns at 10–30k context
  per call, and four hook processes per spawn (`context subagent`, `tests agent-start`,
  `audit agent`, `tests agent-end`).

  | Per run | Haiku 4.5 | Sonnet 5 `low` |
  |---|---|---|
  | Per call (estimate) | ~USD 0.03 | ~USD 0.06 |
  | UC-005, 34 test runs | +USD 1.0 | +USD 1.9 |
  | UC-007, 7 test runs | +USD 0.2 | +USD 0.4 |

  Against option 1's ~USD 0.65 on UC-005, the runner removes the same log and costs more
  than it saves.
- **Model, if it is ever built.** Extraction only (test name, assertion, first application
  frame, root `Caused by`) needs no model: a `grep` does it, which is option 1. Diagnosis
  (finding the cause in a Spring or Testcontainers log) is the only reason to pay for a model,
  and then the choice is Sonnet 5 with `effort: low`, `omitClaudeMd: true` and `tools: Bash,
  Read`. Haiku is excluded on the same ground 0080/0081 keep it out of every class: a small
  model inventing a cause in a diagnostic costs more than it saves. Haiku 4.5 also has no
  `effort` level to tune (`@claude-help.md` § Effort).
- **Reopen only when** both hold: a subagent is confirmed to be able to call another in the
  runtime version in use, and an audit report after option 1 shows fix-and-rerun cycles that
  a better diagnosis would have avoided. One executor turn at ~290k context costs ~USD 0.06,
  so every cycle avoided would pay for several runner calls.

### Reopening option 2

*Note (0130):* reopened and approved. Issue #109 brought an executor run after option 1 at
USD 9.88 with a peak of 427,889. The three preconditions below are settled there, and the
second (the A/B) is a measurement of the next implement run, with a revert criterion.
@.claude/decisions/0130-executor-split-by-block-group-implement-in-clean-session.md.

Recorded as a candidate, not rejected. Reopen it when an audit report after option 1 still
shows an executor run above ~USD 5, or a peak context above ~300k. Before writing it:

1. Check how the `tests` Stop gate of `@.claude/decisions/0116-tests-defers-while-a-writer-subagent-runs.md`
   behaves between two groups, when no writer subagent runs.
2. Measure one implement run with and without the split, on the same spec shape, and compare
   cache reads.
3. Confirm that the audit no longer closes a run at a background agent's hand-back. Before
   @.claude/decisions/0119-audit-ignores-subagent-handback-executor-single-report.md, each group's `<agent-message>` would have closed the run, and
   the report would have held group 1 only.

### Option 4 — create nothing (score 3)

Keep the cost discipline that exists: one case per run, `/clear` between runs, no progress
messages. Rejected because the waste is measured, not anticipated — 191k chars of test logs
in one run is not a hypothesis.

## References

| Claim | Source |
|---|---|
| Content stays in context; every line is a recurring cost | `@claude-help.md` § Content lifecycle |
| Cache is per model; switching model or effort invalidates it; `5m` default, `1h` configurable, `experimental.cacheTtl` for a subagent | `@claude-help.md` § Prompt cache, § Choosing a model, agent fields table |
| Design skills pinned to `opus`; a wrong partial is the costliest defect | `@.claude/decisions/0081-skill-model-required-per-class.md` |
| Executor already names `./mvnw -q test-compile` per block and nothing for Block 4's test runs | `.claude/agents/java-spring-boot-developer.md` Blocks 1–J and Block 4 |
| Executor resumes from what is on disk | `.claude/agents/java-spring-boot-developer.md` § Resume |
| Tests Stop gate defers while a writer subagent runs | `@.claude/decisions/0116-tests-defers-while-a-writer-subagent-runs.md` |
| Measured cost, tokens, turns, tool output | `demo-clean-arch-single-module/.claude/audit-usage/` + session transcripts, 2026-09-17 → 2026-10-03 |
| Prices | `demo-clean-arch-single-module/.claude/audit-usage/pricing.json`, 2026-10-01 |

## Propagation

| File | Change |
|---|---|
| `.claude/agents/java-spring-boot-developer.md` | New § What enters the context — build output and reads, under § Execution rule: the test-run shape with the log under `$TMPDIR`, totals and coverage from the reports, failures from the reports and then the log's `[ERROR]`/`Caused by:` lines, no `\| tail -N`, each file read once, and a region reread before an `Edit` because the `format` hook may have rewritten the file. Block 4's `./mvnw verify` line points to it |
| `.claude/.ci/BashGuardTest.java` | 3 cases and an `agentBash` helper: the documented command and an absolute `/tmp` log are admitted for `java-spring-boot-developer`; a log under `target/` is refused |
| `docs/pt-br/07-ci-validate.md`, `docs/en/07-ci-validate.md` | The `BashGuardTest` row: 20 → 23 cases, and the executor cases named |

Goes to the generated project: **yes** — the agent is in `export.agents.include`, and the
`export` mode copies it whole. Nothing to add to the manifest. `BashGuardTest` stays here:
nothing under `.claude/.ci/` travels.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · hooks-cross-platform › guard bash refuses force pushes and holds shell writes to the phase` | The command the executor is now told to use is admitted by `guard bash` for its class. The `target/` alternative is refused, which is why the log lives under `$TMPDIR` | Green on the tree: 23 of 23. Red with `target/**` added to `agent_classes.executor.write_allow`: `executor: log under target/` fails by name |
| `validate › schema` | The agent's frontmatter, class sections and `export` entry | Green |

What the agent types is not testable in CI. The saving is measured on the next audit report of
an implement run: `./mvnw` characters and rereads per run, compared with the table in
§ Inside the executor.
