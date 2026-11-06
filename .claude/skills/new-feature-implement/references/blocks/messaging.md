# Block M: Messaging (steps M1-M4, conditional)

## Contents

- Form B — outbox and relay (when the messaging block chose it)


**Runs only when the spec carries the messaging block** — i.e., `new-feature`'s messaging
step wasn't skipped and `25-messaging.md` exists. Check with
`grep -m1 -E '^## [0-9.]+ Messaging' <spec>` — the heading's text, not its number: the template
numbers it `3.5`, and a check on `^## 6` matched no spec ever written. No match, or the block
reads `none` → skip to Block J. Don't fabricate a messaging block a spec never asked for.

Read the messaging block of spec.md, then `25-messaging.md`. Which publication form it chose decides what is
generated: **Form A** (publish after commit) plus any consumer is the list immediately below;
**Form B** (transactional outbox + relay) is the sub-block after it, and the two are not
variants of one shape.

Form A and the consumer generate:
- `[Event]Payload.java` (`adapter/out/messaging`) — adapter-local record, mapped
  explicitly from the domain event; the event itself never serializes directly
- `[Event]Publisher.java` — implements the outbound port `domain-modeling` already
  declared (`Publish[Event]Port`); `KafkaTemplate`, partition key = aggregate id
- the **AsyncAPI annotations**, written from the rows of `25-messaging.md` § 1: `@AsyncPublisher` on
  the publisher's `publish` — under Form B, on the outbox writer's, never on `KafkaOutboxEventSender` —
  and `@AsyncListener` with `@KafkaAsyncOperationBinding(groupId = …)` on the listener method. Channel,
  message name, description and headers are the rows' words, not the executor's; `payloadType` is the
  payload record; the topic is the flow's one `static final String`. The publisher or writer is a
  `@Component`, and the method names are the ones the derived operation ids in § 1 were built from —
  a different name is a divergence to report. Shapes: `KafkaProducerAdapter.java.example`,
  `OutboxRelayPublisher.java.example`, `KafkaConsumerAdapter.java.example`
- `[Event]Payload.java` (`adapter/in/messaging`) — mirrors the producer's shape
- `[Event]Listener.java` — `@KafkaListener`, dedupes via `ProcessedEventStore` before
  calling the target use case's inbound port, then returns: no `Acknowledgment`, no retry
  loop, no `sleep`, no catch that drops the record — the container commits after the method
  returns and owns every retry. `@RetryableTopic` only when the messaging block's `Retry mode`
  is `non-blocking`. `@LogExecution` on the listener method **if and only if** the project's
  commons package carries a `LogExecution` annotation (the pre-flight may have just installed
  it); absent, neither the annotation nor its import, and never created here. When the use
  case writes an audited aggregate (spec § 3 Audit not `none`), the call runs inside
  `SystemActor.run("<consumer-name>", …)`
- `ProcessedEventStore` port + its persistence adapter — only if Block 2 modeled the
  dedupe table per `25-messaging.md` step 5a. Missing table and the partial named the
  need → spec defect, stop and report; this block doesn't design schema
- `application-kafka.yml` additions — bootstrap servers, producer `acks`/idempotence,
  consumer group id, offset reset, and `listener.ack-mode` only when the messaging block says
  `record` — plus the `springwolf` block of `application-kafka.yml.example` when the project's
  `application.yml` has none, with `base-package` set to the messaging adapter packages
- the dependencies § 7 names in `pom.xml`, versions empty where the Boot parent manages them. The
  one it does not manage, `springwolf-kafka`, gets the latest stable `2.x` number (no suffix such
  as `-RC` or `-M`) read from
  `https://repo1.maven.org/maven2/io/github/springwolf/springwolf-kafka/maven-metadata.xml` when
  the line is written — never a version remembered, and never `<release>`, which is the last
  deploy
- the shared error handling in the `listener` subpackage, only when the messaging block says
  NEW — `ListenerFailureClassification`, `MonitoredDeadLetterPublisher`,
  `KafkaListenerErrorHandling`, shapes in
  `.claude/skills/messaging-architect/templates/KafkaListenerErrorHandler.java.example`; plus
  `KafkaRetryTopicConfiguration` from `KafkaRetryableTopicListener.java.example` on the first
  `non-blocking` listener. REUSE → touch none of them

Rules: `.claude/rules/messaging.md` (topic naming, idempotent consumer, retry/DLQ —
a business rejection from the consumed use case skips retry and goes straight to the
DLQ), `.claude/rules/architecture-ddd.md` § Adapters (translate, no business logic),
`.claude/rules/naming.md`, `.claude/rules/code-quality.md`, `.claude/rules/lombok.md`,
`.claude/rules/error-handling.md` (which exception is a business rejection vs. a retry
candidate), and `.claude/rules/logging.md`: `[Event]Listener` logs event type +
correlation identifier + outcome, `INFO`; the publisher logs outcome and latency, never
the payload, `INFO` on success / `WARN` on a recovered failure.

