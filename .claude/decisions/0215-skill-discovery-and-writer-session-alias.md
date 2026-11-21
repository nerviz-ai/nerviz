# 0215 · Restore three skill descriptions and correlate writer lifecycle sessions across runtimes

- **Date:** 2026-11-21
- **Scenario:** "Planejar e corrigir os problemas CONFIRMADOS da validação de skills e subagentes, preservando compatibilidade com Claude Code e GitHub Copilot."
- **Decision:** option 1 - existing descriptions and shared boundary normalization
- **State:** approved by the user on 2026-11-21: "Aprovo a opção 1 — descrições e normalização compartilhada conforme o rascunho 0215". SKL-10A/B live require separate authorization; no commit or push.

## Reproduced on disk before classifying

At the investigation baseline, production was unchanged. The existing untracked runner, decision 0214 and
`docs/validation/` are prior work and must remain intact; no commit or push is authorized.

The user clarified during implementation: "os arquivos debaixo da pasta
docs/validation nao tem que ser versionados". Evidence remains local and
preserved; `.gitignore` excludes the whole directory. References below identify
local evidence, not files a future clone is guaranteed to contain.

| Finding | Evidence checked | Pre-fix verification |
|---|---|---|
| Three descriptions exceed the native loader's limit | `docs/validation/copilot-skills-subagents/2026-11-21/live/20261121T184411Z/skill-list-fixture.txt`, its final loader diagnostics; corresponding repo inventory | Installed Copilot CLI 1.0.114, `copilot skill list`, again rejects graphql-api-architect, http-client-architect and jobs-architect with `Skill description must be at most 1024 characters`. Folded descriptions currently measure 1101, 1042 and 1050 characters |
| Start and Stop resolve different session directories | The same run's `SKL-10A/attempt-1/hooks.jsonl`: `tests agent-start` gets `sessionId`, `tests agent-end` gets `session_id`, with matching agent IDs | `ArchHook.java:688-711` still reads only `session_id`; `testsState`, at lines 746-749, uses `unknown` when the session is missing |
| Normal completion leaves both markers | `SKL-10A/attempt-1/notes.txt` records `unknown/0be51636-51ed-4ebb-9f63-81582ced3a12` and `unknown/339d6657-85ae-4295-a3e9-866a5a681533` | Start/Stop both returned zero in the captures; zero did not prove cleanup |
| Cancellation was real, but cleanup/expiry was not certified | `SKL-10B/attempt-1/pre-cancel-action.txt`: an actual 488-byte file and its hash before cancellation; `live-results.md` and runner `skl10b` | A forced kill need not emit SubagentStop. Preserve TTL; a residual killed-agent marker can leave the repeat inconclusive until expiry is observed |

Tools observed for this verification: Copilot CLI 1.0.114, Claude Code 2.1.330, JDK 21.0.11.
`hook_build.javac_feature` is 21. This is the installed JDK, not a Spring
application version selected from memory.

Baseline commands run in the main thread, on unchanged production:

| Command | Result |
|---|---|
| `copilot skill list` (output filtered for the named loader errors) | The three descriptions rejected; command returned zero despite loader diagnostics |
| `java .claude\hooks\ArchHook.java build --verify` | exit 0; jar exactly matches the source under JDK 21 |
| `'{}' \| java .claude\hooks\ArchHook.java schema` | exit 0, no diagnostics |
| `claude plugin validate .claude\skills` | exit 0; `✔ Validation passed` |
| `java .claude\.ci\TestsDeferTest.java` | exit 0; all nine existing cases passed |

No fresh export or new live scenario has been run in this design phase.
The baseline passes do not contradict either defect: the existing lifecycle
test uses snake_case only, and plugin validation is structural, not discovery.
The three production descriptions and their entry rules were read directly.

## Interview

The detailed request supplies the axes; the interview confirmed that framing
and asked the open CI and live-authorization choices before proposing.

