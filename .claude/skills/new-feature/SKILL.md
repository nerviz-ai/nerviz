---
name: new-feature
description: Orchestrates the feature design pipeline — one use case per run, the design skills → an approved UC-NNN-spec.md for the executor; `/new-feature-implement` implements it
disable-model-invocation: true
argument-hint: "<feature description> | UC-NNN-slug of a draft case | empty to list"
model: opus
---

# `/new-feature` — Feature design orchestrator

## Why this is Form 2 (manual skill)

Axis 2 (manual trigger) + axis 5 (procedural) + axis 8 (both). Form 1 rejected — user
requested manual invocation. Subagent rejected — none of the three reasons apply
(context, tools, model). A fixed-order procedure rules out a rule and CLAUDE.md.

**Manual only.** `disable-model-invocation: true` hides this skill from the model: the
`Skill` tool can't call it, and trying is blocked. When a conversation reaches the point
of running it, print the exact command for the user to type — don't attempt the call.

---

Pinned to `opus`: consolidating the partials and settling their divergences is design, and the executor it chains declares its own model (`@.claude/decisions/0081-skill-model-required-per-class.md`).

## guardrail · Entry guardrail

**Runs:** self · **Gate:** stop

**Stops when:** input rejected? · **Says:** prints the error and usage; or lists the cases (empty)

Runs before any skill invocation, any write, and any question. Every check is a command;
the model doesn't interpret the input. **Read `references/entry-guardrail.md` first** — the
survey and classification commands, the error rule, and §§ 2–4 (worktree, project, disk) live
there, shared with `/new-feature-implement`. This section holds only what is this skill's own:
the history check and the input table.

### 1 · Input table — closed

Run the survey of `references/entry-guardrail.md` § 1.

**The disk is not the history.** A folder deleted from the working tree is invisible to
`find` and still in `HEAD`, so the next number looks free when it isn't:

```bash
git ls-tree -d --name-only HEAD docs/use-cases 2>/dev/null
```

A `UC-NNN` that `HEAD` has and the disk doesn't → **stop and report it**, before any
skill call. Two things can be true, and only the user knows which: the number was
recycled (the repository would end up with two different `UC-NNN`, and this run's commit
would delete one while adding the other), or approved work was deleted without being
committed. Name the folder, say which of the two it looks like, and ask. Don't infer the
next free number from the disk alone.

Same comparison for the backlog, and for the same reason — the entry of a deleted case
disappears with its folder, and a run that recreates it from scratch writes different
text nobody compares:

```bash
git diff --stat HEAD -- docs/use-cases/BACKLOG.md 2>/dev/null
```

Non-empty when a `UC-NNN` is missing from the disk → show the diff before
`use-case-design` writes a new entry.

Classify the argument with the commands of `references/entry-guardrail.md` § 1, then
evaluate in order and **stop at the first row that matches**:

| # | Input | Result |
|---|---|---|
| 1 | empty | ✅ list the survey above — each case with its `status` — and stop |
| 2 | `EXACT`, `FOLDER`, status `draft` or `(no spec)` | ✅ resume: skip `use-case-design`, generate only the missing partials, then consolidation |
| 3 | `EXACT`, `FOLDER`, status `approved` | ❌ `<UC-NNN-slug> is approved — implement it in a clean session` — plus the two lines `/clear` and `/new-feature-implement <UC-NNN-slug>`. This skill designs; it cannot forward, since `/new-feature-implement` is manual |
| 4 | `EXACT`, `FOLDER`, status `implemented` or `implemented-blocked` | ❌ `already implemented — describe the change as a new feature` |
| 5 | no `FOLDER`, and the argument's `UC-NNN` resolves to **exactly one** folder on disk | ❌ `<UC-NNN> is <real-slug>, status <status>` — plus the exact command for it: `/new-feature <real-slug>` for an open case, `/new-feature-implement <real-slug>` for an approved one. The case exists; the argument named it wrongly |
| 6 | `EXACT`, no `FOLDER`, number resolving to zero or several folders | ❌ `use case not found; to create one, describe the feature` |
| 7 | `UC_LIKE` but not `EXACT` (slug plus context, malformed slug, two slugs, a path) | ❌ `ambiguous argument` |
| 8 | free text, and the survey shows an open case | ❌ `<UC folder> is open — resume or approve it first` |
| 9 | free text, and no open case | ✅ new: pass the description **as is** to `use-case-design` |
| — | anything else | ❌ `unrecognized argument` |

