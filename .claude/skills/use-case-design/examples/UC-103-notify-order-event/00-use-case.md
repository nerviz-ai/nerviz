# UC-103 · Notify order event

> Parent spec. Fixes the boundary and the names; **doesn't** detail any layer.
> Per-layer detail lives in this folder's partials, each with its own owner.
>
> **Example spec** — see `.claude/skills/use-case-design/examples/README.md`. Written
> to exercise `/new-feature` and `java-spring-boot-developer`; the business rule below
> is a candidate symptom for `java-patterns`, not a pattern chosen here.

| Field | Value |
|---|---|
| Identifier | `UC-103-notify-order-event` |
| Date | 2026-09-09 |
| Trigger | reacts to `OrderConfirmed` — no HTTP; consumed from the internal event bus |
| Side effects | **1** — sends a message through the customer's preferred channel (email, SMS, or push) |
| Storage | `NotificationLog` (the audit record) → relational — chosen by: request (it names the `notification_log` table) |
| Search index | none |
| Audit | none |
| Transaction | none open across the send — the send is not transactional with anything else |
| Idempotency | yes — same `orderId` + channel sent twice must not double-notify the customer |
| Concurrency | dedup key `(orderId, channel)` is the arbiter |
| Active blueprint | `clean-architecture-single-module` |

## Trigger, payload, and response

**In**

| Field | Type | Required | Rule |
|---|---|---|---|
| `orderId` | UUID | ✅ | from the `OrderConfirmed` event |
| `customerId` | UUID | ✅ | from the `OrderConfirmed` event |

**Out**

| Situation | Result |
|---|---|
| sent | notification recorded as sent |
| channel unavailable after retries exhausted | recorded as failed, not retried further |
| already sent for this order and channel | no-op, not an error |

## Flow

1. The event listener receives `OrderConfirmed`.
2. Translates it into the domain command `NotifyOrderEventCommand`.
3. The use case `NotifyOrderEventUseCase` resolves the customer's preferred
   channel, checks the dedup key, and asks `NotificationSender` to send.
4. Sending must survive a transient failure (retry a bounded number of times), must be
   auditable (who was notified, when, through which channel, and whether it succeeded),
   and must not exceed a per-channel rate limit — none of which the base
   `NotificationSender` call provides by itself, and no existing `@Retryable` /
   `@Cacheable` combination already covers all three together.
5. The outcome is recorded through `NotificationLog` (outbound port).

## Components

State: **NEW** to be created · **CHANGE** already exists and changes · **REUSE**
already exists and serves.

| File | Layer | Role | State | Detailed by |
|---|---|---|---|---|
| `infrastructure/messaging/OrderConfirmedListener.java` | infrastructure (in) | consumes the event | NEW | `rest-api-architect` |
| `application/usecase/order/NotifyOrderEventCommand.java` | application | command | NEW | `domain-modeling` |
| `application/usecase/order/NotifyOrderEventUseCase.java` | application | orchestrates | NEW | `domain-modeling` |
| `application/port/NotificationSender.java` | application | outbound port — base send, one implementation per channel | NEW | `domain-modeling` |
| `application/port/NotificationLog.java` | application | outbound port — audit record | NEW | `domain-modeling` |
| `domain/model/NotificationChannel.java` | domain | value object | NEW | `domain-modeling` |
| `domain/exception/NotificationDeliveryFailedException.java` | domain | exception | NEW | `domain-modeling` |
| `infrastructure/notification/EmailNotificationSenderAdapter.java` | infrastructure (out) | implements the port for email | NEW | `persistence-architect` |
| `infrastructure/notification/NotificationLogAdapter.java` | infrastructure (out) | persists the audit record | NEW | `persistence-architect` |
| `db/migration/V6__create_notification_log.sql` | infrastructure | migration | NEW | `persistence-architect` |

## Invariants

| Rule | Guaranteed by | Who violates it |
|---|---|---|
| A given `(orderId, channel)` pair is never sent twice | dedup check before send | the listener retrying the whole event |
| A failed send is retried a bounded number of times, then recorded as failed, never silently dropped | `NotifyOrderEventUseCase` | a sender that swallows the exception |
| Every send attempt (success or failure) is audited | `NotificationLog` | a code path that sends without logging |

## Errors

| Scenario | Exception | Family | Expected result |
|---|---|---|---|
| channel unavailable after retries exhausted | `NotificationDeliveryFailedException` | External | recorded as failed, event acknowledged (not re-queued indefinitely) |

No REST surface — this use case has no HTTP error mapping; `40-tests.md` covers the
retry/audit/rate-limit behavior instead.

## Expected tests

| Level | Target | Detailed by |
|---|---|---|
| unit | `NotifyOrderEventUseCase`: retries a transient failure, gives up after the bound, skips an already-sent pair | `test-architect` |
| unit | rate limit is respected per channel | `test-architect` |
| integration | `NotificationLogAdapter` persists every attempt | `test-architect` |

## Out of scope for this use case

- Choosing the customer's preferred channel in the first place → customer profile
  management, its own use case.
- Notification templates/content → a content-management concern, not this boundary.
- In-app notification center (read/unread state) → separate aggregate.

## Implementation order

- [ ] 1. `domain/model/NotificationChannel.java`
- [ ] 2. `domain/exception/NotificationDeliveryFailedException.java`
- [ ] 3. `application/usecase/order/NotifyOrderEventCommand.java`
- [ ] 4. `application/port/NotificationSender.java`
- [ ] 5. `application/port/NotificationLog.java`
- [ ] 6. `application/usecase/order/NotifyOrderEventUseCase.java`
- [ ] 7. `db/migration/V6__create_notification_log.sql`
- [ ] 8. `infrastructure/notification/`
- [ ] 9. `infrastructure/messaging/OrderConfirmedListener.java`
- [ ] 10. tests for the levels above
- [ ] 11. `./mvnw clean verify` green
