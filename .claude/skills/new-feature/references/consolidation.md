# Consolidation — `/new-feature`

Read whole before writing `UC-NNN-spec.md`. The step itself — its heading and field block —
stays in `SKILL.md`; this is its prose, moved out by
`@.claude/decisions/0196-new-feature-body-cut-quoted-globs-and-audit-effort.md`, unchanged.

## Contents

- Preconditions: which partials must exist
- 1 · Resolve divergences before consolidating — the precedence table, the stop-and-ask cases
- 2 · Consolidate into a single file, by reference — blocks, and every stop condition (errorCode, declared dependencies, Compose services, personal data, deferred rows, `CHANGELOG.md`, `permitAll`)
- 3 · Check whether the architecture tests can now be turned on

Runs after 1, 2, 5 and 6 ✅, and 3 or 3g; steps 3, 3g, 3b, 3c, 3d, 3e, 4, 4b and 5b are conditional.

If all specs that apply exist and validate (REST only when step 3 wasn't skipped, GraphQL only when
step 3g wasn't, security only when step 3b wasn't skipped,
outbound HTTP only when step 3c wasn't, AI only when step 3d wasn't, search only when step 3e wasn't, messaging only when step 4 wasn't, jobs only when step
4b wasn't, cache only when step 5b wasn't):

