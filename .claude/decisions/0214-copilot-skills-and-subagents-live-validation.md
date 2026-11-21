# 0214 · Measure skills and subagents with a manual live runner, without changing runtime behavior

- **Date:** 2026-11-21
- **Scenario:** "planejar e executar a validacao de skills e subagentes no Copilot CLI, com evidencias versionadas"
- **Decision:** option 1 — a manual Java live runner; no new runtime extension
- **State:** approved by the user on 2026-11-21: "Aprovo a opcao 1 — runner Java manual conforme a decisao 0214"

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | The local guide has ten unverified scenarios. Native discovery on CLI 1.0.114 rejects graphql-api-architect, http-client-architect and jobs-architect descriptions above 1024 characters. Six deterministic baselines pass. Neither fact proves live context or enforcement | Speculative changes to existing components |
| 2, 3, 5 — trigger, frequency, nature | A manual validation run in this branch, with a reproducible runner; no workflow or frequency changes | New automatically loaded skill, rule, hook or mod |
| 4, 8, 9 — territory, destination, integration | This meta-repository only: a CI-style Java runner and sanitized evidence. Read real export/schema/jar; do not change their behavior or existing components | Changes to production settings, agents, skills, rules, export or generated CI |
| 6 — isolation | Live CLI sessions run only in exclusive synthetic projects, never against the user's working project | New permanent agent; direct execution of probe prompts in the meta-repository |
| 7 — mandatoriness | Measure existing guarantees; do not introduce a new guarantee. Failures remain findings, not invitations to widen a territory | Forms 7 and 8 as a proposed correction |
| 10 — cost of error | A false green would certify an absent guarantee. Missing actions, skipped components and missing context are explicit gaps | Passing a scenario from the model's verbal answer alone |
| 11 — existing CLI | java, git and copilot already run the experiments | MCP |
| 17 — CI | User chose a manual reproducible runner now, no workflow or frequency changes. Existing copilot-live covers five other native-payload scenarios; validate covers deterministic baselines | Registering this new live suite in CI during this decision |
| Components | Real component bodies are not replaced; synthetic probes only complement them | Shortened bodies under production names as evidence of native component support |
| Findings | Record failures and limitations, do not fix behavior this round | Coupled compatibility correction |
| SKL-10 | Exercise normal completion and executor cancellation this round | Omitting cancellation silently |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | No new runtime extension; `.claude/.ci/CopilotSkillsLiveTest.java` and `docs/validation/copilot-skills-subagents/` | 9 | Approved |
| 2 | Create no runner or runtime extension; execute manual CLI commands and save the matrix and captures | 6 | Rejected: less reproducible |
| 3 | A new validation skill and/or enforcement hook | 3 | Rejected: no recurring procedure or new guarantee requested; speculative runtime cost |

### Option 1 — manual runner (score 9)

**Motivator:** axes 2, 3, 10 and 17; executable precedent in CopilotLiveTest.

**Pros:** repeatable commands; synthetic isolated projects; assertions combine
recorded actions with filesystem state; real failures and inconclusives remain visible.
No runtime component is added or translated for compatibility.

**Cons:** live model behavior costs credits and is nondeterministic; instrumentation
must be recorded explicitly; a smoke run of representatives is not validation of
every discovered component. Windows is the tested surface; other OSes remain untested.

**Points cut in the rubric:** maintenance of a second live runner (criterion 5).

**Visual:**

```text
manual java runner
  -> ArchHook.jar export -> exclusive synthetic fixture
  -> real copilot CLI -> unchanged real skills / agents
  -> existing guard / context / lifecycle registrations
  -> observer captures + filesystem / diff + usage
  -> sanitized evidence + requirement matrix
```

**CI:** no new CI step, explicitly chosen by the user. Six baseline commands
already run in validate. Existing copilot-live/copilot runs CopilotLiveTest, not
this suite. New runner selftest runs locally with positive and negative evidence
fixtures; live execution is manual and can legitimately return nonzero.

### Option 2 — manual captures only (score 6)

**Motivator:** existing CLI is sufficient; no new extension needed.

**Pros:** least code and no runtime context cost.

**Cons:** ten scenarios, cancellation, trace correlation and red-proof checks must
be repeated by hand. A verbal conclusion is easier to mistake for evidence.

**Points cut:** maintenance/reproducibility (5), incomplete repeatable validation
(7), and evidence error cost (4).