Read `references/input-table-notes.md` now, whole, before printing any ❌ row: the error shape, and why rows 3 and 5 report and stop.

### 2 · Worktree, project, disk

`references/entry-guardrail.md` §§ 2–4, in that order, before any question or write.
`IGNORED` and the list of pre-existing changes travel into the `/git-publish` line § Approval prints.

---

## Procedure — 6 steps + consolidation

**Execution discipline** — the run's cost is turns × context size, not file size:

- **No progress messages** between steps or tools ("moving to step 3", "domain ok"). Each
  one is a turn that re-reads the whole conversation. The only report is § Final report.
- **Independent writes in one turn** — parallel tool calls, not one file per turn.
- **Dependency versions come from the build file.** Read `pom.xml` before resolving any
  version; no web search for a version the project already declares.
- A step's "Output" below is a check the run makes, not a message it prints.

### 1 · Use case (new case only)

**Runs:** skill:use-case-design · **Depends on:** guardrail · **Conditional:** yes

**Invoke** `use-case-design` via the Skill tool, with the user's description as is.

`use-case-design` owns the boundary, the number, and the slug. When the description
holds several use cases, it asks the user which one to design, designs that one, and
writes the rest to `docs/use-cases/BACKLOG.md`. This run continues with the one it
created — never with the others.

**Output:** `docs/use-cases/UC-NNN-<slug>/00-use-case.md` — its folder name is the
`UC-NNN-<slug>` every later step uses.

### short · Short path

**Runs:** self · **Depends on:** 1

**Skips when:** all nine criteria met? · **To:** approval · **Says:** writes UC-NNN-spec.md; from the short template

Checked right after step 1. A trivial case doesn't need five partials. Check all nine against `00-use-case.md`
and the approved specs:

| Criterion | Unmet when |
|---|---|
| Aggregate reused from an approved spec | the component table has a NEW aggregate or value object |
| No new table, column, or migration | the component table has a NEW or CHANGE migration or entity |
| No new exception | an error situation has no exception already named in an approved spec |
| No question asked to the user | `use-case-design` needed `AskUserQuestion` |
| No freshness tolerance | the `Freshness` row states a duration — a cache to design |
| No model and no semantic search | the case asks a model to classify, extract or moderate, or finds items by meaning — an AI port to design |
| No GraphQL operation | the `Trigger` names a GraphQL adapter — the short template has no GraphQL row |
| No search projection, no new document | the `Search index` row names a projection, or a `document (MongoDB)` aggregate gets a NEW or CHANGE document, collection or index — the short template has no row for either |
| No new audit level | the `Audit` row names `stamps` or `history` for an aggregate whose level is new or changes — stamps, history tables and the actor to design; the short template has no row for them |

All nine met → skip steps 2–6 and write `UC-NNN-spec.md` from
`templates/feature-spec-short.md.example`: every row names its source, an approved spec or
a rule. A row that needs judgment to fill means the case isn't trivial — discard the
short spec and take steps 2–6. One criterion unmet → steps 2–6.

### 2 · Domain

**Runs:** skill:domain-modeling · **Depends on:** 1, short

If `10-domain.md` is missing: **invoke** `/domain-modeling UC-NNN`.
If it exists: read, validate (four blocks: aggregate, VOs, invariants, ports).

**Output:** "✅ Domain ready" or a list of gaps.