| Axis | Answer | Forms eliminated |
|---|---|---|
| 1 - symptom | Only the two defects above; SKL-03/04/07/08 are validation gaps | Speculative fallback, chaining or catalog fixes |
| 2, 3, 5 - trigger, frequency, nature | Existing native skill discovery and existing SubagentStart/SubagentStop lifecycle, in every applicable session; deterministic correction, not a new procedure | New skill, rule, agent or mod |
| 4, 8, 9 - territory, destination, integration | Both runtimes and fresh exports. Existing three SKILL.md descriptions, ArchHook.java/jar, alias data and the appropriate existing test | New ownership, new hook registration, permission changes |
| 6 - isolation | Preserve session/agent isolation and concurrent writers; tests use exclusive synthetic fixtures | Shared counters, scans/deletes across sessions, permanent new agent |
| 7, 10 - mandatory behavior, cost | User confirmed mandatory discovery and cleanup without relaxing guarantees. False green could conceal stale state affecting tests and sweep | Prose workaround, disabling tests/hooks, widened docs/notes territory |
| 14 - lifecycle | Start creates only an executor-class marker; Stop removes only that agent's marker in the same session; Stop gate reads the existing marker set | A new lifecycle event |
| 15, 16 - reaction, owner | Correct identity within existing `tests` lifecycle. Keep current exit behavior, expiry and `executorsOpen`; do not add a prohibition | Form 8, a second executable, context/audit owning cleanup |
| 17 - CI, user choice | "Regressões determinísticas em validate; discovery e SKL-10A/B apenas manuais" | New workflow/trigger, automatically charged live suite |
| Live authorization | "Pedir autorização separada depois das regressões verdes" | Treating approval of this design or 0214 as approval to run live |

Axes 11-13 (external integration/credentials) and 18-20 (mod interface/state)
do not apply. The existing CLI is sufficient for discovery; no MCP is needed.
State already has an owner and an expiry policy in decision 0116.

## Classification and invariant veto

Create no extension. Adjust the descriptions of three existing Form 1 skills
and the existing Form 7c executable's `tests` lifecycle. Keep Form 7a
registrations unchanged. Existing classes, territories, models, bodies,
frontmatter fields and export entries remain unchanged.

All eleven invariants were checked: no rule/procedure duplication (1-2), no new
boilerplate or command (3-4), no new agent (5), existing guarantees retained
(6), no blueprint change (7), no Spring/Java application version invented (8),
self-contained existing export (9), alias list owned once by extensions.json
(10), and no secrets or personal configuration written (11).

Normalizing every camelCase envelope in every mode is not proposed: only the
session field observed in the existing tests lifecycle has evidence for this
change. `agent_id` and `agent_type` already arrive in snake_case there.

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | Short descriptions + reuse the boundary alias-normalization algorithm for the tests session envelope | 9 | Approved and implemented |
| 2 | Same descriptions + a tests-local session reader using the same alias data | 8 | Not selected; second reading path unnecessary |
| 3 | Create nothing and leave production unchanged | 2 | Rejected as a corrective solution |

### Option 1 - canonical-input reuse in `.claude/hooks/ArchHook.java` (score 9)

**Motivator:** axes 7, 9 and 16; the existing reader already normalizes aliases
once before handing a payload to the modes that own behavior.

**Pros:** one alias algorithm; canonical Claude payload remains byte-for-byte
unchanged; no new hooks/processes; tests marker writer, remover and Stop reader
see one session spelling. The guard's existing snake_case reads find the marker
in the correct directory without changing guard territory or sweep semantics.

**Cons:** extracting the small alias loop touches a write-payload helper even
though its output must remain identical; rerun ApplyPatchTest to prove that.
Native discovery remains a manual check by the user's CI choice.

**Points cut:** criterion 5, a small helper extraction and regression surface.
No invariant is strained.

**Visual:**

```text
extensions.json
  guard.tool_input_aliases ----+--> shared canonicalFields algorithm
  tests.input_aliases ---------+
                               |
write modes: canonicalInput ---+--> unchanged native/patch handling
tests: canonicalSessionInput --+--> agent-start / agent-end / Stop
                                    |
                                    +--> same testsState(session)/agent_id
                                         same executor classes and TTL
export --> same three skills + source/jar + alias data
```

