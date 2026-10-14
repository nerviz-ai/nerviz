# 0159 · A concurrent update is a typed, retryable conflict, and a Kafka consumer retries it in the container — blocking or non-blocking, never in the listener

- **Date:** 2026-10-14
- **Scenario:** "investigue o listener KycVerificationCompletedListener no pag-bank. onMessage está fazendo retry com backoff manualmente. O Spring Kafka já não provê esse tipo de funcionalidade por meio das anotações ou configs do container Kafka?" — followed by "registre a possibilidade do uso do @RetryableTopic levando em consideração os cenários de quando e quando não usar, assim como criação de templates".
- **Decision:** Option 1, with both extensions — Form 4 (`rules/messaging.md`, `rules/error-handling.md`, `rules/persistence.md`) plus exemplars and procedure in `messaging-architect`, `domain-modeling`, `persistence-architect`, `use-case-design` and the executor, and a `migrations` entry
- **State:** approved by Lucas Fernandes, on 2026-10-14

## Reproduced on disk

In `bank-app` (nerviz v0.34.0), `UC-005-record-kyc-verdict`:

- `infrastructure/messaging/kycverificationcompleted/KycVerificationCompletedListener.java`
  retries `ConflictException` `CUSTOMER_CONCURRENT_UPDATE` three times in a `while (true)` loop
  with `Thread.sleep` 1 s / 2 s / 4 s, inside `onMessage`.
- `infrastructure/messaging/listener/KafkaListenerConfig.java` justifies it in its Javadoc:
  *"Spring Kafka's exception classifier matches by exact Java type"*. False on spring-kafka
  4.1.1 (the version Boot 4.1.1 resolves): `org.springframework.kafka.support.ExceptionMatcher`
  walks `getSuperclass()` from the concrete class and returns the nearest entry, and walks
  `getCause()`. What is true is narrower: it classifies by type, never by `errorCode`.
- The chain that produced it, all in this repository:
  - `rules/persistence.md` § Translation turns `OptimisticLockingFailureException` into
    `ConflictException` — concurrent modification; the persistence exemplars spell the code
    `<AGGREGATE>_CONCURRENT_UPDATE` (`RepositoryAdapter.java.example`,
    `MongoRepositoryAdapter.java.example`) or `CONCURRENT_UPDATE`
    (`DocumentTransactions.java.example`).
  - `rules/messaging.md` § Retry and DLQ: a business error does not retry. `ConflictException`
    is a `DomainException`, so every consumer whose use case writes a versioned aggregate sends a
    transient lost race straight to the DLQ.
  - `messaging-architect` step 6 and `messaging-spec.md.example` § 4 never ask about it — the
    `25-messaging.md` of UC-005 does not name `CUSTOMER_CONCURRENT_UPDATE`.
  - No exemplar shows the `DefaultErrorHandler` bean: `application-kafka.yml.example` only says
    "wired once in a DefaultErrorHandler + DeadLetterPublishingRecoverer bean". The executor
    wrote it free-hand and improvised the loop.
- Side effect of the loop: an interrupt during `Thread.sleep` becomes a `ConflictException`,
  which the handler classifies as not retryable, and a record that only needed redelivery goes
  to the DLQ.

Verified on the spring-kafka 4.1.1 jar (`javap`):

- `DefaultErrorHandler` (`FailedRecordProcessor`) has `setBackOffFunction(BiFunction<ConsumerRecord, Exception, BackOff>)`
  — a per-record, per-exception backoff, able to read `errorCode()`.
- `@RetryableTopic` and `RetryTopicConfigurationBuilder` classify by type only: `include` /
  `exclude` / `includeNames` / `excludeNames`, `retryOn` / `notRetryOn`, `dltRoutingRules`.
  No per-exception function. `DefaultDestinationTopicResolver` extends `ExceptionClassifier`,
  reachable through `RetryTopicConfigurationSupport.configureDestinationTopicResolver()` and
  `manageNonBlockingFatalExceptions(List)`.
- Both `DefaultErrorHandler` and `DefaultDestinationTopicResolver` extend `ExceptionClassifier`,
  so `addNotRetryableExceptions(DomainException.class)` plus
  `addRetryableExceptions(<subtype>.class)` resolves to the nearest class — one rule, both modes.