**Visual:** `manual CLI commands -> captures -> manually reviewed matrix`.

**CI:** existing deterministic tests only. No new step by user choice; live
captures cannot be reproduced by a single runner command.

### Option 3 — new skill or hook (score 3)

**Motivator:** none beyond this one validation run.

**Pros:** a new slash command could package the procedure.

**Cons:** anticipation, extra context and propagation; a new hook could change the
behavior being measured. Violates the mirror of invariant 6 if it enforces an
unobserved failure. Not a viable option.

**Visual:** `new runtime extension -> existing CLI`, an unnecessary layer.

**CI:** structure could be covered by schema, but structure is not this task's
missing runtime evidence. No such extension is to be implemented.

## Option 1 implementation contract

This section is the complete handoff; the implementing agent receives no implicit
conversation state. No commit, push, remote call through gh, dependency install,
workflow edit or production-behavior correction.

### Files and verbatim rationale

Create `.claude/.ci/CopilotSkillsLiveTest.java`, a single-file Java 21 program,
following the process/evidence shape of CopilotLiveTest, not its broad silent catch.
Use only the JDK, git and the installed Copilot CLI. Do not add a second production
hook file or a persistent synthetic skill/agent in `.claude/skills` or `.claude/agents`.

Header rationale, verbatim:

> Why this is a manual test runner: this run measures existing skills, agents and
> guarantees rather than adding a runtime extension. The user chose reproducible
> manual execution and no CI frequency change. A new validation skill or hook was
> rejected because it would add context or change the behavior being measured.

Its contract: preserve exported production skill/agent bodies and frontmatter
byte for byte; any observer is fixture-only and cannot grant permission, rewrite
context, repair an event alias or replace a production tool call. Report both the
original and instrumented registration shapes. A transparent observer may record
the original hook stdin/stdout/stderr/exit and forward these unchanged; failures
of the observer invalidate evidence, never produce a success fallback.
Component hashes must prove preservation. Live scenario prompts are public and
synthetic. Missing action means inconclusive, not approved.

Create `docs/validation/copilot-skills-subagents/README.md`, in Portuguese, with
scope, all ten scenarios, exact commands, repeat policy, privacy limits and source
references. Initial status: partial validation, never certified compatibility.
Create evidence under its `2026-11-21` subdirectory without overwriting the existing
six baseline logs and discovery.txt. Publish a result matrix and per-attempt
command/prompt, exit, duration, actions, hook results, diff and applicable model/
tool observations. Runtime/model is resolved by the installed CLI; do not impose
an invented model mapping. Model/tool evidence unavailable means an explicit gap.

The complete inventory is meta-repository discovery and exported-project discovery,
separately; every real exported skill/agent gets a row, including rejected,
unexercised and unsupported components. No unexercised component is called supported.
Representative exercises do not remove these gaps.

### Fixture, scope and lifecycle

Use a freshly created exclusive directory outside the meta-repository, as in the
existing live runner. Export through the committed ArchHook.jar with blueprint
`hexagonal` (two relevant modules: domain and application). The entire fixture is
synthetic; no user source, token, key, credentials or environment dump is supplied
to prompts or evidence. Only production harness components necessary for the test
are copied, as in the existing exported live tests; never read source code unrelated
to the fixture.

Provide synthetic root CLAUDE.md/AGENTS.md and per-module AGENTS.md with distinct
public markers and restrictions; module CLAUDE.md imports AGENTS.md as the real
template requires. Do not duplicate normative prose: reference exported norms.
Provide a parent use-case and domain partial plus an approved minimal consolidated
spec for UC-900-probe and a second target UC-901-other. Specs must satisfy the
blocks/checklist their exercised real procedures require. The fixture's data may
name public input markers; the delegating prompt must not supply the context marker
whose delivery is being tested.

Do not claim a generated application builds: this suite measures the harness.
If a real executor/installer refuses missing build prerequisites, record that
result and the blocked action, do not shorten its procedure or fabricate a wrapper.
If a minimal build is needed, use a synthetic build descriptor and resolve any
Spring Boot/Java version via Initializr, never memory. Do not execute /init-project,
git-publish or a real application feature in the meta-repository.

No real component is edited to introduce markers. For catalog dependency evidence,
append only a public synthetic marker to a fixture copy of the catalog section
that the existing subagent_context selects; record this as fixture data
instrumentation, not as unchanged catalog content. Observe the original selection
and emitted output; the delegate must perform an action using the marker without
having it in its delegation prompt. Neither direct `context subagent` replay nor
main-thread knowledge alone proves delivery.