### 3 · REST

**Runs:** skill:rest-api-architect · **Depends on:** 1, 2 · **Conditional:** yes

Read the closing phrase of `00-use-case.md`'s `Trigger` row. `inbound GraphQL adapter` alone →
skip this step. Any other phrase, or none (a case written before the phrase existed) → if
`30-rest.md` is missing: **invoke** `/rest-api-architect UC-NNN`.
If it exists: read, validate (eight blocks: resources, DTOs, errors, pagination, idempotency,
personal data, versioning, admission — `none` unless `00-use-case.md` has an `Admission` row).

**Output:** "✅ REST ready", "— skipped (GraphQL only)", or gaps.

### 3g · GraphQL

**Runs:** skill:graphql-api-architect · **Depends on:** 1, 2 · **Reads:** 3 · **Conditional:** yes

If the `Trigger` names `inbound GraphQL adapter` or `inbound REST and GraphQL adapters` and
`31-graphql.md` is missing: **invoke** `/graphql-api-architect UC-NNN`. No GraphQL adapter in the
phrase → skip this step. If `31-graphql.md` exists: read, validate (thirteen blocks: operations,
schema, inputs and payloads, pagination, batch loads, project setup, error map, personal data,
requirements to other partials, contract test cases, declared dependencies, configuration,
deferred).

Every list that grows is a connection with a ceiling, every child field that costs I/O is a batch
load, and the project setup is decided or inherited — a § 6 with neither is a gap. § 9 is what steps
2 (a batch port, through consolidation), 3b and 5 read; § 11 is the only list that entitles the
executor to add the GraphQL starter to `pom.xml`; § 10 is what step 6 turns into tests.

**Output:** "✅ GraphQL ready", "— skipped (no GraphQL trigger)", or gaps.

### 3b · Security

**Runs:** skill:security-architect · **Depends on:** 1, 2, 3, 3g · **Conditional:** yes

Read `00-use-case.md`'s `Access` row, and whether the project already has a filter chain
(`grep -rl "SecurityFilterChain" --include='*.java' src/main`). If `32-security.md` is missing,
**invoke** `/security-architect UC-NNN` when any holds:

| `Access` | Filter chain on disk | Why it runs |
|---|---|---|
| a role, a group, a scope, or the owner | any | this case's rules — and the setup when there is none |
| authenticated | none | first secured case: the project-wide setup |
| public | present | a `permitAll` needs its recorded reason |

Otherwise skip: public in an unsecured project, or authenticated behind a chain that already
denies by default — the spec's security block says which. A GraphQL trigger is not a skip when
`31-graphql.md` § 9 asks for a field-level row: a sensitive field narrower than its operation is
this case's rule whatever the `Access` row says. An `00-use-case.md` with an HTTP
trigger and no `Access` row (written before the row existed) is not a skip: invoke it, it asks.
If `32-security.md` exists: read, validate (eleven blocks: access per endpoint, mechanism,
authorities, project-wide setup, ownership, failure responses, schema requirements, declared
dependencies, configuration, contract test cases, deferred).

§ 7 is what step 5 reads; § 8 is the only list that entitles the executor to add a security
starter to `pom.xml`; § 10 is what step 6 turns into tests.

**Output:** "✅ Security ready", "— skipped (<which row>)", or gaps.

### 3c · Outbound HTTP

**Runs:** skill:http-client-architect · **Depends on:** 1, 2 · **Reads:** 3, 3b · **Conditional:** yes

Read `10-domain.md` § Ports. If it declares a port of kind `external HTTP` and
`28-http-client.md` is missing: **invoke** `/http-client-architect UC-NNN`. No such port → skip
this step. An output port with **no kind** is not a skip: stop and ask `domain-modeling` for it —
a kind left out is how an external call reaches the executor with nobody having decided its
timeout. A port of that kind whose `00-use-case.md` has no `External calls` row (written
before the row existed) is not a skip either: invoke it, it asks. If `28-http-client.md` exists:
read, validate (eleven blocks: operations, client, engine and pool, outbound authentication,
retry and resilience, project-wide setup, configuration, declared dependencies, contract test
cases, requirements to other partials, deferred).