## Why ordering decided against `@RetryableTopic` in UC-005

`00-caso-de-uso.md` § Concurrency: *"ordering per customer and the state comparison are the
arbiters"*; `ACTIVE` and `REJECTED_BY_KYC` are final; `25-mensageria.md` partitions by
`customerId` so every verdict of one customer is applied in order. Non-blocking retry moves the
failed record to `<topic>-retry-N` and lets the partition go on: verdict A fails on a concurrent
update, verdict B of the same customer is applied and becomes final, A comes back and is
dead-lettered as `KYC_VERDICT_CONFLICT`. The verdict that wins is the one that did not fail, not
the first. Blocking retry holds B behind A's 7 s.

| | Blocking (`DefaultErrorHandler`) | Non-blocking (`@RetryableTopic`) |
|---|---|---|
| Order per key | kept | lost while a record retries |
| Partition during the retry | held | moves on |
| Long retry (minutes) | holds the partition, risks `max.poll.interval.ms` | fits |
| Extra topics | none | `-retry-N` and the DLQ |
| Classification | by type, or by `errorCode` through `setBackOffFunction` | by type only |
| Fits when | order decides the outcome, or the retry is short | independent events and a long retry |

## Manual acknowledgment buys nothing in the exemplar

Read in `spring-kafka-4.1.1-sources.jar` (`ContainerProperties.AckMode`,
`KafkaMessageListenerContainer`):

- `MANUAL`: *"acks will be queued and offsets will be committed when all the records returned by
  the previous poll have been processed by the listener"* — the same moment as `BATCH`, restricted
  to the acknowledged records.
- `BATCH` (the default): *"Commit the offsets of all records returned by the previous poll after
  they all have been processed by the listener."* After processing, not before.
- `RECORD`: *"Commit the offset after each record is processed by the listener"* — one commit per
  record.
- After the error handler recovers a record (DLQ), `commitOffsetsIfNeededAfterHandlingError`
  acks it in every mode (`ackAfterHandle` defaults to true; `ackCurrent(record, isManualAck)`
  queues it under `MANUAL`). No mode leaves a dead-lettered record uncommitted.

`rules/messaging.md` § Configuration says *"`AckMode.BATCH`'s auto-commit-before-processing is a
data-loss window under a crash mid-batch"*. False for the container's `BATCH`: a crash mid-batch
redelivers the processed records (duplicates, absorbed by the idempotent consumer the same norm
requires), it loses none. The window it describes belongs to the client's `enable.auto.commit=true`,
which the exemplar already sets to `false`.

A listener that calls `ack.acknowledge()` at the end of every successful path, as
`KafkaConsumerAdapter.java.example` does, is a `BATCH` listener with one more parameter, one more
call per branch, and a trap: a branch that returns without acknowledging leaves its offset to be
covered by a later record's commit, or redelivered when it was the last of the partition. Manual
acknowledgment pays off only when the listener hands the record to another thread, or deliberately
returns without committing (`nack`); neither exists in the pipeline.

| | `BATCH` (Boot default) | `RECORD` | `MANUAL` (today) |
|---|---|---|---|
| Commit after the use case returns | yes | yes | yes, if every branch calls `ack` |
| Commits per poll | one | one per record | one |
| Redelivered on a crash | up to the rest of the poll | at most the current record | up to the rest of the poll |
| Code in the listener | none | none | `Acknowledgment` + a call per branch |

## Who decides retry, DLQ or discard today