**CI:** extend `.claude/.ci/TestsDeferTest.java`, already registered in
`.github/workflows/validate.yml`, job `hooks-cross-platform`, step
`tests defers while a writer subagent of the session runs, and only then`,
on Linux, macOS and Windows. Existing ApplyPatchTest protects the extracted
write-normalization path; existing `design` frontmatter schema and
`export-determinism` keep structural/export coverage. No workflow or generated
CI jobs/steps/triggers. The later approved addition below annotates an existing
workflow step and adds the description-length check. Native description
discovery is not proven by these CI checks: native
meta-repo/fresh-export discovery is a required manual acceptance check.

### Option 2 - tests-session reader in `.claude/hooks/ArchHook.java` (score 8)

**Motivator:** same axes; minimize changes to the existing write normalizer.

**Pros:** fewer changes outside tests; a helper used by Start, Stop and the
tests Stop gate reads canonical `session_id` first, then aliases from data.
Same descriptions, executor checks, agent-specific removal, TTL and export.

**Cons:** a second alias-reading path beside canonicalInput must preserve the
same canonical-precedence policy. Its three callers must remain consistent.
The entire envelope is not normalized once at the boundary.

**Points cut:** criteria 5 and 6, parallel alias-reader maintenance and less
reuse of the normalization precedent. No invariant is violated.

**Visual:** `tests Start/Stop/gate -> testsSession(in) -> tests.input_aliases
-> existing session/agent markers`; write modes' canonicalInput unchanged.

**CI:** the same TestsDeferTest cases in the same `validate` job and step,
existing structural/export checks, and the same required manual native
discovery. No workflow change and no automatic SKL-10A/B.

### Option 3 - create nothing (score 2)

**Motivator:** cheapest hypothesis and strict avoidance of speculative fixes.

**Pros:** no changed production bytes, new processes or maintenance.

**Cons:** the three skills remain rejected and normal completion keeps
miskeyed markers. This does not satisfy the confirmed request or repair the
existing mandatory lifecycle guarantee. Documenting a defect is not a fix.

**Score:** 2; fails fit/enforcement for this request, not a viable correction.

**Visual:** `current discovery -> rejection; current Start -> unknown;
current Stop -> session directory -> marker survives`.

**CI:** existing nine TestsDeferTest cases still pass; no test added, so the
mixed-format failure remains unguarded. Manual evidence already disproves
acceptance. No claim of correction is possible.

## Description design - identical for options 1 and 2

Replace only each folded `description: >` scalar, keeping the same field shape.
The following text is the approved, installed content.
Keep all existing entry rules and bodies verbatim. Validate the folded length
before installation, then verify actual discovery, not just the count.

### graphql-api-architect

```text
Designs the schema-first inbound GraphQL adapter of an already-modeled use case on the servlet stack into `31-graphql.md`: root fields/ports, nullability, mutation `userErrors`, Relay pagination, batching, errors, field access, personal data, depth/complexity/timeout/batch-size limits, introspection, GraphiQL, CORS, caller quotas and concurrency caps. Use when the request involves GraphQL, a schema or `.graphqls` file, a query or mutation, a resolver, `@QueryMapping`, `@BatchMapping`, DataLoader, N+1 in GraphQL, a connection or cursor, GraphQL errors, or a rate limit on the GraphQL endpoint. Piece of the `/new-feature` pipeline: requires `10-domain.md` and a `00-use-case.md` whose `Trigger` names a GraphQL adapter in the given folder - and stops without them.
```

### http-client-architect

```text
Designs an already-modeled use case's outbound HTTP adapter into `28-http-client.md`: @HttpExchange/RestClient, OpenAPI Generator, WebClient streams, legacy OpenFeign; Apache HttpClient 5/JDK HttpClient/Reactor Netty pools, timeouts, retry allowlist/one retrying layer, circuit breaker, bulkhead, OAuth2 client credentials, token relay, API key, Basic, mutual TLS, HMAC, failure translation and WireMock cases. Servlet applications only. Use when the request involves calling an external API, a third-party or partner service, a payment gateway, a REST client, an HTTP client, RestClient, RestTemplate, WebClient, Feign, @HttpExchange, a timeout or retry on a remote call, a circuit breaker, or "the provider is down". Piece of the `/new-feature` pipeline: requires `10-domain.md` in the given folder with a port of kind `external HTTP`, and stops without it.
```

