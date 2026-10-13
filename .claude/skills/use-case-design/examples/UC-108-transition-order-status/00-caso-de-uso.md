# UC-108 · Transition order status

> Parent spec. Fixes the boundary and the names; **doesn't** detail any layer.
> Per-layer detail lives in this folder's partials, each with its own owner.
>
> **Example spec** — see `.claude/skills/use-case-design/examples/README.md`. Written
> to exercise `/new-feature` and `java-spring-boot-developer`; the business rule below
> is a candidate symptom for `java-patterns`, not a pattern chosen here.

| Field | Value |
|---|---|
| Identifier | `UC-108-transition-order-status` |
| Date | 2026-09-09 |
| Trigger | an operator moves an order to its next status, over HTTP — inbound REST adapter |
| Side effects | **1** — `UPDATE` on `orders.status` |
| Storage | `Order` → relational — chosen by: request (the aggregate already lives in a table) |
| Search index | none |
| Audit | none |
| Transaction | opens in the use case, closes before the response |
| Idempotency | no — a given transition is a one-time state change; retried with the same target status while already there is a no-op, to a different status is a conflict |
| Concurrency | optimistic lock (`version`) on `orders` |
| Active blueprint | `clean-architecture-single-module` |

## Trigger, payload, and response

**In**

| Field | Type | Required | Rule |
|---|---|---|---|
| `orderId` | UUID (path) | ✅ | must reference an existing order |
| `targetStatus` | string | ✅ | one of `CREATED`, `PAID`, `SHIPPED`, `DELIVERED`, `CANCELLED` |

**Out**

| Situation | Result |
|---|---|
| transitioned | order reference, new status |
| transition not allowed from the current status | rejected without touching the domain |

## Flow

1. The inbound adapter receives `orderId` and `targetStatus`.
2. Translates it into `TransitionOrderStatusCommand`.
3. The use case `TransitionOrderStatusUseCase` loads the `Order` aggregate and
   calls `order.transitionTo(targetStatus)`.
4. Each status allows a different, fixed set of next statuses and enforces different
   validations before allowing the move — `CREATED → PAID` requires a successful
   authorization (**UC-105**), `PAID → SHIPPED` requires a tracking number already on
   file, `SHIPPED → DELIVERED` requires a delivery confirmation, and `CANCELLED` is only
   reachable from `CREATED` or `PAID`, never after `SHIPPED`. The aggregate itself
   decides what's legal from where it currently stands, not the caller.
5. `OrderRepository` persists the new status.

## Components

State: **NEW** to be created · **CHANGE** already exists and changes · **REUSE**
already exists and serves.

| File | Layer | Role | State | Detailed by |
|---|---|---|---|---|
| `infrastructure/rest/OrderStatusController.java` | infrastructure (in) | receives HTTP | NEW | `rest-api-architect` |
| `infrastructure/rest/dto/TransitionOrderStatusRequest.java` | infrastructure (in) | input DTO | NEW | `rest-api-architect` |
| `infrastructure/rest/dto/OrderStatusResponse.java` | infrastructure (in) | output DTO | NEW | `rest-api-architect` |
| `application/usecase/order/TransitionOrderStatusCommand.java` | application | command | NEW | `domain-modeling` |
| `application/usecase/order/TransitionOrderStatusUseCase.java` | application | orchestrates | NEW | `domain-modeling` |
| `application/port/OrderRepository.java` | application | outbound port | REUSE | `domain-modeling` |
| `domain/model/Order.java` | domain | aggregate | CHANGE — adds `transitionTo(OrderStatus)` | `domain-modeling` |
| `domain/model/OrderStatus.java` | domain | value object — legal-next-status table | NEW | `domain-modeling` |
| `domain/exception/IllegalOrderTransitionException.java` | domain | exception | NEW | `domain-modeling` |
| `infrastructure/persistence/order/OrderRepositoryAdapter.java` | infrastructure (out) | implements the port | REUSE | `persistence-architect` |

## Invariants

| Rule | Guaranteed by | Who violates it |
|---|---|---|
| A transition only succeeds if `targetStatus` is in the current status's allowed-next set | `Order.transitionTo` | caller requesting `CREATED → DELIVERED` directly |
| `CANCELLED` is unreachable once `SHIPPED` | `OrderStatus`'s table | caller cancelling a shipped order |
| Re-requesting the current status is a no-op, not an error | `Order.transitionTo` guard | a retried request |

## Errors

| Scenario | Exception | Family | Expected result |
|---|---|---|---|
| transition not allowed from current status | `IllegalOrderTransitionException` | Conflict | 409 |
| unknown target status | `ValidationException` | Validation | 400 |

## Expected tests

| Level | Target | Detailed by |
|---|---|---|
| unit | `Order`: every legal transition succeeds, every illegal one is rejected, current-status re-request is a no-op | `test-architect` |
| unit | `OrderStatus`: allowed-next set is exhaustive for all five statuses | `test-architect` |
| slice | controller: 200, 400, 409 | `test-architect` |
| integration | `OrderRepositoryAdapter` persists the new status | `test-architect` |

## Out of scope for this use case

- What triggers each transition in practice (payment webhook, carrier scan, delivery
  confirmation) → each has its own use case; this one is the shared status-change
  boundary they all funnel through.
- Notifying the customer on each transition → **UC-103**, reacts to the resulting
  event, not detailed here.

## Implementation order

- [ ] 1. `domain/model/OrderStatus.java`
- [ ] 2. `domain/exception/IllegalOrderTransitionException.java`
- [ ] 3. `domain/model/Order.java` (change)
- [ ] 4. `application/usecase/order/TransitionOrderStatusCommand.java`
- [ ] 5. `application/usecase/order/TransitionOrderStatusUseCase.java`
- [ ] 6. `infrastructure/rest/`
- [ ] 7. tests for the levels above
- [ ] 8. `./mvnw clean verify` green