1. **Resolve divergences before consolidating.** The partials are written by different
   skills, and downstream corrects upstream: `30-rest.md` fixes the path and status
   that `00-use-case.md` had sketched, `20-persistence.md` fixes the key that the
   domain described in prose. Stacking both versions and explaining in a note which one
   wins pushes the work onto the reader — and the note ends up two hundred lines away
   from where it matters.

   Walk through every partial in the folder and, for each fact that appears in more than one with
   different values, apply this precedence:

   | Fact | Who wins |
   |---|---|
   | HTTP path, verb, status, body shape | `30-rest.md` — except the 401 and 403 rows, below |
   | GraphQL root fields and the port each calls, types, fields and nullability, inputs, payloads and `userErrors`, connections and their ceilings, batch loads, the GraphQL error map, the project's GraphQL limits | `31-graphql.md` — except who may read each field, below. A batch port it asked for becomes `10-domain.md`'s port by consolidation, with `31-graphql.md` as who asked; the batch statement and its index are `20-persistence.md`'s |
   | Who may call each endpoint, GraphQL root field and sensitive field, the authentication mechanism, the claims-to-authorities mapping, the `permitAll` list and its reasons, the 401 and 403 rows, CORS, OpenAPI and Actuator exposure, where the controller reads the actor from | `32-security.md`. The actor field, the owner field and the ownership invariant stay `10-domain.md`'s; an owner-only `Access` whose domain partial has no actor is not settled by precedence: **stop and ask** |
   | Table, column, key, index, migration | `20-persistence.md` — including every table or column another partial *asked for*: the requirement is born in `30-rest.md` block 4 or `25-messaging.md` § 6, the final form (name, type, nullability, index, migration) is always this one's |
   | Topic, serialization, delivery guarantee, consumer retry and DLQ | `25-messaging.md` |
   | Outbox table, its columns, the claim query, and the values the columns encode (batch size, backoff, attempt ceiling, retention window, the prune's statement) | `20-persistence.md` — the whole table is its territory. `25-messaging.md` declares the **guarantee** those values have to deliver, never the columns; a § 6 row naming columns is reported as a divergence and the guarantee is what carries over |
   | Client, engine and pool, timeouts, the retrying layer and its policy, breaker and bulkhead, outbound authentication, the failure table of each remote operation | `28-http-client.md`. The outbound port, its domain types, the idempotency key field and the unknown-outcome state stay `10-domain.md`'s; the column and the reconciliation schedule are persistence's and jobs's, as for every requirement |
   | Scheduling technology, a job's trigger and cadence (the relay's poll interval included), coordination across instances and the replica count, overlap and missed-run policy, on/off property, job metrics, the prune job | `35-jobs.md`. A claim strategy in `20-persistence.md` that does not meet `35-jobs.md` § 3's replica count is not settled by precedence — it is a guarantee dropped by a shape decision: **stop and ask** |
   | Provider and model of each AI port, its prompt resources and answer type, what each unusable answer becomes, the fields sent to the provider, the store and its embedding model, the timeout and retrying layer of each AI port | `29-ai.md`. The port, its "could not decide" value and the review state stay `10-domain.md`'s; a timeout above the trigger's deadline, or a personal-data field sent in clear without a named receiver, is not settled by precedence: **stop and ask** |
   | The search document and its mapping, the index version and alias, the query and its cursor, the indexer's write rules, the rebuild's alias swap, the lag the projection consumes, the search dependency | `23-search.md`. Whether the case has a search index, its engine and the freshness tolerance stay `00-use-case.md`'s; a lag larger than the tolerance is not settled by precedence: **stop and ask** |
   | Storage family of each aggregate | `00-use-case.md` — never a design partial, and never this orchestrator. An aggregate the code already stores in the other family is not settled by precedence: **stop and ask** |
   | Audit level of each aggregate | `00-use-case.md` — never a design partial. An aggregate the code already audits at a higher level than the row asks is not settled by precedence (a history cannot be switched off without losing it): **stop and ask** |
   | Which reads are cached, each cache key, the cache provider, expiry and size per cache, the writers that must evict, cache serialization and metrics | `22-cache.md`. The freshness tolerance stays `00-use-case.md`'s; an expiry longer than it is not settled by precedence — it serves answers older than the business allowed: **stop and ask** |
   | Aggregate name, value object, port, event | `10-domain.md` |
   | Exception class and `errorCode` | `10-domain.md` |
   | Use case boundary, invariants, error situations | `00-use-case.md` |
   | Name and level of each test | `40-tests.md` |
   | A design pattern and the classes it creates | The partial of the layer those classes live in. Two partials adopting different patterns for the same class is not settled by precedence: **stop and ask** |

   `UC-NNN-spec.md` carries **a single value per fact** — the winner — across all
   blocks, including those that had inherited the old value. The discarded versions go
   into a `## Resolved divergences` section at the end, one line each: fact, discarded
   value, adopted value, partial that decided. The executor reads one truth; the audit
   trail is still there.

   A divergence the table doesn't resolve — two facts from the same owner, or a
   business-rule contradiction — **stops the pipeline** and asks. Don't invent it or
   stack it.

   **A downstream partial never edits an upstream one's fact — it asks for it.** A requirement
   born downstream and landing upstream — `20-persistence.md` bounding the producer's send
   time so the outbox lease outlives any send, a value `25-messaging.md` owns — travels as a
   named requirement in the downstream partial, and this step resolves it like any other
   divergence: the owner's block in `UC-NNN-spec.md` carries the new value, and
   `## Resolved divergences` records who asked. Carried silently into the owner's block, it is
   a fact with two authors and no record of the second.

   **And one shape the table resolves only in appearance:** a column decision that changes
   behaviour. `20-persistence.md` wins on the column, always — but when dropping a column
   also drops a guarantee `25-messaging.md` declared (no `next_attempt_at`, so per-row
   exponential backoff becomes a fixed repoll), the winner is not the answer. That is a
   divergence the table doesn't resolve: **stop and ask**, and record which guarantee the
   chosen shape delivers. A precedence rule written for table shape must never be what
   settles a behaviour.