### jobs-architect

```text
Designs scheduled/background jobs into `35-jobs.md`: @Scheduled, ShedLock, Quartz, Spring Batch, db-scheduler, JobRunr; cadence, replica coordination, overlap, missed runs, on/off and metrics. Owns the outbox relay schedule and prune job. Use when the request involves a cron or recurring job, a scheduled task, a batch run, a background job, ShedLock, Quartz, Spring Batch, a job running twice across replicas, catch-up after downtime, or table pruning/retention. Piece of the `/new-feature` pipeline: requires `00-use-case.md` in the given folder plus a schedule trigger, a Form B outbox in `25-messaging.md`, reconciliation asked by `28-http-client.md`, a search index rebuild asked by `23-search.md`, a document backfill asked by `20-persistence.md`, audit history to prune (an `Audit` row naming `history`), or a deferred scheduled job - and stops without one of them.
```

## Option 1 implementation boundary

Add only this alias data under the existing `tests` object:

```json
"input_aliases": {
  "session_id": ["sessionId"]
}
```

The accompanying `$comment_input_aliases` names the captured Copilot Start,
Claude snake_case, canonical precedence, tests-only normalization and this
decision. No alias list in Java. No new general tool-envelope translations.

Extract canonicalInput's existing map alias loop into `canonicalFields`:
canonical key wins by `containsKey`; use the first present alias; remove only
the selected alias from a copy; return the original map when unchanged.
The existing canonicalInput keeps its guard.tool_input_aliases data source,
tool_input handling, string-patch path and byte-for-byte unchanged return.

Add `canonicalSessionInput(stdin)` using that algorithm on the root envelope
and `tests.input_aliases`. Invoke it once in main for mode `tests`, before
dispatch. No change to testsAgentStart, testsAgentEnd, testsState, safeName,
executorsOpen, agent classes or TTL. Blank/missing input retains today's
behavior; when the canonical key exists its value is not silently replaced
from an alias. A genuinely absent session still has today's fallback;
this decision does not redefine that policy.

Javadoc rationale to travel with the helper:

> This adjusts the existing Form 7c tests lifecycle, invoked at SubagentStart,
> SubagentStop and Stop. The observed Copilot Start session alias must resolve
> to the same per-session marker as Claude's canonical payload, without changing
> who writes or how long a marker lives. A tests-local alias reader was rejected
> in favor of the existing normalization algorithm, with aliases owned by data.

### Exact approved Java changes

In main, after the existing canonicalInput call/filter and before HOOK_EVENT:

```java
if ("tests".equals(mode)) stdin = canonicalSessionInput(stdin);
```

Replace only the alias-copy loop inside canonicalInput with:

```java
Map<String, Object> fixed = canonicalFields(ti, aliases);
if (fixed == ti) return stdin;
Map<String, Object> out = new LinkedHashMap<>(env);
out.put("tool_input", fixed);
return jsonOf(out);
```

Move the unchanged loop into this helper (same canonical precedence and
selected-alias removal), next to canonicalInput:

```java
static Map<String, Object> canonicalFields(Map<String, Object> input,
        Map<String, Object> aliases) {
    Map<String, Object> fixed = new LinkedHashMap<>(input);
    boolean changed = false;
    for (Map.Entry<String, Object> a : aliases.entrySet()) {
        if (input.containsKey(a.getKey())) continue;
        for (String alias : asStrList(a.getValue())) {
            if (input.containsKey(alias)) {
                fixed.remove(alias);
                fixed.put(a.getKey(), input.get(alias));
                changed = true;
                break;
            }
        }
    }
    return changed ? fixed : input;
}
```

Add this tests-only boundary translator with the Javadoc rationale above:

```java
static String canonicalSessionInput(String stdin) {
    if (stdin == null || stdin.isBlank()) return stdin;
    Map<String, Object> env = asMap(Json.parse(stdin));
    if (env == null) return stdin;
    Map<String, Object> sch = asMap(Json.parse(readOrNull(ROOT.resolve(SCHEMA_FILE))));
    Map<String, Object> aliases = asMap(get(sch, "tests", "input_aliases"));
    if (aliases == null) return stdin;
    Map<String, Object> fixed = canonicalFields(env, aliases);
    return fixed == env ? stdin : jsonOf(fixed);
}
```

