# Block 4: Tests (steps 16-19)

## Contents

- Steps 16-19 — test fixtures, unit, integration and architecture tests
- Close the spec — last, and only last
- Final summary — the run's one report

Read § 5 of spec.md, then the partial it cites for the step at hand. Generates:
- Test fixtures — builders, test data, `@TestData`
- `[Aggregate]Test.java` — unit (isolated domain)
  - Invariants verified
  - VOs validate
  - Events emitted
- `[Resource]RepositoryIT.java` — integration (real DB, Testcontainers)
  - Full CRUD
  - Relations save/load
  - Schema exists
- `[Resource]ControllerTest.java` — contract (`@WebMvcTest`)
  - 201 Created + Location
  - 422 Unprocessable + violations
  - 404 Not Found
  - 409 Conflict (invariant)
  - Idempotency (Idempotency-Key, If-Match)
- JaCoCo report (80% lines, 70% branches)
- When Block M ran: `[Event]PublisherIT.java` / `[Event]ListenerIT.java` — dedupe on
  redelivery, manual ack timing, business rejection routed to DLQ, per
  `.claude/rules/messaging.md` § How to verify
- When Block M ran: `AsyncApiContractIT.java` and its snapshot, once per project
  (`.claude/skills/test-architect/templates/AsyncApiContractIT.java.example`). The first run has no
  snapshot and fails naming it: copy `target/asyncapi/asyncapi.current.json` to
  `src/test/asyncapi/asyncapi.snapshot.json`, read the copy against `25-messaging.md` § 1 — channel,
  message name, operation ids, descriptions, headers — and run it again. A document that does not say
  what § 1 says is a divergence to report, not a snapshot to commit. Later cases refresh the snapshot
  in their own change
- When Block M ran **Form B**: `RelayOutboxEventsServiceTest.java` (fake gateway and sender)
  and `KafkaOutboxEventSenderTest.java` (mocked `KafkaTemplate`) — unit, no broker. Sent, send
  failure, the failed row that exhausts its attempts, and the unroutable-event-type dead-letter
  path. The broker-level test may be deferred by `40-tests.md`; these are not deferrable, and
  their absence is what left the class carrying the durability guarantee as the project's
  largest coverage hole (lessons-learned-013 § 8.1)
- When Block G ran: every case of `31-graphql.md` § 10 at the level it names — mapping, errors,
  `userErrors`, the page ceiling, the cursor round trip and one batch port call per request in the
  GraphQL slice (`.claude/skills/test-architect/templates/GraphQlControllerTest.java.example`);
  access, depth and introspection over HTTP through the real chain
  (`GraphQlHttpIT.java.example`); the schema compatibility test against `src/test/graphql/`
  (`GraphQlSchemaCompatibilityTest.java.example`), created by the project's first GraphQL case
- When Block S ran: every case of `32-security.md` § 10 — each protected endpoint without a
  credential (401), without the authority (403, or the 404 ownership chose) and with it; each
  public one with no credential; each ownership row at the use case with another actor — through
  the real filter chain, never with the security filters switched off
  (`.claude/skills/test-architect/templates/SecuredControllerTest.java.example`,
  `.claude/rules/authorization.md` § Tests). Existing controller tests the impact section names
  are updated in the same step: they import the chain and send a credential
- When Block H ran: every case of `28-http-client.md` § 9 — through the outbound port against a
  stub server on a real socket, never `MockRestServiceServer`; the attempt count asserted on the
  stub, the family and `errorCode` on the exception
  (`.claude/skills/test-architect/templates/HttpClientAdapterIT.java.example`,
  `.claude/rules/http-client.md` § Tests)
- When Block A ran: every case of `29-ai.md` § 8 at the level it names — the answers through the
  adapter with a scripted stub `ChatModel` (`AiAdapterContractTest.java.example`), the wiring
  against a stub server speaking the provider's protocol with the attempt count asserted
  (`AiProviderIT.java.example`), a store against the real `pgvector/pgvector` engine with a stub
  embedding model (`VectorStoreIT.java.example`) — all in `.claude/skills/test-architect/templates/`.
  No test calls a real model (`.claude/rules/ai.md` § Tests)
