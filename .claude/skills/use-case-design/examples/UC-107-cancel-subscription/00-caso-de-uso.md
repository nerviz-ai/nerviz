# UC-107 · Cancel subscription

> Parent spec. Fixes the boundary and the names; **doesn't** detail any layer.
> Per-layer detail lives in this folder's partials, each with its own owner.
>
> **Example spec** — see `.claude/skills/use-case-design/examples/README.md`. Written
> to exercise `/new-feature` and `java-spring-boot-developer`; the business rule below
> is a candidate symptom for `java-patterns`, not a pattern chosen here.

| Field | Value |
|---|---|
| Identifier | `UC-107-cancel-subscription` |
| Date | 2026-09-09 |
| Trigger | a customer-support agent cancels a subscription, over HTTP — inbound REST adapter |
| Side effects | **1** — `UPDATE` on `subscriptions`, recorded as a queued, undoable action |
| Storage | `Subscription` → relational — chosen by: request (the aggregate already lives in a table) |
| Search index | none |
| Transaction | opens in the use case, closes before the response; the undo, if it happens, is a separate transaction |
| Idempotency | yes — `Idempotency-Key` required |
| Concurrency | optimistic lock (`version`) on `subscriptions` |
| Active blueprint | `clean-architecture-single-module` |

## Trigger, payload, and response

**In**

| Field | Type | Required | Rule |
|---|---|---|---|
| `subscriptionId` | UUID (path) | ✅ | must reference an active subscription |
| `reason` | string | ✅ | one of a fixed set of CS reason codes |
| `agentId` | UUID | ✅ | the CS agent issuing the cancellation |
| `Idempotency-Key` | header | ✅ | per `@.claude/rules/api-rest.md` |

**Out**

| Situation | Result |
|---|---|
| cancellation queued | subscription reference, grace-window end timestamp |
| subscription not active | rejected without touching the domain |

## Flow

1. The inbound adapter receives the request and validates the payload's shape.
2. Translates it into the command `CancelSubscriptionCommand`.
3. The use case `CancelSubscriptionUseCase` doesn't cancel synchronously — the
   action is captured as an object (who requested it, when, why, on what target) so it
   can be logged, retried if the downstream billing call fails, and **undone** within a
   grace window if the customer calls back before it takes effect. A direct method call
   that mutates the subscription and returns can't be logged after the fact, retried
   independently of the HTTP request, or reversed without re-deriving the original
   request.
4. On success, `Subscription` moves to `PENDING_CANCELLATION`, effective at the end of
   the grace window; a scheduled job (out of scope) finalizes it if not undone.

## Components

State: **NEW** to be created · **CHANGE** already exists and changes · **REUSE**
already exists and serves.

| File | Layer | Role | State | Detailed by |
|---|---|---|---|---|
| `infrastructure/rest/SubscriptionCancellationController.java` | infrastructure (in) | receives HTTP | NEW | `rest-api-architect` |
| `infrastructure/rest/dto/CancelSubscriptionRequest.java` | infrastructure (in) | input DTO | NEW | `rest-api-architect` |
| `infrastructure/rest/dto/CancellationResponse.java` | infrastructure (in) | output DTO | NEW | `rest-api-architect` |
| `application/usecase/subscription/CancelSubscriptionCommand.java` | application | command | NEW | `domain-modeling` |
| `application/usecase/subscription/CancelSubscriptionUseCase.java` | application | orchestrates, opens transaction | NEW | `domain-modeling` |
| `application/port/SubscriptionRepository.java` | application | outbound port | REUSE | `domain-modeling` |
| `application/port/CancellationAuditLog.java` | application | outbound port — records who/when/why | NEW | `domain-modeling` |
| `domain/model/Subscription.java` | domain | aggregate | CHANGE — adds `PENDING_CANCELLATION` and its undo | `domain-modeling` |
| `domain/exception/SubscriptionNotActiveException.java` | domain | exception | NEW | `domain-modeling` |
| `infrastructure/persistence/cancellationaudit/CancellationAuditLogAdapter.java` | infrastructure (out) | persists the audit record | NEW | `persistence-architect` |
| `db/migration/V8__create_cancellation_audit_log.sql` | infrastructure | migration | NEW | `persistence-architect` |

## Invariants

| Rule | Guaranteed by | Who violates it |
|---|---|---|
| Cancellation only applies to an `ACTIVE` subscription | `Subscription`'s guard | agent cancelling an already-cancelled one |
| Every cancellation request is auditable (agent, reason, timestamp), independent of whether it's later undone | `CancellationAuditLog` | a code path that mutates state without logging |
| The subscription is only truly cancelled after the grace window, and only if not undone | `Subscription`'s state machine | a job that finalizes before the window ends |

## Errors

| Scenario | Exception | Family | Expected result |
|---|---|---|---|
| subscription not `ACTIVE` | `SubscriptionNotActiveException` | Conflict | 409 |
| unknown reason code | `ValidationException` | Validation | 400 |

## Expected tests

| Level | Target | Detailed by |
|---|---|---|
| unit | `Subscription`: transitions to `PENDING_CANCELLATION` only from `ACTIVE`, undo restores `ACTIVE` within the window | `test-architect` |
| unit | `CancelSubscriptionUseCase`: retries a failed audit write, doesn't retry a failed state transition | `test-architect` |
| slice | controller: 200, 400, 409 | `test-architect` |
| integration | `CancellationAuditLogAdapter` against a real database | `test-architect` |

## Out of scope for this use case

- Undoing the cancellation → its own trigger (`POST .../undo-cancellation`), same
  aggregate, separate use case.
- The scheduled job that finalizes cancellation after the grace window → an
  operational job, its own spec.
- Billing proration on cancellation → a finance concern, separate boundary.

## Implementation order

- [ ] 1. `domain/exception/SubscriptionNotActiveException.java`
- [ ] 2. `domain/model/Subscription.java` (change)
- [ ] 3. `application/usecase/subscription/CancelSubscriptionCommand.java`
- [ ] 4. `application/port/CancellationAuditLog.java`
- [ ] 5. `application/usecase/subscription/CancelSubscriptionUseCase.java`
- [ ] 6. `db/migration/V8__create_cancellation_audit_log.sql`
- [ ] 7. `infrastructure/persistence/cancellationaudit/CancellationAuditLogAdapter.java`
- [ ] 8. `infrastructure/rest/`
- [ ] 9. tests for the levels above
- [ ] 10. `./mvnw clean verify` green