Use `-Darchhook.test.jar=<absolute-jar-path>` as the narrow TestsDeferTest-only
test-target override, defaulting to its existing committed jar path.
This is test infrastructure, not a production switch. Keep original test cases
and add the designed observations; do not require the agent to invent another
normalization strategy. The test may improve cleanup of its own fixture paths
as required by the new red run, never clean shared temp directories.

## Regression cases and falsifiers

Extend the existing TestsDeferTest, not a new production hook or test program.
Use unique synthetic session AND agent IDs. Inspect actual marker paths and
wrapper calls, not exit zero alone. Clean only the paths created by the test,
including its own uniquely named `unknown/<agent-id>` if old code creates it;
never delete shared unknown state or another session's directory.

| Case | Required observation | Defect that must fail it |
|---|---|---|
| Copilot Start camelCase + Stop snake_case | Start creates session/A, never unknown/A; gate defers; Stop removes session/A; gate calls wrapper again | Old snake_case-only reader writes unknown/A and gate fails to defer; the path assertion names the miskeyed marker |
| Claude Start/Stop snake_case | Same path, deferral and deletion; canonical payload behavior unchanged | Normalizer ignoring canonical field or clearing wrong ID |
| Two interleaved IDs | Start A, Start B, Stop A leaves B and gate deferred; Stop B leaves neither, gate calls wrapper | Shared counter or directory-wide deletion erases B at Stop A |
| Session isolation | Same agent ID in two different sessions; Stop in one leaves the other's marker | Search-by-agent or cross-session cleanup deletes the wrong marker |
| Non-executor and internal Stop | Reader Start makes neither session nor unknown marker; its Stop leaves active writer A untouched | Treating every agent as writer or deleting any active marker |
| Canonical precedence and alias as data | Both keys: canonical session wins; in fixture-only schema, changing alias spelling changes what is recognized | Hardcoded sessionId reader or alias replacing a present canonical field |
| Expiration retained | Existing cap-zero case still calls wrapper; assert expired own marker is removed | Removing TTL check or silently preserving expired state |

Existing group handover, unknown-agent and other-session cases remain.
ApplyPatchTest must pass after option 1's extraction, including the native
Edit/Write aliases and patch-string cases from 0213/0169.

**Red then green:** preserve the old jar outside production in a uniquely
owned fixture. Run the extended lifecycle test against that old jar before
accepting green on the rebuilt jar, through a narrowly scoped test-target
override if needed. Expected red is the named mixed-session marker assertion,
not compilation, missing tools, permissions or schema staleness. Never revert
or inject defects into the user's production worktree for this proof.
The current loader rejection supplies the description red; capture it in new
evidence, then require discovery of all three after shortening.

## Verification plan after option approval

1. Review current status; preserve all prior evidence. Complete exact approved
   Java text in this record, then delegate the bounded implementation to one
   general-purpose Sonnet agent per 0191, no commit. Main thread owns the record,
   full hook diff review and independent verification.
2. Extend TestsDeferTest and prove red against the old jar. Install approved
   descriptions and Java/data changes, rebuild with the installed JDK 21, then
   prove green. Preserve both results in a NEW evidence directory.
3. Main-thread commands:

```powershell
java .claude\hooks\ArchHook.java build
java .claude\hooks\ArchHook.java build --verify
'{}' | java .claude\hooks\ArchHook.java schema
claude plugin validate .claude\skills
java .claude\hooks\ArchHook.java doctor
java .claude\.ci\TestsDeferTest.java
java .claude\.ci\ApplyPatchTest.java
java .claude\.ci\SweepTest.java
java .claude\.ci\AgentTerritoryTest.java
git --no-pager diff --check
copilot skill list
```

The smallest extra checks protect option 1's shared reader, 0210's marker
consumer and agent territories. Do not run Maven here. Manually replay Start
and Stop through the rebuilt jar with synthetic mixed-format payloads and
inspect the paths. Read doctor Hooks against the unchanged registration count.