2. Consolidate into a single file: `UC-NNN-spec.md`, with `status: draft`, **by reference**
   - Each block holds only the final decisions — one value per fact — and the path of the
     partial that details it. Never copy a partial's tables, SQL, or code: a copied
     consolidation doubled the output of a real run and added nothing the partial lacked
   - Structure: 5 blocks (use case, domain, persistence, REST, tests) — `## 4 REST` reads
     `none — GraphQL only` when step 3 was skipped — plus `## 4.1 GraphQL` only when step 3g ran,
     `none` otherwise, plus a 6th
     (messaging) only when step 4 wasn't skipped, a 7th (jobs) only when step 4b wasn't, and
     the security block (`## 4.5 Security`) only when step 3b wasn't — when it was, that block
     reads `none` with the skip row of step 3b, so "covered by deny-by-default" is a recorded
     fact and not a missing block
   - The outbound HTTP block (`## 3.3 Outbound HTTP`) only when step 3c ran; `none` otherwise
   - The cache block (`## 3.4 Cache`) only when step 5b ran; `none` otherwise
   - The AI block (`## 3.7 AI`) only when step 3d ran; `none` otherwise
   - The search block (`## 3.8 Search`) only when step 3e ran; `none` otherwise
   - `## Impact on approved use cases`: every row from the same section of each partial —
     "none" when all are empty
   - `## Design patterns`: every row from the same section of each partial, with the partial
     that decided it — "none" when all are empty. **A partial that applies must carry the
     section, or consolidation stops**: absence is not `none`, the same test as the
     declared-dependency lists below. A row whose force is neither a spec line nor a
     `file:line` is not consolidated — the pipeline stops and asks
   - **A row that adds a precondition must name its satisfier, or consolidation stops.** Any
     row making an earlier case require a state it did not require before — a status, a flag, a
     related record — carries the `Satisfied by` column `00-use-case.md` already asks for:
     an approved `UC-NNN`, this case, or a backlog row **by its `BL-NN`**. Missing or vague, the
     pipeline stops and asks; it is not filled in by inference. A `UC` number is not a valid
     answer for a backlog case — it has none until it is designed, and two reports of a real run
     invented `UC-004` because the sentence needed a name and `BACKLOG.md` had none to give
     (lessons-learned-014 § 7). Where the satisfier is a backlog row, the consolidated spec says
     outright that the earlier case is unreachable end to end until that one ships, and the final
     report repeats it, citing the same `BL-NN` — a use case that answers 422
     on every real call is not a detail the reader should have to find in a test fixture
     (lessons-learned-013 § 5)
   - **Every `errorCode` a partial names exists in its owner, or consolidation stops.** The
     precedence table gives `errorCode` to `10-domain.md`, but precedence only settles a fact
     written twice with different values — a code written once, in a downstream partial, is
     settled by nothing and reaches the executor with no exception behind it (issue #167:
     `ORDER_ALREADY_EXISTS` in `20-persistence.md`, absent from the domain). Walk every partial:
     each code is declared in `10-domain.md` § 2 with its exception class, and each one an HTTP
     path can return has a row in `30-rest.md` § 3 · Error map. A code missing from either is not
     inferred into place — **stop and ask** which partial is wrong
   - **A partial that applies must carry its declared-dependency section, or consolidation
     stops.** `20-persistence.md` § 6 always; `31-graphql.md` § 11 whenever step 3g ran; `32-security.md` § 8 whenever step 3b ran;
     `28-http-client.md` § 8 whenever step 3c ran; `29-ai.md` § 10 whenever step 3d ran; `23-search.md` § 9 whenever step 3e ran;
     `25-messaging.md` § 7 whenever step 4 ran; `35-jobs.md` § 7 whenever step 4b ran; `22-cache.md` § 8 whenever step 5b ran; `40-tests.md` § 5 always. The
     value `none` is legitimate and common — most cases need no new dependency — but **absence
     is not `none`**: one says the design skill decided nothing was needed, the other says
     nobody looked, and the executor cannot tell them apart. Steps 4 and 5 validate the same
     thing; they are conditional, and a spec once reached `approved` with both lists missing
     because its partials had been written in earlier runs (lessons-learned-014 § 6). The
     executor then added a dependency to `pom.xml` by inference — a defensible one, and
     precisely the decision this list reserves for the design skill. Consolidation is the block
     every run passes, which is why the check belongs here as well, exactly like `Satisfied by`
     above
   - **A partial that needs a container must carry `## Compose services`, or consolidation
     stops.** `20-persistence.md` always; `25-messaging.md` whenever step 4 ran; `22-cache.md` whenever step 5b ran; `29-ai.md` whenever step 3d ran; `23-search.md` whenever step 3e ran. `none` is
     valid (H2, no broker) and absence is not. Every row goes into the spec's
     `## Infrastructure` with the partial that decided it, shaped by
     `@.claude/skills/docker-architect/references/compose-service-decision.md`. Without it the implement run, in a session that never saw
     this one's report, has nothing to add and nothing to name — the broker of issue #119
   - **A personal-data field crossing the process boundary in clear carries a recorded
     decision, or consolidation stops.** `25-messaging.md` § 8, `30-rest.md` § 6, `31-graphql.md` § 8 and `29-ai.md` § 4 each list
     the fields of the payload, the response body and the GraphQL output types that match
     `@.claude/rules/logging.md` § Masking candidates, with the form chosen. A `full value` row
     without a named receiver and a reason is not consolidated: the pipeline stops and asks, and
     it is not filled in by inference. `none` is a valid value and absence is not one. Until now
     this was a line in the **final report** — detection after the spec is approved, after the
     code is written and after the commit, and in the observed run it appeared there only because
     the executor volunteered it, for a CPF already published in clear on a topic
     (lessons-learned-014 § 8, `@.claude/rules/personal-data.md` § In transit)
   - **A deferred row leaves the run with an owner, or consolidation stops.** Each partial's
     `Deferred` block carries what it decided not to do, the norm that requires it, and an
     intended owner. `checklist` → add the item to this spec's checklist; the run implements it.
     `backlog` → **consolidation appends the row to `docs/use-cases/BACKLOG.md` and assigns its
     `BL-NN`**, then writes that id into this spec's `## Out of scope` with the consequence
     spelled out: what the project does not guarantee until that row ships. Consolidation is the
     only writer of a backlog row inside a feature run — two partials each computing "highest
     plus one" would issue the same `BL-NN`. An item whose norm makes it mandatory and whose
     owner is missing or vague stops the pipeline and is not inferred: a real run decided a
     7-day retention for `outbox_events`, left a commented `DELETE` in the migration, kept
     `app.outbox.prune-after` out of `application.yml` on purpose, and handed the job to nobody,
     over a table whose payload carries personal data (lessons-learned-014 § 9,
     `@.claude/rules/personal-data.md` § At rest)
   - **Every case named in that section gets a line in its own `CHANGELOG.md`**, at
     `docs/use-cases/UC-XXX-<slug>/CHANGELOG.md` — created on the first change, appended
     afterwards. One line: date, the `UC-NNN` making the change, and what changed
     (`2026-09-27 · UC-003 · RegisterCustomerUseCase now emits CustomerRegistered`). The
     approved spec stays untouched, which is exactly why the log is a separate file
     (`@.claude/rules/naming.md` § Use case identifier). Without it, nobody reading the
     altered case ever learns it changed — immutability keeps the text and loses the
     history
   - Order: implementation order (depends-on)
   - **Every `permitAll` carries its reason, or consolidation stops.** `32-security.md` § 1
     lists the public paths of the project after this case, each with a reason; a public row
     with none, or a first secured case whose `## Impact on approved use cases` does not say
     what happens to the endpoints earlier cases published without a chain, is not consolidated
     — deny by default turns them into 401 on the next deploy, and nobody chose that
     (`@.claude/rules/authorization.md` § Default access)
   - Recipient: `java-spring-boot-developer` agent — a spec with § 4.1 runs the executor's
     conditional Block G (steps G1-G3, right after Block 3 and before Block S), which generates the
     schema file, the GraphQL controllers and records, and — the first time — the GraphQL setup from
     `31-graphql.md`; a spec with § 4.5 runs the executor's
     conditional Block S (steps S1-S3, right after REST), which generates the filter chain, the
     mechanism wiring, the method-security annotations and the actor resolution from
     `32-security.md`; a spec with § 3.3 runs the executor's conditional Block H (steps H1-H3,
     right after Block 2), which generates the outbound adapters, the shared translator and the
     engine configuration from `28-http-client.md`; a spec with § 6 runs the executor's
     conditional Block M (steps M1-M4, between REST and Tests), which generates the Kafka
     producer/consumer adapters from `25-messaging.md`; a spec with § 7 runs Block J (steps
     J1-J3, right after Block M), which generates the scheduling wiring and triggers from
     `35-jobs.md`; a spec with § 3.4 runs Block C (steps C1-C3, right after Block J), which
     generates the cache wiring and the port decorators from `22-cache.md`. None touches the fixed
     19-item checklist

3. **Check whether the architecture tests can now be turned on.** The bootstrap doesn't
   install ArchUnit or the coverage gate by design: a `check` over an empty set proves
   nothing. After the first feature that condition no longer holds, and nothing in the
   pipeline was watching for it.

   ```bash
   grep -rl "ArchRule\|ArchTest" --include='*.java' src/test/ 2>/dev/null | head -1
   find src/main/java -name '*.java' ! -name 'package-info.java' | head -1
   ```

   Second command with a result and the first without: the project now has business
   classes and still has no boundary enforcement outside the editor. In `single-module`
   the compiler doesn't enforce anything either, so there's no verification at all.
   **Propose running `test-architect` in setup mode** — propose, don't run it: detection
   is mechanical, authorization is the user's.
