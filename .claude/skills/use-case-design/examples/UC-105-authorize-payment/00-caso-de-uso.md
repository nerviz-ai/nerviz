# UC-105 · Authorize payment

> Parent spec. Fixes the boundary and the names; **doesn't** detail any layer.
> Per-layer detail lives in this folder's partials, each with its own owner.
>
> **Example spec** — see `.claude/skills/use-case-design/examples/README.md`. Written
> to exercise `/new-feature` and `java-spring-boot-developer`; the business rule below
> is a candidate symptom for `java-patterns`, not a pattern chosen here.

| Field | Value |
|---|---|
| Identifier | `UC-105-authorize-payment` |
| Date | 2026-09-09 |
| Trigger | payment authorization for an order, over HTTP — inbound REST adapter |
| Side effects | **2** — `INSERT` into the internal ledger, and a call to an external payment gateway |
| Storage | `Order` → relational — chosen by: request (the aggregate already lives in a table) |
| Search index | none |
| Transaction | opens in the use case; the ledger write is transactional, the external call is not — see Errors for the reconciliation implication |
| Idempotency | yes — `Idempotency-Key` required, a retried request with the same key returns the original result |
| Concurrency | `Idempotency-Key` uniqueness is the arbiter |
| Active blueprint | `clean-architecture-single-module` |

## Trigger, payload, and response

**In**

| Field | Type | Required | Rule |
|---|---|---|---|
| `orderId` | UUID | ✅ | must reference an existing, unpaid order |
| `amount` | decimal | ✅ | equals the order's total |
| `paymentMethodToken` | string | ✅ | opaque token from the client-side payment form |
| `Idempotency-Key` | header | ✅ | per `@.claude/rules/api-rest.md` |

**Out**

| Situation | Result |
|---|---|
| authorized | ledger entry reference, gateway authorization id |
| declined by the gateway | rejected, order stays unpaid |
| amount mismatch | rejected without calling the gateway |

## Flow

1. The inbound adapter receives the request, validates the payload's shape, and checks
   `Idempotency-Key`.
2. Translates it into `AuthorizePaymentCommand`.
3. The use case `AuthorizePaymentUseCase` validates the amount against the order,
   then calls the outbound port `PaymentAuthorizer`.
4. `PaymentAuthorizer` has to reach two different technologies behind the same
   contract: an internal double-entry ledger (a database write) and an external
   payment gateway (an HTTP call) — the use case only sees "authorize this amount",
   never which technology executed it.
5. On success, `Order` is marked authorized (not yet `PAID` — settlement is a
   different, out-of-scope event) and the ledger entry is persisted.

## Components

State: **NEW** to be created · **CHANGE** already exists and changes · **REUSE**
already exists and serves.

| File | Layer | Role | State | Detailed by |
|---|---|---|---|---|
| `infrastructure/rest/PaymentAuthorizationController.java` | infrastructure (in) | receives HTTP | NEW | `rest-api-architect` |
| `infrastructure/rest/dto/AuthorizePaymentRequest.java` | infrastructure (in) | input DTO | NEW | `rest-api-architect` |
| `infrastructure/rest/dto/PaymentAuthorizationResponse.java` | infrastructure (in) | output DTO | NEW | `rest-api-architect` |
| `infrastructure/rest/PaymentAuthorizationMapper.java` | infrastructure (in) | DTO ↔ domain | NEW | `rest-api-architect` |
| `application/usecase/order/AuthorizePaymentCommand.java` | application | command | NEW | `domain-modeling` |
| `application/usecase/order/AuthorizePaymentUseCase.java` | application | orchestrates, opens transaction | NEW | `domain-modeling` |
| `application/port/PaymentAuthorizer.java` | application | outbound port — one contract, two technologies behind it | NEW | `domain-modeling` |
| `domain/model/Order.java` | domain | aggregate | CHANGE — adds authorized state | `domain-modeling` |
| `domain/exception/AmountMismatchException.java` | domain | exception | NEW | `domain-modeling` |
| `domain/exception/PaymentDeclinedException.java` | domain | exception | NEW | `domain-modeling` |
| `infrastructure/ledger/LedgerPaymentAuthorizerAdapter.java` | infrastructure (out) | internal ledger write | NEW | `persistence-architect` |
| `infrastructure/gateway/GatewayPaymentAuthorizerAdapter.java` | infrastructure (out) | external gateway call | NEW | `persistence-architect` |
| `db/migration/V7__create_ledger_entries.sql` | infrastructure | migration | NEW | `persistence-architect` |

## Invariants

| Rule | Guaranteed by | Who violates it |
|---|---|---|
| `amount` always equals the order's total at authorization time | `AuthorizePaymentUseCase` guard | client sending a stale total |
| An order is authorized at most once | `Idempotency-Key` uniqueness | a retried request without dedup |
| A ledger entry only exists for an authorization the gateway actually accepted | `AuthorizePaymentUseCase` orchestration order | writing the ledger before the gateway responds |

## Errors

| Scenario | Exception | Family | Expected result |
|---|---|---|---|
| amount doesn't match the order total | `AmountMismatchException` | Validation | 400 |
| gateway declines | `PaymentDeclinedException` | External | 402 |
| order already authorized/paid | (idempotent replay) | — | 200, original result |

## Expected tests

| Level | Target | Detailed by |
|---|---|---|
| unit | `AuthorizePaymentUseCase`: rejects amount mismatch before calling the gateway | `test-architect` |
| unit | `Order`: transitions to authorized only from a valid prior state | `test-architect` |
| slice | controller: 200, 400, 402 | `test-architect` |
| integration | `LedgerPaymentAuthorizerAdapter` against a real database; `GatewayPaymentAuthorizerAdapter` against a stubbed gateway | `test-architect` |

## Out of scope for this use case

- Settlement/capture of an authorized payment → its own use case, its own trigger.
- Refunds → separate aggregate lifecycle.
- Reconciliation between the ledger and the gateway on partial failure → an
  operational runbook, not a use case.

## Implementation order

- [ ] 1. `domain/exception/AmountMismatchException.java`
- [ ] 2. `domain/exception/PaymentDeclinedException.java`
- [ ] 3. `domain/model/Order.java` (change)
- [ ] 4. `application/usecase/order/AuthorizePaymentCommand.java`
- [ ] 5. `application/port/PaymentAuthorizer.java`
- [ ] 6. `application/usecase/order/AuthorizePaymentUseCase.java`
- [ ] 7. `db/migration/V7__create_ledger_entries.sql`
- [ ] 8. `infrastructure/ledger/`, `infrastructure/gateway/`
- [ ] 9. `infrastructure/rest/`
- [ ] 10. tests for the levels above
- [ ] 11. `./mvnw clean verify` green
