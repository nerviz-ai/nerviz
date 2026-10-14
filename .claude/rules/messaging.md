---
paths:
  - "**/adapter/out/messaging/**"
  - "**/adapter/in/messaging/**"
  - "**/infrastructure/messaging/**"
status: active
---

# Messaging — broker adapters, delivery, and resilience

A domain event is a plain fact the domain already emits (`@.claude/rules/architecture-ddd.md`
§ Domain: past-tense name, zero framework). This rule covers what happens **after** that:
the adapter that puts the event on a broker, and the adapter that reads one back. Today the
only implemented broker is Kafka; the shape below is written for it, and a second broker adds
a new section here, not a new file — single owner, `@CLAUDE.md` invariant 2.

## Boundary

- The domain event never crosses into the adapter unchanged: the producer adapter maps it to
  a message payload, explicit, both directions. No `DomainEvent` implementation serialized
  directly onto the wire
- Zero business logic in the adapter. A consumer that branches on payload content beyond
  "which use case do I call" has smuggled a domain decision into the transport layer
- The producer adapter implements the outbound port `domain-modeling` already declared for
  the event (`10-domain.md` § Ports) — it doesn't invent its own interface
- The consumer adapter translates an inbound message into a call on the target use case's
  inbound port. It never touches the target aggregate directly
- No Kafka type (`ProducerRecord`, `ConsumerRecord`, `@KafkaListener`) in a domain or
  application signature
- **One subpackage per event flow inside the messaging adapter**, always — including while
  the project has a single flow. A flow is the payload record plus the producer adapter that
  writes it, or the consumer adapter plus its dedupe: `…messaging.orderconfirmed`,
  `…messaging.stockreserved`. The unit here is the **flow**, not the aggregate, because one
  event routinely crosses aggregates and two events of one aggregate have nothing in common
  but their source. **Shared transport infrastructure gets its own subpackage on the same
  rule** — `…messaging.outbox` first among them, holding the relay's broker sender, its
  properties binding and the outbox writer. The trigger that runs the relay on a clock is not
  transport: it lives in the scheduling package, `@.claude/rules/scheduling.md` § Boundary. A flat adapter package holding every flow's
  classes breaks SRP at the package level and grows without bound: one feature already put six
  classes in it, four of them the outbox's. The `test/` tree mirrors the same subpackages
- **Visibility is package-private by default**, payload record, producer, consumer, relay and
  wiring alike. The split is what makes the default bite: one flow's payload is no longer
  visible from another's subpackage, and the way across is the port the adapter implements,
  never a `public` added to reach sideways. Only the specific class another flow genuinely
  consumes becomes `public` — one class at a time, never preemptively
- **Framework wiring for the broker lives in this package too, `@Configuration` included.**
  A class that builds a `ProducerFactory`, a `KafkaTemplate`, or a `DefaultErrorHandler` is
  adapter-local wiring: it carries broker types,
  so it sits with the adapter and not in a project-wide configuration package. This is the
  explicit exception to a module layout that assigns Spring wiring to a configuration package
  — stated here because the two readings contradict each other otherwise, and a real run
  resolved it by failing the grep below and moving the class, which is the right answer
  arrived at the wrong way. Two consequences, both deliberate: the grep stays absolute (no
  `@Configuration` escape hatch, or it stops verifying the boundary), and a coverage
  exclusion written for configuration classes must select them by what they are, not by which
  package they happen to live in

## Delivery semantics

- At-least-once, always. Exactly-once delivery isn't a Spring Kafka default and isn't assumed
  anywhere in this rule
- Every consumer is idempotent: dedupe on the event's own identity (its id, or aggregate id +
  event type + occurred-at), not on offset. A redelivered message must produce the same
  end state as the first delivery, not a duplicate side effect
- **At-least-once is a claim about two parties, so the design names both.** Who publishes is
  obvious; who absorbs the duplicate is not, and where the consumer belongs to another team or
  another company the guarantee is being delegated across an organizational boundary. The
  design that publishes says which receiver dedupes and whether that is **contracted** (written
  where both sides can point at it), **assumed**, or **unknown** — and the last two are
  reported as findings, not settled by a comment in the producer. "Duplicates are absorbed by
  the idempotent consumer" is true only while a consumer exists that does it
- **A duplicate has more than one source, and they need different answers.** A failed mark
  after a successful send duplicates one message, and the consumer's dedupe is the answer. Two
  sweepers reading the same unlocked rows duplicate the whole batch on every pass, and the
  answer there is the claim itself — a lease, `FOR UPDATE SKIP LOCKED`, or a stated
  single-instance constraint. A method named for claiming that only reads is the shape to look
  for: the name promises the guarantee the query does not provide