4. Create an exclusive fresh export through the rebuilt jar with blueprint
   hexagonal, no application build claim or copied user code. In its directory,
   run `copilot skill list`, its own jar schema and `claude plugin validate
   .claude\skills`. Require the three names actually discovered and no loader
   rejection. Preserve full native output, versions and artifact hashes in the
   new evidence directory; counts/grep alone are not discovery. No workflow
   change, no copied `.claude/.ci/` in generated CI.
5. Verify the already-corrected runner locally:

```powershell
java .claude\.ci\CopilotSkillsLiveTest.java --selftest
java .claude\.ci\CopilotSkillsLiveTest.java --selftest-red
```

Expected selftest-red is nonzero with named classifier failures. These are
runner checks, not production lifecycle red/green proof.
6. Stop and request separate live authorization after deterministic green.
   Only then create a NEW existing evidence directory (never reuse the old
   2026-11-21 live captures) and run:

```powershell
java .claude\.ci\CopilotSkillsLiveTest.java <new-existing-evidence-directory> --from SKL-10A
```

Require real created bytes/hash, agent identity and Start/Stop/action ordering,
same root conversation UUID before/after resume, the next forbidden attempt
denied with reason and absent file, and correct per-agent cleanup. No approval
from CLI exit zero. An agent killed without Stop remains subject to the
unchanged 120-minute TTL; do not erase its marker to force live green. If expiry
is not observed, report that gap and leave SKL-10B inconclusive as appropriate.
7. Report tests actually performed separately from gaps; do not certify all
skills/agents or Claude Code live support from plugin validate. SKL-03/04/07/08
remain out of corrective scope without new reproduction.

## References

| Claim or design constraint | Concrete source |
|---|---|
| 0214 approves only the experiment | `.claude/decisions/0214-copilot-skills-and-subagents-live-validation.md`, State, Interview Findings, implementation contract |
| Discovery rejection and lifecycle mismatch | `docs/validation/copilot-skills-subagents/2026-11-21/live/20261121T184411Z/skill-list-fixture.txt`; `SKL-10A/attempt-1/hooks.jsonl` and `notes.txt`; current ArchHook.java:688-711,746-749 |
| Existing canonical alias reader and its scope | `.claude/hooks/ArchHook.java:99-106,11630-11662`; `.claude/schemas/extensions.json:870-877`; decision 0213, option 1 Aliases |
| Translate at the boundary, never duplicate behavior | `.claude/decisions/0169-apply-patch-payload-in-write-hooks.md`, option 1; `CLAUDE.md` invariants 2,10 |
| Marker per agent/session, executor-only, stale cap; do not piggyback context/audit or remove gate | `.claude/decisions/0116-tests-defers-while-a-writer-subagent-runs.md`, approved option 1 and rejected options 2-5 |
| Sweep also consumes the existing marker; preserve territory | `.claude/decisions/0210-guard-sweep-judges-executor-writes-by-agent-class.md`, approved shape and accepted gaps |
| Pipeline descriptions must keep prerequisite signal | `.claude/decisions/0007-pipeline-skills-invocation.md`, option 1 and What doesn't change; the three SKILL.md entry rules |
| Long listing descriptions already flagged, not approval for a broad cleanup | `.claude/lessons-learned/harness-001.md`, section 2; its Next concerns routing, outside this request |
| Test the jar and preserve export self-containment | `.claude/decisions/0084-ci-covers-jar-and-post-0075-guards.md`; `CLAUDE.md` invariant 9 |
| Exact designed handoff and main-thread verification | `.claude/decisions/0191-architect-designer-delegates-phase-4-to-sonnet.md`; architect-designer Phase 4 step 8 |
| Actual CI owner and existing test limitation | `.github/workflows/validate.yml:69-100`; `.claude/.ci/TestsDeferTest.java`; architect-designer `references/ci-coverage.md` |
| Native discovery command, not a model prompt | Installed Copilot CLI 1.0.114 documentation `cli:copilot skill list`; current `copilot skill list` output |
| Structural validate cannot certify runtime guarantees | `docs/pt-br/11-pitfalls.md`, Frontmatter e validação; `live-results.md`, Verificações e limites |
| Alias/header knowledge in one data owner; guarantees retained | `CLAUDE.md` invariants 6,10; `.claude/schemas/extensions.json`, tests and guard |