| Where | What it decides | Gap |
|---|---|---|
| `rules/messaging.md` § Retry and DLQ | Prose: transient retries a fixed number of times; a business error goes straight to the DLQ; exhausted goes to the DLQ; never dropped silently | No category for a concurrent update; no link to the integration families; no "discard" |
| `rules/error-handling.md` § Integration families | A `Retry-safe` column: `DependencyUnavailableException` yes, `OutcomeUnknownException` only for an idempotent call, `DependencyResponseException` no | Written for the outbound HTTP call; `messaging.md` never cites it, so a consumer retries a `DependencyResponseException` three times before the DLQ |
| `DefaultErrorHandler` defaults | Fatal, straight to the recoverer: `DeserializationException`, `MessageConversionException`, `ConversionException`, `MethodArgumentResolutionException`, `NoSuchMethodException`, `ClassCastException` (`ExceptionClassifier.defaultFatalExceptionsList`) | Implicit — nothing in the pipeline names it |
| `messaging-architect` step 6 → `25-messaging.md` § 4 | A per-use-case table (3 rows in the template) | Classification by type is project-wide: a per-case row cannot change it without editing the shared handler |
| The executor, on the first consumer case | Writes the shared `DefaultErrorHandler` free-hand — no exemplar, no owner after that case | Every later case inherits whatever it wrote; UC-005 needed an exception it could not express, and looped |
| `use-case-design` → `00-use-case.md` | Records the business outcome: in UC-005 *"discarded with a warning log and a metric (user's answer)"* | `messaging-architect` asked again and recorded *"goes to the DLQ … not discarded (user's answer)"*: the same question, asked twice, answered two ways |

"Discard" is no category of the norm, and swallowing an exception in a listener would break
`rules/code-quality.md`'s no-silent-catch (`0153`). What a use case calls "discarded" is
expressible one way: the use case returns normally as a recorded no-op — like *"same verdict
already applied: not an error"* — so the record is committed, logged and counted, and no exception
reaches the container. That is a domain decision, in `00-use-case.md`'s error table, and
`messaging-architect` then has nothing to classify.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | One observed case: the manual loop in `bank-app` UC-005, written by the executor against a false premise | — |
| 5 — nature | Declarative (when to retry, which mode) plus code shape (exemplars) | 3 |
| 7 — mandatoriness | Norm + exemplars; a listener that loops sometimes is tolerable, the exemplar removes the reason to improvise | 7, 8 |
| 8 — destination | Both: norms and development skills travel; `bank-app` receives it through a `migrations` entry | — |
| 9 — integration | Touches the owner of the optimistic-lock translation (`persistence.md`), the exception vocabulary (`error-handling.md`, `domain-modeling`'s `ConflictException.java.example`), `messaging.md` § Retry and DLQ, `messaging-architect` step 6 and its templates | — |
| — retry mode per use case | Derived, never asked: `messaging-architect` reads the `Concurrency` row of `00-use-case.md` and the retry length, by the criterion in the norm — same shape as `Admission` (`0151`) | — |
| — existing projects | A `migrations` entry, printed by `arch-adopt` on update, never run | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | `ConcurrentUpdateException` subtype + norm + two retry exemplars + migration | 8 | **Approved**, with both extensions |
| 2 | `errorCode` convention + `setBackOffFunction` + norm + two exemplars + migration | 6 | Rejected — the non-blocking shape has no per-exception function |
| 3 | create nothing in nerviz — fix `bank-app` by hand | 3 | Rejected — the scope answer was nerviz + bank-app |

### Option 1 — typed concurrent update (score 8)

**Motivator:** axis 9 — both retry modes must classify the same failure the same way, and the
non-blocking one only classifies by type.

**What changes:**

- `rules/error-handling.md` — the concurrent modification gets its named subclass,
  `ConcurrentUpdateException extends ConflictException`, under the existing bar *"a caller needs
  to `catch` it specifically"*: the container's error handler is that caller. It keeps the
  per-aggregate `errorCode` (`<AGGREGATE>_CONCURRENT_UPDATE`) passed in, so no REST contract
  changes.
- `rules/persistence.md` § Translation — `OptimisticLockingFailureException` and the spent
  document-store retries become `ConcurrentUpdateException`; a duplicate stays
  `ConflictException`.
- `rules/messaging.md` § Retry and DLQ — a concurrent update is transient and retries; retry is
  the container's, never a loop or a sleep in the listener; blocking by default, non-blocking only
  when order does not decide the outcome, with the table above as the criterion.
- `messaging-architect` step 6 — derives `Retry mode` from `00-use-case.md`'s `Concurrency` row
  and the retry length; asks nothing. `messaging-spec.md.example` § 4 gains the `Retry mode` row
  and the concurrent-update row.
- Templates: `KafkaListenerErrorHandler.java.example` (blocking: `DefaultErrorHandler`,
  `DeadLetterPublishingRecoverer` to `<topic>.dlq`, monitored, `DomainException` not retryable,
  `ConcurrentUpdateException` retryable) and `KafkaRetryableTopicListener.java.example`
  (non-blocking: `@RetryableTopic`, DLQ suffix `.dlq`, same classification on the destination
  resolver, same monitored recoverer). `KafkaConsumerAdapter.java.example` and
  `application-kafka.yml.example` point at them.
- `domain-modeling/templates/ConflictException.java.example` gains the subclass;
  `persistence-architect` exemplars throw it.
- `migrations` entry `kafka-retry-in-container`: move a listener's retry loop into the
  container's handler, type the concurrent update.

**Extensions, approved with it (asked 2026-10-14, after the first proposal):**

- *No manual acknowledgment.* `ack-mode` leaves `application-kafka.yml.example` (Boot's `BATCH`)
  or becomes `record`; `KafkaConsumerAdapter.java.example` loses `Acknowledgment`;
  `rules/messaging.md` § Configuration loses the false `BATCH` sentence and states: commit after
  the listener returns, by the container, never by the listener — manual only for a hand-off to
  another thread. Same `migrations` entry.
- *One classification table, one owner.* `rules/messaging.md` § Retry and DLQ becomes the table
  — domain families straight to the DLQ, `ConcurrentUpdateException` retried, the integration
  families per `error-handling.md`'s `Retry-safe` column (cited, not restated; an idempotent
  consumer makes `OutcomeUnknownException` retry-safe), framework conversion failures straight to
  the DLQ, anything else retried then dead-lettered — and states that a consumer never discards:
  an expected rejection is a no-op the use case returns. The shared handler exemplar encodes the
  table; the first consumer case writes it, later ones inherit it, and `25-messaging.md` § 4
  records the retry mode and the attempts, never a reclassification.
- *Ack mode derived, one template.* Two templates would differ by one yml line that is
  project-wide anyway. `BATCH` (no line) unless a consumed use case has a port of kind
  `external HTTP` or `LLM` — then `record` — and once `record`, never lowered. Asked and answered
  2026-10-14.
- *`@LogExecution` if and only if the commons logging classes are installed* (asked 2026-10-14,
  mid-implementation). Both listener exemplars carry it, conditioned in their EXEMPLAR header — a
  body carries no comment but Javadoc (`TemplateCommentsTest`). The executor decides, never
  `messaging-architect`: the implement run's pre-flight may install the commons package after the
  design was approved.

**Pros:** one classification, two modes; no string matching in shared config; nearest-class
resolution is the framework's own behavior; the REST side is unchanged (subclass of 409's type).