- Producer: `acks=all` and `enable.idempotence=true`. Without idempotence, a producer retry
  after a transient broker error can duplicate the message before it even reaches the
  consumer
- Ordering only within a partition. If the use case needs order between two events of the
  same aggregate, they share a partition key — the aggregate id, never a random key

## Publication timing

§ Delivery semantics fixes *what guarantee* the message carries. This section fixes *when
the publish happens relative to the transaction that produced the event* — a separate
decision, and the more expensive one: it decides whether the project gains a table, an
outbound abstraction, and a scheduled component, or none of the three.

Publishing **before** the commit is never one of the forms. A rollback after a successful
send leaves a consumer acting on a state that no longer exists, and no retry policy on
either side can undo it.

**Form A — publish after commit. The default.** The transaction commits, then the outbound
port's implementation sends to the broker. One component, no extra table, nothing to
operate. The loss window is real and unmonitored: a broker outage, a pod kill, or a network
partition between the commit and the send drops the event with no trace — the state change
is durable, the announcement isn't.

**Form B — transactional outbox + relay.** The outbound port's implementation inserts a row
into an outbox table **in the same transaction as the state change**; commit makes state and
intent-to-publish atomic. A separate relay component claims unpublished rows, sends them, and
marks them published. Costs one shared table, one application-layer abstraction over it, a
scheduled component, and duplicates whenever the send succeeds and the mark fails — absorbed
by the idempotent consumer § Delivery semantics already requires.

Choose Form B when at least one holds:

- Losing the event corrupts state that a human then has to reconcile — money moved, an
  external ledger, a regulatory record
- The consumer is external and its effect is irreversible once it does run, so a silent
  non-delivery is indistinguishable from "it never happened"
- The event is the only record that the fact occurred: no later request, poll, or
  reconciliation job would notice its absence

Otherwise Form A. "This event matters" is not the criterion — every event matters. The
question is whether silence is recoverable.

Form B's own boundaries:

- The relay reads and updates outbox state through an application-layer abstraction, never
  through the persistence adapter's entity or repository — `@.claude/rules/architecture-ddd.md`
  § Adapters
- Rows are claimed in batches and sent in occurrence order per aggregate, so Form B does not
  weaken the partition-key ordering above
- Attempts are counted and bounded. An exhausted row is flagged dead-lettered and stops being
  re-read; it is not retried forever and not deleted
- Published rows are pruned on a schedule. An outbox that only grows becomes the slowest
  table in the schema, and a retention window with no job enforcing it is no retention —
  `@.claude/rules/scheduling.md` § Retention
- The table, its columns, and its migration belong to `@.claude/rules/persistence.md`. One
  outbox for the whole project, not one per aggregate — same as any other shared
  infrastructure table

## Topics and serialization

- One topic per event type. A shared topic for unrelated event types forces every consumer
  to filter, and couples their deployment
- Topic name: `<bounded-context>.<aggregate>.<event-in-past-tense>` , kebab-case
  (`orders.order.confirmed`). Stable once in use — renaming a topic is a migration, not a
  refactor
- Payload: JSON by default. A schema registry (Avro/Protobuf) is a deliberate upgrade for
  when two teams need a contract they can validate at build time — not a default, and not
  assumed until a use case actually needs it
- JSON on the wire is **JSON text**, and each side converts it exactly once. The producer
  turns the payload record into text before it reaches the broker client — the publisher right
  before sending, or the outbox writer when it writes the row, after which the relay carries
  the text untouched — and the record value is serialized as a `String`. The consumer reads a
  `String` and one message converter builds the listener's typed parameter. Both publication
  forms therefore put the same bytes on a topic: a JSON object with no type header. A JSON
  value serializer would encode already-serialized text a second time, and its type header
  names the producer's class, coupling every consumer to the producer's packages
- The payload's fields are the minimum the consumer needs to act. It is not the full domain
  event object serialized as-is: a payload that leaks internal fields couples the consumer to
  the producer's domain model

## Retry and DLQ

- **The container retries, never the listener.** A loop, a `sleep` or a deferred rethrow inside
  the listener method holds the partition exactly as the container's retry would, without its stop
  handling, its per-record attempt count or its recoverer, and the failure never reaches the
  classification below
- A fixed number of attempts with backoff, not infinite. After the last one the record goes to
  `<topic>.dlq` — never dropped silently