§ 10 is what steps 4b and 5 read — the stored idempotency key, a reconciliation pass, the
security impact of the OAuth2 client starter; § 8 is the only list that entitles the executor to
add a client, engine or resilience dependency to `pom.xml`; § 9 is what step 6 turns into tests.

**Output:** "✅ Outbound HTTP ready", "— skipped (no external HTTP port)", or gaps.

### 3d · AI

**Runs:** skill:ai-architect · **Depends on:** 1, 2 · **Reads:** 3, 3b, 3c · **Conditional:** yes

Read `10-domain.md` § Ports. If it declares a port of kind `LLM` or `vector store` and
`29-ai.md` is missing: **invoke** `/ai-architect UC-NNN`. No such port → skip this step. A port
of kind `LLM` whose return type has no "could not decide" value, or no review state it leads to,
is not a skip: stop and ask `domain-modeling` for it — the adapter would have nowhere to put an
answer it cannot use. If `29-ai.md` exists: read, validate (twelve blocks: AI ports, provider and
models, prompts and output, data sent, vector store, failure and retry, observability, test
cases, requirements to other partials, declared dependencies, configuration, deferred).

Every timeout in § 6 is below the trigger's deadline, and every field of § 4 sent in clear names
its receiver. § 9 is what steps 4, 4b and 5 read — the store's migration, a re-index trigger, an
ingestion job; § 10 is the only list that entitles the executor to add a Spring AI dependency to
`pom.xml`; § 8 is what step 6 turns into tests.

**Output:** "✅ AI ready", "— skipped (no AI port)", or gaps.

### 3e · Search

**Runs:** skill:search-architect · **Depends on:** 1, 2 · **Conditional:** yes

Read `00-use-case.md`'s `Search index` row. If it names a projection and its engine and
`23-search.md` is missing: **invoke** `/search-architect UC-NNN`. `none`, or a spec older than the
row → skip this step. A row that names a projection but no engine is not a skip: stop and send the
case back to `use-case-design`, which asks the one factual question — this orchestrator never picks
an engine. If `23-search.md` exists: read, validate (eleven blocks: projection, index, queries,
writes, rebuild, lag, failure, requirements to other partials, declared dependencies,
configuration, deferred).

§ 6's lag is at most the case's `Freshness`; a larger one is a divergence, not a refresh to add.
§ 8 is what steps 4, 4b and 5 read — the indexer's consumer, the rebuild job, the source's version
and change event; § 9 is the only list that entitles the executor to add a search dependency to
`pom.xml`; § 3 and § 4 are what step 6 turns into tests.

**Output:** "✅ Search ready", "— skipped (no search index)", or gaps.

### 4 · Messaging

**Runs:** skill:messaging-architect · **Depends on:** 2 · **Conditional:** yes

Read `10-domain.md`'s Events block. If it names external (Kafka) delivery for the
event and `25-messaging.md` is missing: **invoke** `/messaging-architect UC-NNN`. If
the event is absent or stays in-process, skip this step — not every use case needs it.
If `25-messaging.md` exists: read, validate (nine blocks: topic and delivery, producer,
consumer and idempotency, retry/DLQ, configuration, schema requirements, declared
dependencies, personal data, deferred).

A missing § 6 is a gap, not an omission: it's the list step 5 reads. `none` is a valid
value there and means publication Form A with no consumer dedupe table to build. § 7 is
the same shape for build dependencies: it is the only list the executor may act on when it
writes `pom.xml`, so a missing one leaves a needed dependency with no owner
(lessons-learned-013 § 10). **This step is not the only check on it** — a run that reaches
consolidation without taking this step is exactly how two lists went missing and a spec was
approved anyway, so consolidation gates the same thing for every path.