Capture real CLI actions and hook input/output with a fixture-only Java observer.
Keep original event names, matchers, conditions, command arguments and shell semantics.
Observe both PascalCase registrations and camelCase events separately if needed;
do not add a missing production context registration to make delivery work.
Raw private logs stay local; only sanitized synthetic evidence travels to docs.
Replace user-home, fixture, repository and temp absolute paths by placeholders;
never publish login/account data, token-like strings or unrelated personal context.
Reject unsafe evidence publication rather than silently substituting a green result.

CLI launcher must support this machine's copilot.ps1 as well as normal executable
launchers. On Windows use the detected launcher path, not an assumed copilot.cmd.
Use documented prompt, trust, output/usage/share flags verified against installed
help. No gist, history-sync change or global settings change. Bound each scenario
to 180 seconds, one initial attempt; one retry only if an action was absent, with
both attempts retained. Never retry to erase an observed enforcement violation.
Persist evidence incrementally even on timeout or runner exception.

Normal sessions are independent; SKL-10 intentionally uses the same conversation
for handover. For forced cancellation, record the starting executor event, wait
for its actual first action, then terminate only the CLI process and its captured
descendant process IDs. Record forced process termination as the cancellation
variant tested, not interactive Escape. Resume that conversation if supported by
documented CLI options, and start the next agent. Verify no surviving owned process
and observe residual lifecycle/permission state. If resume/cancel cannot exercise
the expected event, label it inconclusive or not executed, never approved.
Temporary cleanup touches only this runner's explicitly resolved exclusive paths;
preserve failed-run evidence before deletion, never swallow cleanup errors.

### Scenario table and falsifiers

| ID | Real action / prompt objective | Assertion and what must fail the check |
|---|---|---|
| SKL-01 | Native skill discovery on meta-repo and export; invoke a real domain-modeling skill on the synthetic parent | Inventory rejection is failure for that component. Invocation requires recorded loading/action and a partial created by the real procedure; lack of invocation is inconclusive |
| SKL-02 | `/domain-modeling UC-900-probe` with another existing target | Correct target partial; other target unchanged; no literal `$ARGUMENTS` directory. A wrong target fails even if the CLI exits zero |
| SKL-03 | `/arch-doctor` on fixture | Trace each required injected command, exactly once, and compare report to actual recorded command output. Missing or repeated command fails/inconclusive as appropriate; replay alone never approves |
| SKL-04 | `/new-feature` for one bounded synthetic domain-only case, no remote/publish | Actual ordered required skill calls and artifacts; skipped step or only direct commands are not native chaining approval. Interviews unavailable are a blocked/inconclusive run, not a synthetic replacement of the orchestrator |
| SKL-05 | Plain prompt asks the model to invoke arch-doctor using the skill tool, without a slash | Actual automatic call denied by guard, with reason; distinguish native hiding, instruction refusal and file read. No attempted call is inconclusive for deterministic enforcement |
| SKL-06 | Ask real delegation to java-spring-boot-developer for approved synthetic spec, or the actual required installer when prerequisites call for it | Real custom-agent start/end, resolved tools/model and dependent fixture action. Main-thread substitute or a launch with no necessary context is not approval |
| SKL-07 | Real executor handles domain and application tasks relying on each module's distinct instruction marker | Record applicable context delivery/read and dependent artifact/action in each module. Prompt-provided markers, swapped marker or action without delivery cannot approve |
| SKL-08 | Start eligible java-spring-boot-developer and ineligible issue-verifier with synthetic claims | Original context selection/output plus a dependent action from the eligible delegate, and absence of catalog delivery to the other. Unconditional injection or no dependent eligible action fails/inconclusive |
| SKL-09 | Request actual agent native writes inside src and outside its class territory | Record both attempted writes, permitted file, denied file absent, reason and diff. Absence alone is inconclusive; forbidden file created is failure even if later reverted |
| SKL-10 | Normal completion then next delegate; separate forced-cancel/resume/next-delegate variant | Start/end/action order and before/after state, next legitimate action and attempted forbidden write. Residual writer marker, missing end or absent denial cannot be silently approved; actual leak fails |