**Cons:** widest blast radius — four norms' files touched, five exemplars; strains
`error-handling.md`'s "only a fixed `errorCode`" for a named subclass, since the code stays
per aggregate; `bank-app` needs a migration that touches persistence and messaging.

**Points cut in the rubric:** 5 (maintenance — five exemplars and a new type), 6 (partial
precedent: named subclasses exist, a classifier keyed on one does not), invariants not violated
but strained (2: "fixed `errorCode`").

**CI:** a new `.claude/.ci/MessagingRetryTemplateTest.java`, step of `validate.yml` next to
`AdmissionTemplateTest`, offline: no `Thread.sleep` or retry loop in a messaging `.java.example`;
both retry exemplars classify `DomainException` not retryable and `ConcurrentUpdateException`
retryable, and dead-letter to `.dlq`; every optimistic-lock translation in the persistence
exemplars throws `ConcurrentUpdateException`. Imports: `validate` › `exemplar-imports`. The
`migrations` entry: `schema` and `MigrationsSchemaTest`. Shapes run once against a real
Initializr project before committing, as in `0151` § Verified.

### Option 2 — `errorCode` convention + backoff function (score 6)

Keeps the type vocabulary; fixes `<AGGREGATE>_CONCURRENT_UPDATE` as a convention in
`persistence.md`; the blocking exemplar uses `setBackOffFunction` and matches
`errorCode().endsWith("CONCURRENT_UPDATE")`. Rejected candidate because the non-blocking mode has
no per-exception function: a `@RetryableTopic` consumer cannot tell a lost race from a business
rejection, so the second exemplar would carry a hole the norm then has to explain. String
matching in shared config also breaks silently on a misspelled code. Cut: 1, 4, 5, 6. CI: the
same test, holding the suffix instead of the type.