## Propagation

Implemented and reviewed in the main thread:

| File | Change performed |
|---|---|
| `.claude/skills/graphql-api-architect/SKILL.md` | Description only, above |
| `.claude/skills/http-client-architect/SKILL.md` | Description only, above |
| `.claude/skills/jobs-architect/SKILL.md` | Description only, above |
| `.claude/hooks/ArchHook.java` | Existing normalization/lifecycle adjustment for the selected option |
| `.claude/hooks/ArchHook.jar` | Rebuild under pinned JDK 21 and build --verify |
| `.claude/schemas/extensions.json` | tests.input_aliases and types.skill.max_description_chars with owner comments; no class/territory/TTL change |
| `.claude/.ci/TestsDeferTest.java` | Cases above, old-jar red and rebuilt-jar green |
| `.claude/decisions/0215-skill-discovery-and-writer-session-alias.md` | Main-thread exact approved diff, authorization, verification and final state |
| `.gitignore` | Exclude docs/validation per the user's clarification; preserve local files |
| `.claude/.ci/FrontmatterYamlTest.java` | Data-driven length refusals, exact-cap cases, old-jar override and exit/diagnostic assertions |
| `.claude/skills/claude-code-architect-designer/SKILL.md` | Derived maximum and preservation of trigger/prerequisite signals |
| `.claude/skills/claude-code-architect-designer/templates/SKILL.md.example` and `SKILL.command.md.example` | Derived 1024-character guidance in both skill exemplars |
| `.claude/skills/claude-code-architect-designer/references/frontmatter-fields.md` | Description compatibility limit and its data owner |
| `.github/workflows/validate.yml` | Existing FrontmatterYamlTest step comment; no new step or trigger |
| `docs/en/07-ci-validate.md` and `docs/pt-br/07-ci-validate.md` | Existing FrontmatterYamlTest coverage rows |
| `docs/validation/copilot-skills-subagents/2026-11-21-fix-0215/` | New local verification report and logs; ignored by git, all old evidence preserved |

No new routing/class/export entries: all production pieces already exist and
travel through export. No settings, permission, workflow behavior, generated CI, catalog,
fallback, chaining, rule or blueprint changes. The runner is prior work, not
a production repair target. Decision records and tests do not travel.

## CI coverage

The existing `validate` workflow runs on PRs and pushes to main without path
filters. Its `hooks-cross-platform` matrix already runs TestsDeferTest and
FrontmatterYamlTest on Linux, macOS and Windows; both programs were extended,
not registered a second time. Its `design` schema step now rejects oversized
skill descriptions too. The schema check travels with the hook/data into a
generated project's existing doctor gate; `.claude/.ci/` does not travel.

Main-thread results below are local Windows verification of those commands,
not a claim that GitHub Actions or the other OSes have run this patch:

| Command/check | Green | Preserved pre-fix jar red |
|---|---|---|
| `java .claude\.ci\TestsDeferTest.java` | exit 0, 17 cases | exit 1 expected, four named semantic failures |
| `java .claude\.ci\FrontmatterYamlTest.java` | exit 0, existing YAML and new length cases | exit 1 expected, six missing length-diagnostic failures |
| `java .claude\hooks\ArchHook.java build` and `build --verify` | exit 0 under JDK 21; committed-jar path matches source | Not a red-proof target |
| `schema`, ApplyPatchTest, SweepTest and AgentTerritoryTest | exit 0 | Not injected |

Red programs use `-Darchhook.test.jar=<preserved-old-jar>` outside production.
Oversized-fixture checks require the specific length diagnostic and hook exit
2; exact-cap checks require the partial fixture's known class diagnostic and
exit 2, with no length diagnostic, rather than accepting a failed launcher.

## Manual acceptance completed and remaining gaps