Use complementary synthetic probes only when necessary to distinguish native
tool discovery from model refusal. Clearly separate their results from real
component results; do not broaden the supported set from a probe result.
The complete request is executed as an investigation, not a requirement to make
every scenario green by adapting the component.

### Selftest and red proof

Provide `--selftest`: no CLI, network, installation or production mutation.
Exercise the evidence classifier used by live results with positive and negative
synthetic records: missing action, wrong target, repeated injection, skipped stage,
wrong module marker, catalog delivered to ineligible agent, forbidden file created
and residual cancellation state. Assert the negative cases are not approved and
that actual violations remain failures even with CLI exit zero.
An explicit classifier mutation mode, or equivalent isolated red-proof fixture,
must produce exit nonzero and name the scenario when a missing action is incorrectly
accepted. The normal selftest must pass. Never inject a defect into this worktree's
production hook/schema for this proof.

### Propagation and verification commands

| File | Change |
|---|---|
| `.claude/.ci/CopilotSkillsLiveTest.java` | New manual live runner and local evidence-classifier selftest |
| `docs/validation/copilot-skills-subagents/README.md` | Scope, matrix, exact commands and limitations |
| `docs/validation/copilot-skills-subagents/2026-11-21/*` | Sanitized incremental live evidence and results; preserve baseline files |
| `.claude/decisions/0214-copilot-skills-and-subagents-live-validation.md` | Main thread owns approval, final propagation and verification outcome |

No generated-project propagation: tests and decisions do not travel.
No workflow or CI template edit: manual execution explicitly chosen.
No bilingual general compatibility-page edit or runtime behavior correction in
this investigation; source divergence is recorded in the new report.

Main-thread verification after reviewing the complete diff:

```powershell
java .claude\.ci\CopilotSkillsLiveTest.java --selftest
java .claude\.ci\CopilotSkillsLiveTest.java --selftest-red
java .claude\.ci\CopilotSkillsLiveTest.java docs\validation\copilot-skills-subagents\2026-11-21
claude plugin validate .claude\skills
java .claude\hooks\ArchHook.java schema
git --no-pager diff --check
```

Expected: selftest exit 0, red proof nonzero by scenario name. Live suite may
return nonzero for genuine failures/inconclusives; report exact observations and
unexecuted components rather than claiming completion of compatibility support.
Existing six baselines already passed on unchanged production code; do not repeat
unless a later change justifies it. Do not rebuild the jar: its source is unchanged.

## References

| Claim | Source |
|---|---|
| Manual skill calls require the actual slash in the prompt, not a verbal request to read the body | 0171, guard call; observed guard refusal in this session |
| Literal injection fallback is guided, not equivalent to native expansion | 0172; generated root.CLAUDE.md.example |
| Catalog eligibility comes from agent_classes, delivered at the original subagent start | 0077; SubagentContextTest |
| Completion and active writer markers affect the sweep | 0210; SweepTest, TestsDeferTest |
| Native Windows edit shapes and deny reason need live capture | 0213; CopilotLiveTest; copilot-live.yml |
| Earlier manual measurements explicitly distinguished not-run and adapted cases | lessons-learned-022 sections 1, 5; 13-github-copilot.md |
| CLI 1.0.114 now documents .claude/agents and .claude/rules discovery | Embedded CLI documentation topic:config#claude-code-plugins-skills-hooks-and-settings, consulted 2026-11-21; not a live context claim |
| No new extension when existing CLI solves the procedure | decision-matrix sections 2 and 2.1 |
| No speculative guarantee and no copied norms | CLAUDE.md invariants 2 and 6 |
| Tests are Java single-file programs; existing live CI is separate from deterministic tests | references/ci-coverage.md; validate.yml; copilot-live.yml |

## CI coverage

No new CI step by explicit user choice. An existing green CI run does not run
this new suite.

Main-thread checks of the first implementation, on 2026-11-21:

| Command | Observed result |
|---|---|
| `java .claude\.ci\CopilotSkillsLiveTest.java --selftest` | exit 0; 54 checks |
| `java .claude\.ci\CopilotSkillsLiveTest.java --selftest-red` | exit 1; 11 named failures out of 48 checks |
| `claude plugin validate .claude\skills` | Validation passed |
| `java .claude\hooks\ArchHook.java schema` | exit 0 |