- Which failure retries is decided **by type, once per project**, in the listener container's
  error handling — never per listener, and never by reading `errorCode()`. The most specific type
  wins: `ConcurrentUpdateException` retries although it is a `DomainException`

  | Failure | Retried |
  |---|---|
  | A domain family — `NotFoundException`, `ValidationException`, `BusinessRuleViolationException`, `ConflictException` (`@.claude/rules/error-handling.md` § Taxonomy) | no — straight to the DLQ: waiting does not fix a business rejection |
  | `ConcurrentUpdateException` | yes — the next attempt reloads the state, and the race it lost is over |
  | An integration family | per the `Retry-safe` column of `@.claude/rules/error-handling.md` § Integration families, the consumer being idempotent; the listener is the one retrying layer (`@.claude/rules/http-client.md` § Retry) |
  | A payload that does not convert to the listener's type | no — straight to the DLQ; the container's defaults already classify conversion failures as fatal |
  | Anything else — a database unreachable, a lock timeout | yes, then the DLQ |

- **A consumer never discards.** An exception that reaches the container is retried or
  dead-lettered; catching it in the listener to drop the record is a silent catch
  (`@.claude/rules/code-quality.md`). A record the business wants ignored — a duplicate, a verdict
  after the final one — is a normal return of the use case, a no-op with its own log line and
  metric, and its offset commits like any other
- The DLQ is monitored, not archived: a message with no consumer is a production incident,
  not a log line

### Blocking or non-blocking

Blocking is the default. The container's error handler seeks back and redelivers the failed
record after each backoff, and the records behind it in the partition wait: per-key order holds.
Non-blocking republishes the failed record to one retry topic per delay and lets the partition
move on: per-key order is lost for as long as the record retries.

| Shape | When |
|---|---|
| Blocking | the order of records with the same key decides the outcome — a state machine, a first-wins rule, a later record that supersedes an earlier one — or the whole backoff takes seconds, well inside `max.poll.interval.ms` |
| Non-blocking | records do not depend on each other's order and the wait is long — a dependency down for minutes — so holding the partition would stall every other key in it |

Both shapes apply the table above and dead-letter to `<topic>.dlq`, never to the framework's own
suffix. Non-blocking adds one topic per retry delay, declared and provisioned like the main one.

## Configuration

- Consumer group id: one per logical consumer, stable across deployments — changing it resets
  the committed offset and can cause reprocessing or gaps
- `auto-offset-reset`: `earliest` in an environment where losing unprocessed messages is worse
  than reprocessing old ones; `latest` only when a gap on redeploy is acceptable. Decide per
  use case, not as a global default
- **The container commits the offset after the listener returns — never the listener.** The
  default ack mode, `BATCH`, commits a poll's records once all of them were processed, and a
  dead-lettered record with them: nothing commits before processing, and a crash mid-poll
  redelivers processed records, which the idempotent consumer absorbs. `enable-auto-commit` stays
  `false`: with it on, the client commits on its own clock and the ack mode decides nothing
- `RECORD` — one commit per record — when redelivering a whole poll costs more than a commit per
  record: the use case reaches another system whose idempotency lives on that side, or a record
  takes seconds to process. The setting is project-wide, so it only goes up: once one consumer
  needs `RECORD` the project uses it, and a later consumer never lowers it
- Manual acknowledgment only when the listener hands the record to another thread before it is
  processed. A branch that returns without acknowledging leaves its offset to the next record's
  commit, or to a redelivery when it was the last of its partition
- Credentials come from the environment — `@.claude/rules/secrets.md`. Bootstrap servers
  with SASL/TLS come from the environment in any non-local profile

## How to verify

```bash
# No Kafka type outside the messaging adapter. Zero lines is the expected result.
grep -rn "org.apache.kafka\|org.springframework.kafka" --include=*.java . | grep -v "/messaging/"

# Producer idempotence and acks are set explicitly, not left to the client default.
grep -rn "enable.idempotence\|acks" src/main/resources/

# The record value is a String on both sides: no JSON (de)serializer configured.
# Zero lines is the expected result.
grep -rn "value-serializer\|value-deserializer" src/main/resources/ | grep -v "StringSerializer\|StringDeserializer"

# Form B only: the relay never reaches the persistence adapter's entity or repository.
# Zero lines is the expected result.
grep -rn "import .*persistence.*\(Entity\|Repository\)" --include=*.java . | grep -i "outbox\|relay"

# Consumer idempotency and DLQ routing are integration-test territory, not grep territory.
./mvnw -q test
```
