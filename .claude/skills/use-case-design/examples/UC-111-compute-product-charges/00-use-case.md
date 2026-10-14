# UC-111 · Compute product charges

> Parent spec. Fixes the boundary and the names; **doesn't** detail any layer.
> Per-layer detail lives in this folder's partials, each with its own owner.
>
> **Example spec** — see `.claude/skills/use-case-design/examples/README.md`. Written
> to exercise `/new-feature` and `java-spring-boot-developer`; the business rule below
> is a candidate symptom for `java-patterns`, not a pattern chosen here.

| Field | Value |
|---|---|
| Identifier | `UC-111-compute-product-charges` |
| Date | 2026-09-09 |
| Trigger | charge computation for a product at checkout time, over HTTP — inbound REST adapter |
| Side effects | **0** — pure computation |
| Storage | `Product` → relational (read) — chosen by: request (the aggregate already lives in a table) |
| Search index | none |
| Audit | none |
| Transaction | read-only |
| Idempotency | yes |
| Concurrency | not applicable |
| Active blueprint | `clean-architecture-single-module` |

## Trigger, payload, and response

**In**

| Field | Type | Required | Rule |
|---|---|---|---|
| `productId` | UUID | ✅ | must reference an existing product |
| `destinationZip` | string | ✅ | valid postal code |
| `quantity` | integer | ✅ | > 0 |

**Out**

| Situation | Result |
|---|---|
| computed | `taxAmount`, `shippingEligible`, `exportPayload` (a serialized snapshot for the tax authority feed) |
| product not found | resource does not exist |

## Flow

1. The inbound adapter receives the request and validates the payload's shape.
2. Translates it into `ComputeProductChargesCommand`.
3. The use case `ComputeProductChargesUseCase` fetches the `Product` — which is
   one of a fixed hierarchy of concrete types (`PhysicalGood`, `DigitalGood`,
   `Subscription`) already established by product creation, out of scope here — and
   runs three unrelated operations over it: tax calculation, shipping-eligibility
   check, and export-payload serialization. Each operation behaves differently per
   concrete product type (a `DigitalGood` has no shipping eligibility at all, a
   `Subscription` computes tax on a recurring basis), and the set of operations keeps
   growing independently of the product hierarchy itself — adding "compute loyalty
   points" shouldn't require touching `PhysicalGood`, `DigitalGood`, or `Subscription`.
4. The three results are combined into the response.

## Components

State: **NEW** to be created · **CHANGE** already exists and changes · **REUSE**
already exists and serves.

| File | Layer | Role | State | Detailed by |
|---|---|---|---|---|
| `infrastructure/rest/ProductChargesController.java` | infrastructure (in) | receives HTTP | NEW | `rest-api-architect` |
| `infrastructure/rest/dto/ProductChargesResponse.java` | infrastructure (in) | output DTO | NEW | `rest-api-architect` |
| `application/usecase/product/ComputeProductChargesCommand.java` | application | command | NEW | `domain-modeling` |
| `application/usecase/product/ComputeProductChargesUseCase.java` | application | orchestrates the three operations | NEW | `domain-modeling` |
| `application/port/ProductRepository.java` | application | outbound port | REUSE | `domain-modeling` |
| `domain/model/Product.java` | domain | aggregate — fixed hierarchy (`PhysicalGood`, `DigitalGood`, `Subscription`) | REUSE | `domain-modeling` |
| `domain/model/ProductCharges.java` | domain | value object — tax, eligibility, export payload | NEW | `domain-modeling` |

## Invariants

| Rule | Guaranteed by | Who violates it |
|---|---|---|
| A `DigitalGood` is never shipping-eligible | the per-type computation | a shipping check that assumes every product has weight |
| Tax computation is per-type (one-time vs. recurring) but always non-negative | `ProductCharges`'s construction | a negative tax result |
| The export payload always reflects the same inputs as the tax and eligibility results — computed once, not re-derived per field | `ComputeProductChargesUseCase` | inconsistent snapshots across the three fields |

## Errors

| Scenario | Exception | Family | Expected result |
|---|---|---|---|
| product not found | (adapter-level lookup) | — | 404 |
| invalid quantity | `ValidationException` | Validation | 400 |

## Expected tests

| Level | Target | Detailed by |
|---|---|---|
| unit | tax, eligibility, and export payload are each correct per concrete product type | `test-architect` |
| unit | `DigitalGood` is never shipping-eligible | `test-architect` |
| slice | controller: 200, 400, 404 | `test-architect` |

## Out of scope for this use case

- Persisting the computed charges → this is a quote, not a committed charge; committing
  happens at order confirmation (**UC-106**).
- Adding new product types → product modeling, a separate concern from this
  computation.
- Loyalty points, gift wrapping, or other future per-product computations → each would
  extend this use case's set of operations, not its trigger.

## Implementation order

- [ ] 1. `domain/model/ProductCharges.java`
- [ ] 2. `application/usecase/product/ComputeProductChargesCommand.java`
- [ ] 3. `application/usecase/product/ComputeProductChargesUseCase.java`
- [ ] 4. `infrastructure/rest/`
- [ ] 5. tests for the levels above
- [ ] 6. `./mvnw clean verify` green
