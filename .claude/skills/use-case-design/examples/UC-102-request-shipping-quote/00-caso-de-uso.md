# UC-102 · Request shipping quote

> Parent spec. Fixes the boundary and the names; **doesn't** detail any layer.
> Per-layer detail lives in this folder's partials, each with its own owner.
>
> **Example spec** — see `.claude/skills/use-case-design/examples/README.md`. Written
> to exercise `/new-feature` and `java-spring-boot-developer`; the business rule below
> is a candidate symptom for `java-patterns`, not a pattern chosen here.

| Field | Value |
|---|---|
| Identifier | `UC-102-request-shipping-quote` |
| Date | 2026-09-09 |
| Trigger | shipping cost quote request, over HTTP — inbound REST adapter |
| Side effects | **0** — pure computation, nothing persisted |
| Storage | none — the case reads and writes no stored aggregate |
| Search index | none |
| Transaction | none — no write |
| Idempotency | yes — same input always yields the same quote |
| Concurrency | not applicable — no shared state written |
| Active blueprint | `clean-architecture-single-module` |

## Trigger, payload, and response

**In**

| Field | Type | Required | Rule |
|---|---|---|---|
| `originZip` | string | ✅ | valid postal code |
| `destinationZip` | string | ✅ | valid postal code |
| `weightKg` | decimal | ✅ | > 0 |
| `lengthCm`, `widthCm`, `heightCm` | decimal | ➖ | required together if any is present |
| `declaredValue` | decimal | ➖ | required if `insured` is true |
| `insured` | boolean | ➖ | default `false` |
| `express` | boolean | ➖ | default `false` |
| `fragile` | boolean | ➖ | default `false` |

**Out**

| Situation | Result |
|---|---|
| quote computed | carrier, price, estimated delivery window |
| no carrier serves the destination | rejected — no route |
| invalid combination (e.g. `insured` without `declaredValue`) | rejected without touching the domain |

## Flow

1. The inbound adapter receives the request and validates the payload's shape.
2. Translates it into the domain command `RequestShippingQuoteCommand`, which the
   application layer turns into a `ShipmentRequest` value object — a valid combination
   of ≥ 6 optional attributes, some of which require each other.
3. The use case `RequestShippingQuoteUseCase` executes: resolves the shipping zone
   from origin/destination and computes the quote from `ShipmentRequest`.
4. No persistence — the response is built directly from the computed quote.
5. The adapter translates the result into the response.

## Components

State: **NEW** to be created · **CHANGE** already exists and changes · **REUSE**
already exists and serves.

| File | Layer | Role | State | Detailed by |
|---|---|---|---|---|
| `infrastructure/rest/ShippingQuoteController.java` | infrastructure (in) | receives HTTP | NEW | `rest-api-architect` |
| `infrastructure/rest/dto/ShippingQuoteRequest.java` | infrastructure (in) | input DTO | NEW | `rest-api-architect` |
| `infrastructure/rest/dto/ShippingQuoteResponse.java` | infrastructure (in) | output DTO | NEW | `rest-api-architect` |
| `infrastructure/rest/ShippingQuoteMapper.java` | infrastructure (in) | DTO ↔ domain | NEW | `rest-api-architect` |
| `application/usecase/shippingquote/RequestShippingQuoteCommand.java` | application | command | NEW | `domain-modeling` |
| `application/usecase/shippingquote/RequestShippingQuoteUseCase.java` | application | orchestrates | NEW | `domain-modeling` |
| `application/port/ShippingRateProvider.java` | application | outbound port | NEW | `domain-modeling` |
| `domain/model/ShipmentRequest.java` | domain | value object — the invalid-combination guard lives here | NEW | `domain-modeling` |
| `domain/model/ShippingQuote.java` | domain | value object — computed result | NEW | `domain-modeling` |
| `domain/exception/NoCarrierAvailableException.java` | domain | exception | NEW | `domain-modeling` |
| `infrastructure/rate/ShippingRateProviderAdapter.java` | infrastructure (out) | calls the carrier rate table/service | NEW | `persistence-architect` |

## Invariants

| Rule | Guaranteed by | Who violates it |
|---|---|---|
| `weightKg` is strictly positive | `ShipmentRequest`'s constructor | caller sending zero or negative weight |
| dimensions are all-or-nothing — either all three are present or none are | `ShipmentRequest`'s constructor | caller sending only `lengthCm` |
| `declaredValue` is required whenever `insured` is `true` | `ShipmentRequest`'s constructor | caller sending `insured=true` with no value |

## Errors

| Scenario | Exception | Family | Expected result |
|---|---|---|---|
| no carrier serves the destination | `NoCarrierAvailableException` | NotFound | 404 |
| invalid attribute combination | `ValidationException` | Validation | 400 |

## Expected tests

| Level | Target | Detailed by |
|---|---|---|
| unit | `ShipmentRequest`: rejects each invalid combination independently | `test-architect` |
| unit | `RequestShippingQuoteUseCase`: propagates `NoCarrierAvailableException` | `test-architect` |
| slice | controller: 200 and 400 | `test-architect` |
| integration | `ShippingRateProviderAdapter` against a stubbed rate source | `test-architect` |

## Out of scope for this use case

- Booking the shipment (creating a shipping label) → its own use case, has a side
  effect this one deliberately doesn't.
- Multi-parcel quotes → out, single parcel only.
- Currency conversion → assumes a single currency for now.

## Implementation order

- [ ] 1. `domain/model/ShipmentRequest.java`
- [ ] 2. `domain/model/ShippingQuote.java`
- [ ] 3. `domain/exception/NoCarrierAvailableException.java`
- [ ] 4. `application/usecase/shippingquote/RequestShippingQuoteCommand.java`
- [ ] 5. `application/port/ShippingRateProvider.java`
- [ ] 6. `application/usecase/shippingquote/RequestShippingQuoteUseCase.java`
- [ ] 7. `infrastructure/rate/ShippingRateProviderAdapter.java`
- [ ] 8. `infrastructure/rest/` (DTOs, mapper, controller)
- [ ] 9. tests for the levels above
- [ ] 10. `./mvnw clean verify` green