**Output:** "✅ Messaging ready", "— skipped (no external delivery)", or gaps.

### 4b · Jobs

**Runs:** skill:jobs-architect · **Depends on:** 1, 3d, 3e, 4 · **Conditional:** yes

Invoke `/jobs-architect UC-NNN` if `35-jobs.md` is missing and at least one holds:
`25-messaging.md` § 2 chose **Form B**, `00-use-case.md`'s trigger is a schedule, a
recurring run, a batch or a background job, `28-http-client.md` § 10 asks for a reconciliation
pass, `29-ai.md` § 9 asks for an ingestion or re-index job, `23-search.md` § 8 asks for a rebuild job, `00-use-case.md`'s `Audit` row names `history` and the project has no history prune job yet, or a partial's `Deferred` block names a scheduled job. None holds → skip this step. If `35-jobs.md` exists: read, validate (nine blocks: jobs,
technology, coordination, execution guarantees, observability, schema requirements, declared
dependencies, configuration, deferred).

A Form B case with no `35-jobs.md` is a gap, not a skip: the relay would have no schedule and
the outbox no prune — the exact state lessons-learned-014 § 9 found. § 3's replica count and
§ 6's requirements are what step 5 reads; § 7 is the only list that entitles the executor to
add a scheduling dependency to `pom.xml`.

**Output:** "✅ Jobs ready", "— skipped (no scheduled work)", or gaps.

### 5 · Persistence

**Runs:** skill:persistence-architect · **Depends on:** 1, 2, 3, 3g, 3b, 3c, 3d, 3e, 4, 4b

