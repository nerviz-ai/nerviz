# UC-104 · Search orders

> Parent spec. Fixes the boundary and the names; **doesn't** detail any layer.
> Per-layer detail lives in this folder's partials, each with its own owner.
>
> **Example spec** — see `.claude/skills/use-case-design/examples/README.md`. Written
> to exercise `/new-feature` and `java-spring-boot-developer`; the business rule below
> is a candidate symptom for `java-patterns`, not a pattern chosen here.
>
> **Admitted exception applies.** This is a pure read query
> (`@.claude/rules/architecture-ddd.md` § Admitted exception) — the ADR line below
> records it.

| Field | Value |
|---|---|
| Identifier | `UC-104-search-orders` |
| Date | 2026-09-09 |
| Trigger | order search, over HTTP — inbound REST adapter |
| Side effects | **0** — read-only |
| Storage | `Order` → relational (read) — chosen by: request (the aggregate already lives in a table) |
| Search index | none |
| Audit | none |
| Transaction | read-only, no explicit transaction boundary needed |
| Idempotency | yes — a query, safe to repeat |
| Concurrency | not applicable |
| Active blueprint | `clean-architecture-single-module` |
| ADR | pure read query, infrastructure (in) → infrastructure (out) directly, no domain crossing (`architecture-ddd.md` § Admitted exception) |

## Trigger, payload, and response

**In**

| Field | Type | Required | Rule |
|---|---|---|---|
| `status` | string | ➖ | one of `Order`'s known statuses |
| `dateFrom`, `dateTo` | date | ➖ | required together if either is present |
| `customerSegment` | string | ➖ | one of the known segments |
| `minValue` | decimal | ➖ | ≥ 0 |
| `page`, `size` | integer | ➖ | pagination, default `page=0`, `size=20` |

**Out**

| Situation | Result |
|---|---|
| results found | paginated list of order summaries |
| no results | empty page, not an error |
| invalid filter combination | rejected without querying |

## Flow

1. The inbound adapter receives the query parameters and validates the payload's shape.
2. Translates it into `OrderSearchCriteria` — a composition of independent, optional
   predicates (status, date range, segment, minimum value) that combine with AND.
3. The use case `SearchOrdersUseCase` passes `OrderSearchCriteria` straight to the
   outbound query adapter — this is the admitted read-only exception, no aggregate
   loaded.
4. The same `OrderSearchCriteria` shape is reused, unmodified, by the nightly
   `OrderBacklogReportJob` (out of scope here, cited for context) to build its report
   query — the predicate composition is shared, not duplicated.
5. The adapter translates the page of results into the response.

## Components

State: **NEW** to be created · **CHANGE** already exists and changes · **REUSE**
already exists and serves.

| File | Layer | Role | State | Detailed by |
|---|---|---|---|---|
| `infrastructure/rest/OrderSearchController.java` | infrastructure (in) | receives HTTP | NEW | `rest-api-architect` |
| `infrastructure/rest/dto/OrderSearchResponse.java` | infrastructure (in) | output DTO | NEW | `rest-api-architect` |
| `infrastructure/rest/OrderSearchMapper.java` | infrastructure (in) | DTO ↔ criteria | NEW | `rest-api-architect` |
| `application/usecase/order/SearchOrdersUseCase.java` | application | passes criteria to the outbound query | NEW | `domain-modeling` |
| `application/port/OrderSearchQuery.java` | application | outbound port — read-only query | NEW | `domain-modeling` |
| `domain/model/OrderSearchCriteria.java` | domain | value object — the reusable predicate composition | NEW | `domain-modeling` |
| `infrastructure/persistence/order/OrderSearchQueryAdapter.java` | infrastructure (out) | implements the query | NEW | `persistence-architect` |

## Invariants

| Rule | Guaranteed by | Who violates it |
|---|---|---|
| `dateFrom` and `dateTo` are required together | `OrderSearchCriteria`'s constructor | caller sending only one bound |
| `minValue`, when present, is not negative | `OrderSearchCriteria`'s constructor | malformed payload |
| Page size stays within the configured maximum | `OrderSearchCriteria`'s constructor | caller requesting an oversized page |

## Errors

| Scenario | Exception | Family | Expected result |
|---|---|---|---|
| invalid filter combination | `ValidationException` | Validation | 400 |

## Expected tests

| Level | Target | Detailed by |
|---|---|---|
| unit | `OrderSearchCriteria`: rejects each invalid combination, composes predicates correctly | `test-architect` |
| slice | controller: 200 with and without filters, 400 on invalid combination | `test-architect` |
| integration | `OrderSearchQueryAdapter` against a real database, every filter combination | `test-architect` |

## Out of scope for this use case

- The nightly `OrderBacklogReportJob` itself → cited only to justify why the criteria
  is a shared, independent value object instead of inline query parameters.
- Full-text search on order notes → different technology, different use case.
- Exporting search results → **UC-110** covers export, for a different aggregate.

## Implementation order

- [ ] 1. `domain/model/OrderSearchCriteria.java`
- [ ] 2. `application/port/OrderSearchQuery.java`
- [ ] 3. `application/usecase/order/SearchOrdersUseCase.java`
- [ ] 4. `infrastructure/persistence/order/OrderSearchQueryAdapter.java`
- [ ] 5. `infrastructure/rest/` (DTO, mapper, controller)
- [ ] 6. tests for the levels above
- [ ] 7. `./mvnw clean verify` green
