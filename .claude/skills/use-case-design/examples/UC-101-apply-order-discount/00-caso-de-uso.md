# UC-101 · Apply order discount

> Parent spec. Fixes the boundary and the names; **doesn't** detail any layer.
> Per-layer detail lives in this folder's partials, each with its own owner.
>
> **Example spec** — see `.claude/skills/use-case-design/examples/README.md`. Written
> to exercise `/new-feature` and `java-spring-boot-developer`; the business rule below
> is a candidate symptom for `java-patterns`, not a pattern chosen here.

| Field | Value |
|---|---|
| Identifier | `UC-101-apply-order-discount` |
| Date | 2026-09-09 |
| Trigger | recompute and apply a discount to an existing order, over HTTP — inbound REST adapter |
| Side effects | **1** — `UPDATE` on `orders` (`discount_percentage`, `total`) |
| Storage | `Order` → relational — chosen by: request (the aggregate already lives in a table) |
| Search index | none |
| Audit | none |
| Transaction | opens in the use case, closes before the response |
| Idempotency | no while the order isn't `PAID` — reapplying yields the same result for the same tier; once `PAID`, reapplying is a conflict, not a repeat |
| Concurrency | optimistic lock (`version` column) on `orders` |
| Active blueprint | `clean-architecture-single-module` |

## Trigger, payload, and response

**In**

| Field | Type | Required | Rule |
|---|---|---|---|
| `orderId` | UUID (path) | ✅ | must reference an existing order |

**Out**

| Situation | Result |
|---|---|
| applied | order reference + `discountPercentage`, `total` |
| order already paid | conflict with existing state |
| order not found | resource does not exist |

## Flow

1. The inbound adapter receives `orderId`.
2. Translates it into the domain command `ApplyOrderDiscountCommand`.
3. The use case `ApplyOrderDiscountUseCase` loads the `Order` aggregate (already
   exists — created by an earlier, out-of-scope use case) and calls
   `order.applyDiscount(customerTier)`.
4. The `Order` aggregate resolves the discount percentage for the customer's tier from
   its own fixed mapping, guards that the order isn't already `PAID`, and recomputes
   the total.
5. `OrderRepository` persists the change.
6. The adapter translates the returned aggregate into the response.

## Components

State: **NEW** to be created · **CHANGE** already exists and changes · **REUSE**
already exists and serves.

| File | Layer | Role | State | Detailed by |
|---|---|---|---|---|
| `infrastructure/rest/OrderDiscountController.java` | infrastructure (in) | receives HTTP | NEW | `rest-api-architect` |
| `infrastructure/rest/dto/OrderDiscountResponse.java` | infrastructure (in) | output DTO | NEW | `rest-api-architect` |
| `infrastructure/rest/OrderDiscountMapper.java` | infrastructure (in) | DTO ↔ domain | NEW | `rest-api-architect` |
| `application/usecase/order/ApplyOrderDiscountCommand.java` | application | command | NEW | `domain-modeling` |
| `application/usecase/order/ApplyOrderDiscountUseCase.java` | application | orchestrates, opens transaction | NEW | `domain-modeling` |
| `application/port/OrderRepository.java` | application | outbound port | REUSE | `domain-modeling` |
| `domain/model/Order.java` | domain | aggregate | CHANGE — adds `applyDiscount` | `domain-modeling` |
| `domain/model/CustomerTier.java` | domain | value object — tier → percentage mapping | NEW | `domain-modeling` |
| `domain/exception/OrderAlreadyPaidException.java` | domain | exception | NEW | `domain-modeling` |
| `infrastructure/persistence/order/OrderRepositoryAdapter.java` | infrastructure (out) | implements the port | REUSE | `persistence-architect` |
| `db/migration/V5__add_order_discount_columns.sql` | infrastructure | migration | NEW | `persistence-architect` |

## Invariants

| Rule | Guaranteed by | Who violates it |
|---|---|---|
| Discount percentage is fixed per `CustomerTier` (Bronze 0%, Silver 5%, Gold 10%, Platinum 15%); new tiers are appended without changing existing ones | `CustomerTier`'s mapping | caller passing an unknown tier |
| `total` is never negative | `Order`'s constructor and `applyDiscount` | discount percentage above 100% |
| A `PAID` order's discount is never recomputed | `Order.applyDiscount` guard | caller retrying after payment |

## Errors

| Scenario | Exception | Family | Expected result |
|---|---|---|---|
| order already `PAID` | `OrderAlreadyPaidException` | Conflict | 409 |
| unknown customer tier | `ValidationException` | Validation | 400 |
| order does not exist | (adapter-level lookup) | — | 404 |

Family and `errorCode` follow `@.claude/rules/error-handling.md`. HTTP mapping belongs
to `30-rest.md`.

## Expected tests

| Level | Target | Detailed by |
|---|---|---|
| unit | `Order`: applies the correct percentage per tier, rejects when `PAID` | `test-architect` |
| unit | `CustomerTier`: rejects a value outside the known set | `test-architect` |
| slice | controller: 200, 404, and 409 | `test-architect` |
| integration | `OrderRepositoryAdapter` persists `discount_percentage` and `total` | `test-architect` |

## Out of scope for this use case

- Creating the order → earlier use case, not detailed here.
- Coupon codes stacked on top of the tier discount → future UC, needs its own
  combination rule.
- Expiring or reverting an applied discount → its own trigger.

## Implementation order

- [ ] 1. `domain/model/CustomerTier.java`
- [ ] 2. `domain/model/Order.java` (change)
- [ ] 3. `domain/exception/OrderAlreadyPaidException.java`
- [ ] 4. `application/usecase/order/ApplyOrderDiscountCommand.java`
- [ ] 5. `application/usecase/order/ApplyOrderDiscountUseCase.java`
- [ ] 6. `db/migration/V5__add_order_discount_columns.sql`
- [ ] 7. `infrastructure/persistence/order/` (adapter change, if needed)
- [ ] 8. `infrastructure/rest/` (DTO, mapper, controller)
- [ ] 9. tests for the levels above
- [ ] 10. `./mvnw clean verify` green