The full implementation review nevertheless found incomplete positive evidence:
some classifiers could approve an artifact without actor/context/model attribution,
the inventory parser missed rejection bullets, and the process helper read output
before its timeout. The implementing agent must correct these runner defects and
extend the selftests before any live result is trusted. These are test-infrastructure
corrections, not a change to production compatibility or enforcement.
The second review removed temporal attribution of unnamed writes, restricted
model facts to named executor metadata, preserved observed failures when evidence
integrity is incomplete, published preparation/discovery errors and ensured process
cleanup even when the cancellation observer throws.

Final local checks by the main thread:

| Command | Observed result |
|---|---|
| `java .claude\.ci\CopilotSkillsLiveTest.java --selftest` | exit 0; 97 checks |
| `java .claude\.ci\CopilotSkillsLiveTest.java --selftest-red` | exit 1; 13 named failures out of 81 checks |

Logs: `docs/validation/copilot-skills-subagents/2026-11-21/runner-selftest*.txt`.
Manual live execution started with
`java .claude\.ci\CopilotSkillsLiveTest.java docs\validation\copilot-skills-subagents\2026-11-21`.
The first live run (`20261121T181357Z`) ended with exit 3 during SKL-07:
the collector attempted to decode compiled `.class` artifacts as UTF-8.
The partial matrix and stack trace were retained. This is a runner defect,
not a production compatibility failure.

The collector now retains binary/oversized artifacts by path and SHA-256.
The observed native `Agent` payload also proved that `agent_type` must precede
the display `name`. Duplicate stop registrations are deduplicated only with
the same explicit agent id and event timestamp. Focused regression checks
cover these defects.

| Command | Observed result after the live fixes |
|---|---|
| `java .claude\.ci\CopilotSkillsLiveTest.java --selftest` | exit 0; 101 checks |
| `java .claude\.ci\CopilotSkillsLiveTest.java --selftest-red` | exit 1; 13 named failures out of 83 checks |

Recovery uses `--from SKL-06`, a fresh fixture and a new evidence directory.
Earlier scenarios are explicitly not executed in this continuation; their
first-run outcomes remain separately versioned. This avoids spending more
credits rerunning unaffected successful skill scenarios. Each new run also
records the SHA-256 of the runner source.
The continuation (`20261121T182900Z`) ended with exit 1. It exposed residual
writer markers after normal stops: native Start carries `sessionId`, Stop
carries `session_id`; `testsAgentStart` writes to `unknown`, while
`testsAgentEnd` deletes under the session UUID (ArchHook.java:688-716).
This observed production symptom is recorded only, not corrected this round.

Two further runner defects were identified: `git clean` removed the empty
source directories prepared by the fixture, and `--trust-folder` is mutually
exclusive with `--session-id`/`--resume` in CLI 1.0.114. The fixture now restores
its original directories after every reset. Cancellation starts an ordinary
ephemerally trusted prompt, captures the CLI-issued root UUID and resumes that
UUID without `--trust-folder`; no global trust or account configuration changes.
It waits for an actual source-file effect, with pre-termination hash evidence,
instead of treating a PreToolUse admission as a completed write. Full context
stdout is preserved up to 200 KB for reproducible catalog evidence.

| Command | Observed result after the recovery fixes |
|---|---|
| `java .claude\.ci\CopilotSkillsLiveTest.java --selftest` | exit 0; 106 checks |
| `java .claude\.ci\CopilotSkillsLiveTest.java --selftest-red` | exit 1; 13 named failures out of 85 checks |

The affected SKL-07 onward scenarios were rerun with `--from SKL-07`.
The last run (`20261121T184411Z`) completed with exit 1: SKL-09 passed,
SKL-10A reproduced the residual-marker failure, and SKL-10B exercised forced
cancellation after an actual 488-byte source-file effect and resumed the same
root UUID. The next forbidden write was denied; residual markers kept that
variant inconclusive. No captured processes survived.

Consolidation across the explicitly cited runs: four passed, two failed and
five inconclusive scenarios (ten requirements, SKL-10 split into two).
All component hashes remained unchanged, apart from the approved data
adaptations. The source SHA-256 recorded by the final run matches the final
runner. Earlier logs are retained, including unsupported-flag errors.

Final report:
`docs/validation/copilot-skills-subagents/2026-11-21/live-results.md`.
This completes the approved investigation, not compatibility certification.
No production component or workflow was changed. The runner wrote no global
setting, and the meta-repository received no commit or push. The synthetic
fixture commits were local experiment data and were removed with their fixtures.
