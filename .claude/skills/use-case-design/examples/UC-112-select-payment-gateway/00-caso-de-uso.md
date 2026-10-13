# UC-112 · Select payment gateway

> Parent spec. Fixes the boundary and the names; **doesn't** detail any layer.
> Per-layer detail lives in this folder's partials, each with its own owner.
>
> **Example spec** — see `.claude/skills/use-case-design/examples/README.md`. Written
> to exercise `/new-feature` and `java-spring-boot-developer`; the business rule below
> is a candidate symptom for `java-patterns`, not a pattern chosen here.

| Field | Value |
|---|---|
| Identifier | `UC-112-select-payment-gateway` |
| Date | 2026-09-09 |
| Trigger | payment method availability check, over HTTP — inbound REST adapter |
| Side effects | **0** — the actual authorization is **UC-105**; this use case only resolves which gateway client would handle a given method |
| Storage | none — the case reads and writes no stored aggregate |
| Search index | none |
| Transaction | read-only |
| Idempotency | yes |
| Concurrency | not applicable |
| Active blueprint | `clean-architecture-single-module` |

## Trigger, payload, and response

**In**

| Field | Type | Required | Rule |
|---|---|---|---|
| `paymentMethod` | string | ✅ | one of `CREDIT_CARD`, `PIX`, `BOLETO` |
| `amount` | decimal | ✅ | > 0 |

**Out**

| Situation | Result |
|---|---|
| resolved | gateway name, whether it's currently available |
| unsupported method | rejected without resolving |

## Flow

1. The inbound adapter receives the request and validates the payload's shape.
2. Translates it into `SelectPaymentGatewayCommand`.
3. The use case `SelectPaymentGatewayUseCase` resolves which concrete gateway
   client (`CreditCardGatewayClient`, `PixGatewayClient`, `BoletoGatewayClient`)
   handles the requested method — the return type is always `PaymentGatewayClient`,
   but which concrete class comes back depends entirely on `paymentMethod`; new
   payment methods (e.g. a future `BankTransferGatewayClient`) are expected to be
   added without changing this decision point's callers.
4. It reports the resolved gateway's name and a lightweight availability check.

## Components

State: **NEW** to be created · **CHANGE** already exists and changes · **REUSE**
already exists and serves.

| File | Layer | Role | State | Detailed by |
|---|---|---|---|---|
| `infrastructure/rest/PaymentGatewaySelectionController.java` | infrastructure (in) | receives HTTP | NEW | `rest-api-architect` |
| `infrastructure/rest/dto/PaymentGatewaySelectionResponse.java` | infrastructure (in) | output DTO | NEW | `rest-api-architect` |
| `application/usecase/paymentgateway/SelectPaymentGatewayCommand.java` | application | command | NEW | `domain-modeling` |
| `application/usecase/paymentgateway/SelectPaymentGatewayUseCase.java` | application | resolves and checks availability | NEW | `domain-modeling` |
| `application/port/PaymentGatewayClient.java` | application | outbound port — one contract, one concrete class per method | NEW | `domain-modeling` |
| `domain/model/PaymentMethod.java` | domain | value object | NEW | `domain-modeling` |
| `domain/exception/UnsupportedPaymentMethodException.java` | domain | exception | NEW | `domain-modeling` |
| `infrastructure/gateway/CreditCardGatewayClientAdapter.java` | infrastructure (out) | concrete client | NEW | `persistence-architect` |
| `infrastructure/gateway/PixGatewayClientAdapter.java` | infrastructure (out) | concrete client | NEW | `persistence-architect` |
| `infrastructure/gateway/BoletoGatewayClientAdapter.java` | infrastructure (out) | concrete client | NEW | `persistence-architect` |

## Invariants

| Rule | Guaranteed by | Who violates it |
|---|---|---|
| Every supported `PaymentMethod` resolves to exactly one concrete gateway client | `SelectPaymentGatewayUseCase` | ambiguous mapping between method and client |
| Adding a new payment method never requires changing an existing client's code | the resolution point being the only place that knows the mapping | a client that switches on method internally |
| An unsupported method is rejected before any client is touched | `SelectPaymentGatewayUseCase` guard | a client instantiated speculatively |

## Errors

| Scenario | Exception | Family | Expected result |
|---|---|---|---|
| unsupported `paymentMethod` | `UnsupportedPaymentMethodException` | Validation | 400 |

## Expected tests

| Level | Target | Detailed by |
|---|---|---|
| unit | `SelectPaymentGatewayUseCase`: each method resolves to its expected concrete client | `test-architect` |
| unit | unsupported method is rejected before touching any client | `test-architect` |
| slice | controller: 200, 400 | `test-architect` |

## Out of scope for this use case

- Actually authorizing the payment → **UC-105**, consumes the resolved client.
- Adding `BankTransferGatewayClient` → a future change to this resolution point, not
  part of this spec.
- Gateway credentials and secrets management → infrastructure configuration, not a use
  case.

## Implementation order

- [ ] 1. `domain/model/PaymentMethod.java`
- [ ] 2. `domain/exception/UnsupportedPaymentMethodException.java`
- [ ] 3. `application/usecase/paymentgateway/SelectPaymentGatewayCommand.java`
- [ ] 4. `application/port/PaymentGatewayClient.java`
- [ ] 5. `application/usecase/paymentgateway/SelectPaymentGatewayUseCase.java`
- [ ] 6. `infrastructure/gateway/` (three clients)
- [ ] 7. `infrastructure/rest/`
- [ ] 8. tests for the levels above
- [ ] 9. `./mvnw clean verify` green