Validation — **Form A and the consumer**: payload is never the domain event, partition key is
the aggregate id, the offset commits only after the use case returns, a business rejection
routes to the DLQ without retrying while `ConcurrentUpdateException` retries, the listener has no
`Acknowledgment`, loop or `sleep`, and the dedupe check runs before the use case is called, not
after, every publisher and listener method carries the annotation § 1 describes, no annotation
sits on `KafkaOutboxEventSender`, and the `springwolf` block turns the `@KafkaListener` scanner and UI
publishing off. Under Form B with no consumer in this project, four of those six are `n/a`, and a
block whose whole validation list is `n/a` validated nothing — use the Form B list below
instead.

## Form B — outbox and relay (when the messaging block chose it)

`25-messaging.md` chooses Form B when the event must not be lost between the commit and the
publish. It is a **different set of classes**, not the Form A publisher with a table added, and
everything below has a shape exemplar: nothing here is for the executor to infer
(lessons-learned-013 § 2, where an unsupervised run invented all of it).

Read the messaging block and the partial, then generate — shapes in
`.claude/skills/messaging-architect/templates/OutboxRelayPublisher.java.example` (the relay
pass) and `.claude/skills/persistence-architect/templates/OutboxEventStore.java.example` (the
table side, Block 2's business). The relay's **trigger** is not this block's: it is Block J's,
from `35-jobs.md`:

- `[Event]Payload.java` + the outbound port's implementation — writes an outbox row **inside
  the caller's transaction** instead of sending. No `@Transactional` of its own: the use case
  owns the transaction, and a new one here reopens the gap Form B closes
- `RelayOutboxEvents` (inbound port) + `RelayOutboxEventsCommand` + `RelayOutboxEventsService`
  — one pass: takes the command (batch size, attempt ceiling), claims a batch through the
  application-layer gateway, sends through `OutboxEventSender`, marks, and returns a
  `RelayOutcome`. No `@Scheduled`, no `@Transactional`, no Kafka type. The command is the use
  case's input, placed where the blueprint puts every Command — never a settings bean injected
  at construction, never a record nested in the use case
- `OutboxEventSender` (outbound port) + `KafkaOutboxEventSender` — the **only** class in the
  project that imports `KafkaTemplate`. Never inside the appending transaction, and never
  through the persistence adapter's entity or repository
- The messaging adapter binds nothing of `app.outbox.*`: `batch-size` and `max-attempts` are
  bound in Block J by the relay job's `OutboxProperties`, which builds the command. The service
  itself is a `@Service`, never built by hand in a `@Configuration`. **One `app.outbox` map in
  `application.yml`**: Block M writes the two keys `20-persistence.md` names, Block J merges its
  own into the same map — never a second `app:` root
- The dependency of § 7 in `pom.xml` — `spring-boot-starter-kafka`, because Spring Boot 4
  autoconfigures no Kafka from `spring-kafka` alone. One `<dependency>`, no version when the
  parent manages it, and only what § 7 names
Validation — **Form B**, each item checkable and none of them optional:

- `KafkaOutboxEventSender` is the only class importing `KafkaTemplate`, and the pass does not
  run inside the transaction that appends the row;
- the pass is a use case behind `RelayOutboxEvents`, and nothing in the messaging adapter
  carries `@Scheduled` or `@EnableScheduling` — the trigger, its gate and the two delivery
  metrics are Block J's;
- `RelayOutboxEventsServiceTest` (fake gateway and sender) and `KafkaOutboxEventSenderTest`
  (mocked `KafkaTemplate`) exist, no broker: sent, failed, unroutable, and the failed row that
  exhausts its attempts. Relay coverage that comes only from a scheduler having run during the
  integration tests is accidental, and it is the proof the trigger is running where it should
  not;
- dead-lettering is a named operation (`markDeadLettered`), never `markFailed(…, maxAttempts =
  0)` arriving at the same state by arithmetic;
- the claim strategy is the one `20-persistence.md` states — that partial owns it. A claim
  whose name promises a lock the query does not take is a divergence to report, not to fix
  here.

Compilation: `./mvnw -q -pl <messaging-adapter-module> test-compile` in multi-module,
`./mvnw -q test-compile` in single-module ✅

**Line in the final report:**
```
✅ Block M: Messaging complete (steps M1-M4)
- Producer: OrderConfirmedPublisher — topic orders.order.confirmed, key = orderId
- Consumer: OrderConfirmedListener — group inventory-service, blocking retry, BATCH commit
- Dedupe: ProcessedEventStore (table modeled in Block 2)
- Config: acks=all, earliest; error handling REUSE (3 retries → .dlq)
- Contract: 2 operations annotated (orders.order.confirmed_send_publish, …_receive_onMessage)
- Files: 5 classes, 0 errors
```