If `20-persistence.md` is missing: **invoke** `/persistence-architect UC-NNN`. It reads
every schema requirement list in the same pass — `30-rest.md` block 4 (the idempotency
table among them) when step 3 ran, when step 3g ran `31-graphql.md` § 9 (one statement per batch
port, bounded by § 6's max batch, and the index each connection's order needs), when step 3b ran `32-security.md` § 7 (a credentials or API-key store),
when step 3c ran `28-http-client.md` § 10 (a stored idempotency key, an unknown-outcome state),
when step 3d ran `29-ai.md` § 9 (a vector store's table, its extensions and its index),
when step 3e ran `23-search.md` § 8 (the source's version and its change event in the outbox),
when step 4 ran `25-messaging.md` § 6 (the shared outbox table, the
dedupe table), and when step 4b ran `35-jobs.md` § 3 and § 6 (the replica count its claim
strategy must meet, the scheduling tables, the prune's bounded delete). If it exists: read, validate (seven blocks: schema, mapping, adapter and
ports, migrations, configuration, declared dependencies, deferred). § 6 is the persistence twin
of `25-messaging.md` § 7,
and the same rule holds: it is the only list that entitles the executor to touch `pom.xml` —
and consolidation checks it again, for the paths that never take this step.

**Output:** "✅ Persistence ready" or gaps.

### 5b · Cache

**Runs:** skill:cache-architect · **Depends on:** 1, 2, 5 · **Reads:** 3c, 3e, 4b · **Conditional:** yes

Invoke `/cache-architect UC-NNN` if `22-cache.md` is missing and `00-use-case.md`'s
`Freshness` row states a tolerance — a duration an answer may be old. The row reads `none`, or
the case predates the row → skip this step: no tolerance, no cache
(`@.claude/rules/caching.md` § Boundary). If `22-cache.md` exists: read, validate (ten blocks:
cached reads, provider, expiry and size, invalidation, failure and load, serialization,
observability, declared dependencies, configuration, deferred).

Every expiry in § 3 is at most the tolerance it serves — minus `23-search.md` § 6's lag when the
cached read is a search projection — and every writer of the cached data
has a § 4 row — a writer named in another approved case's Components table and missing from
§ 4 is a gap. § 8 is the only list that entitles the executor to add a cache dependency to
`pom.xml`; § 1 and § 4 are what step 6 turns into tests.

**Output:** "✅ Cache ready", "— skipped (no freshness tolerance)", or gaps.

### 6 · Tests

**Runs:** skill:test-architect · **Depends on:** 1, 2, 3, 3g, 3b, 3c, 3d, 3e, 4, 5, 5b

If `40-tests.md` is missing: **invoke** `/test-architect UC-NNN` (design mode).
If it exists: read, validate (five blocks: pyramid, cases, fixtures, coverage, test dependencies).
When step 3b ran, every case of `32-security.md` § 10 has a test row — a missing one is a gap.
When step 3g ran, every case of `31-graphql.md` § 10 has a test row at the level it names, and the
schema snapshot test exists — a missing one is a gap, and so is an access case placed in the
GraphQL slice, where no filter chain runs.
When step 3c ran, every case of `28-http-client.md` § 9 has a test row — a missing one is a gap.
When step 3d ran, every case of `29-ai.md` § 8 has a test row at the level it names — a missing
one is a gap, and so is any row that calls a real model.
When step 5b ran, every cached read of `22-cache.md` § 1 has three test rows — a second read
not reaching the source, an eviction by every writer of § 4 that goes through the port, and
two callers never sharing an entry when the key names the caller (`@.claude/rules/caching.md`
§ Tests). A missing one is a gap.

**Output:** "✅ Tests ready" or gaps.

### consolidation · Consolidation

**Runs:** self · **Depends on:** 1, 2, 5, 6, 3, 3g

Read `references/consolidation.md` now, whole, before writing `UC-NNN-spec.md`.

---

## End of flow

Every branch below ends in an explicit instruction. None of them runs git, and none invokes
`git-publish`: the final report prints its command for the person to type, after `/clear`.

**Design and implementation never share a session.** This skill designs; a run that
consolidated a spec ends at § Approval. An `approved` spec is implemented only by
`/new-feature-implement UC-NNN-<slug>`, typed after `/clear`. The executor never inherits the
conversation, but the orchestrator's thread does: every hand-back it receives, every group it
launches would otherwise run at the 250–370k context the design phase leaves behind — and so
would a chained `git-publish`, which is why the report prints it after `/clear` instead
(`@.claude/decisions/0130-executor-split-by-block-group-implement-in-clean-session.md`). The
second half of the pipeline lives in its own skill, so an implement run never loads this body
(`@.claude/decisions/0135-new-feature-split-spec-and-implement.md`).

### approval · Approval

**Runs:** self · **Depends on:** consolidation · **Gate:** ask, stop

**Stops when:** kept as draft? · **Says:** reports the spec path; resume: /new-feature UC-NNN-slug

Read `references/approval-and-report.md` now, whole, before asking for approval.

### report · Final report

**Runs:** self · **Depends on:** approval

**Hands over:** `/clear`, `/git-publish docs(UC-NNN-<slug>): …`, `/new-feature-implement UC-NNN-<slug>`

The report is the last message of the run and the only one; `references/approval-and-report.md`, already read, says what it carries.

---

## Spec lifecycle

`UC-NNN-spec.md` opens with a frontmatter carrying one field:

| `status:` | Written by | When |
|---|---|---|
| `draft` | this skill | at consolidation |
| `approved` | this skill | only after the user's explicit approval, § End of flow |
| `implemented` | `java-spring-boot-developer` | with a green build |
| `implemented-blocked` | `java-spring-boot-developer` | with a green build, when the run left an approved use case unreachable end to end — the code is on disk and verified, and a `Satisfied by` the spec named is not there yet |

A use case folder is **open** when it has no `UC-NNN-spec.md` yet, or its spec is `draft`.
`implemented-blocked` is **not** open: a case whose code is on disk, green and committed is not
work in progress, and its satisfier is usually a backlog row nobody is holding this run for.

**One `/new-feature` produces one use case.** The next one only starts once the previous
is `approved`, `implemented` or `implemented-blocked`.

**An approved spec is immutable.** Later cases read approved specs as a read-only
contract and reuse the aggregate already modeled. A change a new case needs in an
approved one goes into the `## Impact on approved use cases` section of the **new**
spec; the executor applies it in code. Don't edit the old spec — write the line in the
altered case's own `CHANGELOG.md` instead (consolidation step 2).

**A case can be nothing but changes.** Every component `CHANGE`, no new use-case class:
it still gets its own `UC-NNN`, and its slug names the capability added rather than the
trigger, which already belongs to the case being changed
(`@.claude/rules/naming.md` § Use case identifier).

Decide what the current case needs. Don't defer or anticipate a decision "for the future
case" — the future case decides it, in its own impact section.

---

## Contract

**Class:** orchestrator — the territory is `skill_classes.orchestrator` in
`@.claude/schemas/extensions.json` and `ArchHook.java guard` enforces it. A run writes under
`docs/` only; `ArchHook.java guard` also refuses a `Skill` call to a `build`-class skill while
this run is open.

**Owns:** the sequence, the input table, the spec lifecycle (`status:`) and `UC-NNN-spec.md`.
The use case number and slug are `use-case-design`'s.

**Never writes:** under `src/`, outside `docs/`, or through `git add`, `git commit` and `git push` —
neither does any skill it chains. `git-publish` is printed for the person to type.

**Delegates:** every partial under `docs/use-cases/UC-NNN-<slug>/` to the design skill each
step of § Procedure names on its `**Runs:**` line; the code to `/new-feature-implement`.

Full contract: `references/contract.md`

---

## References

- **D14** — ordered pipeline: use-case → domain → persistence → REST → tests → executor. Order
  of REST and persistence swapped by `0037`.
- **D17** — pipeline skills without `disable-model-invocation` (so `/new-feature` can call them).
- **D20** — `/new-feature` deferred; `java-spring-boot-developer` executor; scope: both.
- **D22** — `/new-feature` design (this record).
- **`@.claude/decisions/0032-messaging-architect-skill.md`** — `messaging-architect`
  chained as a conditional step, same pattern as `persistence-architect`/`rest-api-architect`.
- **`@.claude/decisions/0144-cache-architect-skill.md`** — `cache-architect` chained as step 5b,
  after persistence, only when the use case states a freshness tolerance.
- **`@.claude/decisions/0146-graphql-api-architect-skill.md`** — `graphql-api-architect` chained as
  step 3g, the REST step's pair, when the `Trigger` names a GraphQL adapter; step 3 skipped when it
  names GraphQL alone.
- **`@.claude/decisions/0130-executor-split-by-block-group-implement-in-clean-session.md`** and
  **`@.claude/decisions/0135-new-feature-split-spec-and-implement.md`** — design and implement
  never share a session; the implement flow is `/new-feature-implement`, a skill of its own.
- **`@.claude/decisions/0037-lessons-learned-005-remediation.md`** — closed input table,
  one use case per run, spec lifecycle, no `src/` and no git outside `git-publish`.
- **`@.claude/decisions/0182-git-publish-printed-not-chained-by-the-feature-flows.md`** — the
  flow prints `/git-publish` instead of invoking it.
- **Invariant 2** (`@CLAUDE.md`) — single owner. The orchestrator owns `UC-NNN-spec.md`;
  `use-case-design` owns number and slug.
- **Invariant 8** (`@CLAUDE.md`) — specs via skills; code via executor.

---
- **`templates/feature-spec.md.example`** — the shape of `UC-NNN-spec.md` (blocks, frontmatter `status:`, optional blocks); the short path uses `templates/feature-spec-short.md.example`.


## Entry into generated projects

Step 6.6 writes the skills into the project. `/new-feature` travels the same way — the user runs
`/new-feature <feature description>` to design a new feature.

No adaptation needed — skills already use relative paths.