Native `copilot skill list` discovered all three skills without loader rejection
in the meta-repository and in a new 364-file hexagonal export. Their descriptions
measure 769, 859 and 873 characters. The fresh export's own jar schema passed.
`claude plugin validate .claude\skills` passed in both locations, with the
unaltered output `✔ Validation passed`. Main-thread doctor reports unchanged
`Hooks: 20 registrations across 4 events`.

A separate manual camelCase Start/snake_case Stop replay through the fresh
export's jar created the unique session/agent marker, never an unknown marker,
and removed only that marker on Stop. Its owned empty directory was cleaned.
The deterministic test additionally proves canonical Claude spelling,
interleaved agents, session isolation, non-executors, alias precedence/data
and the retained expiry behavior.

Exported source/jar hashes match the source checkout. Skill frontmatter and
the runtime types/tests/guard/classes/JDK data match too. Schema and full skill
file hashes need not match: the existing exporter strips meta-repository
decision citations from comments and bodies; that expected transformation was
checked, not treated as a corrective change.

Native discovery, plugin validation and SKL-10A/B remain manual. SKL-10A/B have
not been repeated and require separate authorization after this deterministic
green. Asked separately after green, the user chose "Adiar execução live";
the repeat is deferred, not accepted or implicitly authorized.
Runner selftests passed 106 checks; selftest-red failed as expected with
13 named classifier failures in 85 checks. Those are runner evidence, not live
or production lifecycle acceptance. SKL-03/04/07/08 remain validation gaps;
structural plugin validation does not certify complete Claude Code live support.

## Approved addition - prevent oversized descriptions in CI

During implementation the user explicitly added: "adicione entao a skill a
todos templates das skills o numero maximo de 1024 caracetres" and "e tambem
adicione esse teste no ci". This approves the deterministic description-length
check, not automatic native discovery or SKL-10A/B live. The previous manual
discovery choice remains; the length regression is no longer a CI gap.

Adjust the existing schema mechanism, not a new hook or workflow. Single data
owner: `types.skill.max_description_chars: 1024` in extensions.json. The
architect-designer skill, its two SKILL exemplars (the complete inventory) and
its frontmatter reference show this derived maximum, preserving trigger and
prerequisite guidance. No new frontmatter field is introduced.

Exact Java addition in checkFrontmatter, before its enum check/early return:

```java
if ("skill".equals(type)) {
    long cap = num(t.get("max_description_chars"));
    String description = fpText(content, "description");
    if (cap > 0 && description.length() > cap) {
        errors.add("  " + rel + " — `description` is " + description.length()
                + " characters; maximum is " + cap
                + " (types.skill.max_description_chars). Shorten it while preserving"
                + " triggers and prerequisites; move detail to the skill body");
    }
}
```

Use the existing scalar reader, not a new YAML parser. Extend
FrontmatterYamlTest with named cases asserting the new diagnostic and exit 2
for oversized plain, quoted and folded descriptions; exactly-at-limit accepted
by this check; a fixture-only smaller cap proves the limit comes from data.
Check folded multi-line values, including CRLF. Existing unrelated body/class
diagnostics in a partial skill fixture cannot be mistaken for length success
or failure: assert the length diagnostic specifically, as the existing YAML
cases assert their specific diagnostic.

Red proof: extended FrontmatterYamlTest against the preserved pre-fix jar,
using the same test-only archhook.test.jar property; require the missing
description-limit diagnostic to fail by name, not missing data or compilation.
Green: rebuilt jar with the new schema check.

Already registered in validate -> hooks-cross-platform -> `schema refuses
frontmatter a strict YAML parser refuses`, all three OSes; extend that existing
test and annotate its existing workflow step rather than add a duplicate
runner. Update the corresponding coverage rows in docs/en/07-ci-validate.md
and docs/pt-br/07-ci-validate.md. The existing design schema step will also
refuse every oversized skill in the repository, and generated doctor gate
inherits the check through source/jar/schema export.

Additional propagation: architect-designer SKILL.md, both SKILL*.example
templates, references/frontmatter-fields.md, FrontmatterYamlTest.java, the
existing validate.yml step comment and the two CI coverage docs. Source,
rebuilt jar and extensions.json were already in this option's territory.
No new settings, permissions, runtime process, CI trigger or charged CLI run.