### Option 3 — create nothing (score 3)

Fix `bank-app` alone. The next consumer of a versioned aggregate repeats the loop or dead-letters
the race, and the scope answer (nerviz + bank-app) already rejected it. Cut: 1, 4, 7.

## References

| Claim | Source |
|---|---|
| Nearest-superclass and cause traversal in classification | `spring-kafka-4.1.1.jar` › `ExceptionMatcher.matchInCache` (bytecode) |
| `setBackOffFunction` exists | `spring-kafka-4.1.1.jar` › `FailedRecordProcessor` |
| Non-blocking retry classifies by type only | `spring-kafka-4.1.1.jar` › `RetryableTopic`, `RetryTopicConfigurationBuilder`, `DefaultDestinationTopicResolver extends ExceptionClassifier` |
| A named subclass is allowed when a caller catches it specifically | `rules/error-handling.md` § Shape of the base classes |
| Optimistic lock becomes `ConflictException` | `rules/persistence.md` § Translation |
| Business error never retries | `rules/messaging.md` § Retry and DLQ |
| Derived, never asked | `0151-inbound-admission-in-rest-and-graphql.md` |
| A convention change leaving code behind is a `migrations` entry | `0104-use-case-subpackage-per-aggregate.md`, `extensions.json` › `migrations` |
| Template test precedent | `.claude/.ci/AdmissionTemplateTest.java` |

## Verified before committing

A project from the Initializr (Boot 4.1.1, resolved from `start.spring.io`, JDK 21, `kafka`,
`actuator`, `lombok`, `testcontainers`, plus `spring-boot-starter-aspectj` and
`spring-boot-starter-jackson`), with the exemplars split into files as written — domain family,
integration families, `KafkaListenerErrorHandler`, `KafkaRetryableTopicListener`,
`KafkaConsumerAdapter`, the commons logging classes — and one `@SpringBootTest` against
`apache/kafka:3.8.0` (Testcontainers). The only edit to an exemplar was the non-blocking delays,
shortened to 500 ms / 2 s for the run. Green:

- blocking: a `ConcurrentUpdateException` failing twice is called 3 times and never
  dead-lettered; one failing always, 4 times then `.dlq`; `ConflictException` and
  `DependencyResponseException` once then `.dlq`; an `IllegalStateException` 4 times then `.dlq`
  as `RETRIES_EXHAUSTED`; text that does not parse goes to `.dlq` as `MALFORMED`, uncalled;
- non-blocking: the same table through `<topic>.retry-0` and `-1`; `ConflictException` once, then
  `<topic>.dlq`; no `-dlt` topic exists; the dead-letter counter counts the DLQ only, not the hops;
- the consumer group's committed offset covers every record of the blocking topic, the
  dead-lettered ones included, with no `Acknowledgment` anywhere (`BATCH`);
- `@LogExecution` intercepts the package-private `onMessage` through the proxy.

Red: with `addRetryableExceptions(ConcurrentUpdateException.class)` removed, the lost race that
recovers is dead-lettered (six DLQ records instead of five).

Found by the run, fixed in the same change:

- `MonitoredDeadLetterPublisher` first missed `org.springframework.kafka.support.converter.ConversionException`,
  the type `StringJacksonJsonMessageConverter` throws — a malformed payload was reported as
  `RETRIES_EXHAUSTED`. `bank-app`'s `DeadLetterMonitoringRecoverer` has the same miss today; the
  migration replaces it.
- `http-client-architect/templates/IntegrationExceptions.java.example` used `@FieldDefaults`
  without importing it or `AccessLevel` — it did not compile as written. `exemplar-imports` checks
  the imports that exist, not the ones missing.
- `KafkaConsumerAdapter.java.example`'s listener had no `@Component` and was `public`, its payload
  `public` — never registered, and against `rules/messaging.md` § Boundary's package-private
  default.

Known and accepted: under `DocumentTransactions` a write conflict is retried in process (3 times,
milliseconds apart) before `ConcurrentUpdateException` reaches a listener that retries it again
(3 times, seconds apart). The first is the driver's documented whole-transaction retry, the second
waits out sustained contention; neither is an outbound call, so `http-client.md`'s one retrying
layer does not apply.