- When Block J ran: each job's use case is tested by calling its inbound port — never by
  waiting for a scheduler; the prune's service with a gateway fake that returns full batches
  then a short one; and one context test asserting that, under the test profile, no trigger
  bean exists (`35-jobs.md`, `.claude/rules/scheduling.md` § Triggers)
- When Block C ran: for every cached read of `22-cache.md` § 1, through the port against the real
  provider — a Redis container for Redis — a second read that does not reach the delegate, an
  eviction after the commit of every write § 4 routes through the port, and two callers never
  sharing an entry when the key names the caller
  (`.claude/skills/test-architect/templates/CachedAdapterIT.java.example`,
  `.claude/rules/caching.md` § Tests)

Rules: `.claude/rules/naming.md` (test class/method names and shape),
`.claude/rules/testing.md` (pyramid, doubles, test data).

Validation: 28+ cases, 100% pass, coverage gate ✅, and every test on a unique business
key asserts the domain exception's `errorCode` — not just the type. That's the assertion that
distinguishes an adapter that translates the violation from one that lets it escape
(`.claude/rules/testing.md` § Names and shape).

Compilation + test: `./mvnw verify` ✅ (JaCoCo check passes) — run into the log, and read
failures and totals from the reports, as § What enters the context says

**Close the spec — last, and only last.** Two writes, in this order and never the other way
round:

1. **Tick the checklist**, every item this run completed, `[ ]` → `[x]` — in a `tests`-group
   run, steps 16–19; the earlier groups ticked theirs.
2. **Then** Edit the `status: approved` line to `status: implemented` — that line, nothing else
   in the file. Red build → the status stays `approved`.

The order is the contract, not a preference: the status line is the **last write this agent
makes to the folder**. It used to be irreversible to get wrong — a run that closed first froze
23 unticked boxes permanently and had its revert refused too, leaving the spec describing as
unfinished a feature that was on disk, green and committed. The toggle survives the close now,
so the mistake is recoverable; the order is still what keeps the spec readable without needing
the recovery.

**When the run left an approved use case unreachable, close to `status: implemented-blocked`
instead** — see § *An approved use case left unreachable* below for when that applies. Same
single line, same green build. It is not a failure state: the code is on disk and verified, and
the value says what `implemented` alone cannot, which is that a `Satisfied by` the spec named is
not there yet.

**Final summary — the run's one report:**
```
UC-001-order implemented ✅ COMPLETE

🧱 Blocks:
<the line of each block that ran, in order — 1, 2, H, 3, S, M, J, C when present; in a
`tests`-group run, the earlier groups' lines as they arrived>
✅ Block 4: Tests complete (steps 16-19)
- Unit: OrderAggregateTest — 8 cases, 100% pass
- Integration: OrderRepositoryIT — 6 cases, real DB, 100% pass
- Contract: OrderControllerTest — 9 cases, @WebMvcTest slice, 100% pass
- Coverage: 82% lines, 74% branches (gate ✅ 80/70)
- Files: 8 classes, 28 tests

═══════════════════════════════════════════════════════════════

📊 Summary:
- Files created: 23 Java classes
- Domain: 6 (aggregate, 3 VOs, 2 events)
- Persistence: 5 (entity, repository, adapter, migration, yml)
- REST: 5 (controller, 3 DTOs, mapper, handler)
- Tests: 8 (2 unit, 3 integration, 3 contract)
- Patterns: 1 from the spec (Specification — OrderEligibility); 0 adopted on disk symptom
  (each one adopted alone: pattern, `file:line`)

✅ Tests: 28 cases, 100% pass
✅ Coverage: 82% lines, 74% branches (gate 80/70)
✅ Build: ./mvnw verify — green
✅ Checklist: <n>/<n> complete
✅ Spec: status: implemented          (or `implemented-blocked`, naming the case it blocks on)

🔧 Next step: the caller (`/new-feature-implement`) prints the `/git-publish` line — commit and
push happen there, typed by the person, not in this agent.
```
