# UC-106 · Confirm order

> Parent spec. Fixes the boundary and the names; **doesn't** detail any layer.
> Per-layer detail lives in this folder's partials, each with its own owner.
>
> **Example spec** — see `.claude/skills/use-case-design/examples/README.md`. Written
> to exercise `/new-feature` and `java-spring-boot-developer`; the business rule below
> is a candidate symptom for `java-patterns`, not a pattern chosen here.

| Field | Value |
|---|---|
| Identifier | `UC-106-confirm-order` |
| Date | 2026-09-09 |
| Trigger | order confirmation, over HTTP — inbound REST adapter |
| Side effects | **1** direct — `UPDATE` on `orders`; **2** reactive, out of this boundary — inventory reservation and customer notification |
| Storage | `Order` → relational — chosen by: request (the aggregate already lives in a table) |
| Search index | none |
| Transaction | opens in the use case, closes before the response; the reactive consumers run in their own transactions, after this one commits |
| Idempotency | yes — confirming an already-confirmed order is a no-op, not an error |
| Concurrency | optimistic lock (`version`) on `orders` |
| Active blueprint | `clean-architecture-single-module` |

## Trigger, payload, and response

**In**

| Field | Type | Required | Rule |
|---|---|---|---|
| `orderId` | UUID (path) | ✅ | must reference an existing, authorized order |

**Out**

| Situation | Result |
|---|---|
| confirmed | order reference, `OrderConfirmed` published |
| already confirmed | order reference, no event republished |
| order not authorized yet | rejected without touching the domain |

## Flow

1. The inbound adapter receives `orderId`.
2. Translates it into `ConfirmOrderCommand`.
3. The use case `ConfirmOrderUseCase` loads the `Order` aggregate, calls
   `order.confirm()`, which validates it's in the `AUTHORIZED` state and transitions to
   `CONFIRMED`, recording the domain event `OrderConfirmed`.
4. `OrderRepository` persists the state change and publishes `OrderConfirmed` after
   commit. After commit and not inside the transaction, because a rollback after a
   successful publish would leave both consumers acting on an order that was never
   confirmed. Publishing directly, and not through an outbox, because losing the event is
   recoverable here: an unreserved order surfaces in the next stock reconciliation and an
   unsent notification is visible to the customer. The criterion, and the case where the
   answer is the outbox instead (**UC-113**), is `@.claude/rules/messaging.md`
   § Publication timing.
5. Two independent consumers react to `OrderConfirmed`, each in its own module,
   neither aware of the other: inventory reservation (`InventoryModule`) and customer
   notification (**UC-103**, already specified). This use case's boundary ends at
   publishing the event — it doesn't call either consumer directly.

## Components

State: **NEW** to be created · **CHANGE** already exists and changes · **REUSE**
already exists and serves.

| File | Layer | Role | State | Detailed by |
|---|---|---|---|---|
| `infrastructure/rest/OrderConfirmationController.java` | infrastructure (in) | receives HTTP | NEW | `rest-api-architect` |
| `infrastructure/rest/dto/OrderConfirmationResponse.java` | infrastructure (in) | output DTO | NEW | `rest-api-architect` |
| `application/usecase/order/ConfirmOrderCommand.java` | application | command | NEW | `domain-modeling` |
| `application/usecase/order/ConfirmOrderUseCase.java` | application | orchestrates, opens transaction | NEW | `domain-modeling` |
| `application/port/OrderRepository.java` | application | outbound port | REUSE | `domain-modeling` |
| `application/port/DomainEventPublisher.java` | application | outbound port | REUSE | `domain-modeling` |
| `domain/model/Order.java` | domain | aggregate | CHANGE — adds `confirm()` | `domain-modeling` |
| `domain/event/OrderConfirmed.java` | domain | domain event | NEW | `domain-modeling` |
| `domain/exception/OrderNotAuthorizedException.java` | domain | exception | NEW | `domain-modeling` |
| `infrastructure/persistence/order/OrderRepositoryAdapter.java` | infrastructure (out) | implements the port | REUSE | `persistence-architect` |

## Invariants

| Rule | Guaranteed by | Who violates it |
|---|---|---|
| Only an `AUTHORIZED` order can be confirmed | `Order.confirm()` guard | caller confirming a `CREATED` or `CANCELLED` order |
| `OrderConfirmed` is published exactly once per order | published only on the state transition, not on every call | a naive implementation that republishes on every confirm call |
| Confirming twice is a no-op, never a second event | `Order.confirm()` idempotent guard | a retried request |

## Errors

| Scenario | Exception | Family | Expected result |
|---|---|---|---|
| order not `AUTHORIZED` | `OrderNotAuthorizedException` | Conflict | 409 |
| order not found | (adapter-level lookup) | — | 404 |

## Expected tests

| Level | Target | Detailed by |
|---|---|---|
| unit | `Order`: confirms only from `AUTHORIZED`, records `OrderConfirmed` exactly once, no-ops on retry | `test-architect` |
| unit | `ConfirmOrderUseCase`: publishes after commit, not before | `test-architect` |
| slice | controller: 200, 404, 409 | `test-architect` |
| integration | `OrderRepositoryAdapter` persists state and the outbox/event publication | `test-architect` |

## Out of scope for this use case

- Inventory reservation itself → `InventoryModule`, reacts to `OrderConfirmed`,
  detailed in its own use case.
- Customer notification → **UC-103**, already specified, reacts to the same event.
- What happens if reservation fails after confirmation → a compensating use case,
  triggered by a different event, not this one.

## Implementation order

- [ ] 1. `domain/event/OrderConfirmed.java`
- [ ] 2. `domain/exception/OrderNotAuthorizedException.java`
- [ ] 3. `domain/model/Order.java` (change)
- [ ] 4. `application/usecase/order/ConfirmOrderCommand.java`
- [ ] 5. `application/usecase/order/ConfirmOrderUseCase.java`
- [ ] 6. `infrastructure/rest/`
- [ ] 7. tests for the levels above
- [ ] 8. `./mvnw clean verify` green