## Propagation

| File | Change |
|---|---|
| `.claude/rules/messaging.md` | § Retry and DLQ rewritten: the container retries, the classification table by type, no discard, § Blocking or non-blocking; § Configuration: the container commits, `BATCH` by default, `RECORD` derived and never lowered, manual only for a hand-off — the false `BATCH` sentence removed |
| `.claude/rules/error-handling.md` | `ConcurrentUpdateException` fixed as the one named subclass the rule sets; the family is six classes |
| `.claude/rules/persistence.md` | § Translation: optimistic lock and spent document retries → `ConcurrentUpdateException` |
| `.claude/skills/domain-modeling/templates/ConcurrentUpdateException.java.example` | new |
| `.claude/skills/domain-modeling/templates/ConflictException.java.example`, `SKILL.md`; `project-bootstrap/references/scaffold.md` | the family of six |
| `.claude/skills/persistence-architect/templates/RepositoryAdapter`, `MongoRepositoryAdapter`, `DocumentTransactions` (`.java.example`), `persistence-spec-document.md.example` | throw `ConcurrentUpdateException` |
| `.claude/skills/messaging-architect/templates/KafkaListenerErrorHandler.java.example` | new — blocking shape, shared classification, monitored DLQ publisher |
| `.claude/skills/messaging-architect/templates/KafkaRetryableTopicListener.java.example` | new — non-blocking shape |
| `.claude/skills/messaging-architect/templates/KafkaConsumerAdapter.java.example` | no `Acknowledgment`, `@LogExecution` conditioned, `@Component`, package-private |
| `.claude/skills/messaging-architect/templates/application-kafka.yml.example`, `messaging-spec.md.example`, `KafkaProducerAdapter.java.example`, `OutboxRelayPublisher.java.example` | no `ack-mode: manual`; § 4 retry mode, classification rows, no-op row; § 5 ack mode derived; producer and relay no longer point at a consumer's handler |
| `.claude/skills/messaging-architect/SKILL.md` | steps 5, 6, 7 and the partial's contract rows |
| `.claude/skills/use-case-design/SKILL.md`, `templates/use-case-spec.md.example` | a message situation is a failure or a no-op, never "discarded" |
| `.claude/agents/java-spring-boot-developer.md` | Form A consumer bullets, shared error handling NEW/REUSE, `@LogExecution` iff commons installed, validation list |
| `.claude/skills/http-client-architect/templates/IntegrationExceptions.java.example` | missing Lombok imports |
| `.claude/schemas/extensions.json` | `migrations` entry `kafka-retry-in-container` |
| `CLAUDE.md`, `project-bootstrap/templates/root.CLAUDE.md.example` | Kafka routing rows |
| `.claude/.ci/MessagingRetryTemplateTest.java`, `.github/workflows/validate.yml`, `docs/en/07-ci-validate.md`, `docs/pt-br/07-ci-validate.md` | the CI check, below |

Goes to the generated project: **yes** — norms, development skills, the executor and
`extensions.json` travel through `export`; `bank-app` gets the `migrations` prompt on its next
`arch-adopt` update.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate` · `design` › `Kafka consumer exemplars leave retry and commit to the container and retry a lost race` | `MessagingRetryTemplateTest`: no listener exemplar acknowledges, sleeps or loops; no yml `ack-mode: manual`; `@LogExecution` conditioned in the header; one classification by type applied to both shapes; `.dlq`, index-suffixed retry topics, no framework DLQ consumer; `ConcurrentUpdateException` thrown by every optimistic-lock translation | green on the tree · red with an `Acknowledgment` put back, with the header condition reworded, and with a `ConflictException("ORDER_CONCURRENT_UPDATE"…)` — each named by file |
| `validate` · `design` › `exemplar bodies carry no comment but Javadoc` | the new exemplars carry their instructions in the header and Javadoc only | green |
| `validate` · `exemplar-imports` | every import of the new exemplars resolves in a real Initializr project | covered by the existing job |
| `validate` › `schema` · `MigrationsSchemaTest` | the `migrations` entry's fields and blueprints | green |
| none — run once by hand | the shapes run against a broker (§ Verified before committing) | a Kafka broker in CI is minutes and Docker; the offline test holds what the run proved |
