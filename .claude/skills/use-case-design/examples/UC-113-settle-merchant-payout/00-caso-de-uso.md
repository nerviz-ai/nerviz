# UC-113 · Settle merchant payout

> Parent spec. Fixes the boundary and the names; **doesn't** detail any layer.
> Per-layer detail lives in this folder's partials, each with its own owner.
>
> **Example spec** — see `.claude/skills/use-case-design/examples/README.md`. Written
> to exercise `/new-feature` and `java-spring-boot-developer`; the durability requirement
> below is a symptom for `messaging-architect` to resolve, not a form chosen here.

| Field | Value |
|---|---|
| Identifier | `UC-113-settle-merchant-payout` |
| Date | 2026-09-26 |
| Trigger | payout settlement, over HTTP — inbound REST adapter, called by the finance back office |
| Side effects | **1** direct — `UPDATE` on `payouts`; **1** reactive, out of this boundary — the external accounting service credits the merchant's ledger |
| Storage | `Payout` → relational — chosen by: request (the aggregate already lives in a table) |
| Search index | none |
| Transaction | opens in the use case, closes before the response; the accounting service runs in its own process, after this one commits |
| Idempotency | yes — settling an already-settled payout is a no-op, and must never produce a second ledger credit |
| Concurrency | optimistic lock (`version`) on `payouts` |
| Active blueprint | `clean-architecture-single-module` |

## Trigger, payload, and response

**In**

| Field | Type | Required | Rule |
|---|---|---|---|
| `payoutId` | UUID (path) | ✅ | must reference an existing payout in `APPROVED` state |
| `settlementReference` | String (body) | ✅ | the bank's own reference for the transfer; unique across payouts |

**Out**

| Situation | Result |
|---|---|
| settled | payout reference and settled-at instant, `PayoutSettled` emitted |
| already settled with the same reference | same response, no second event |
| payout not approved yet | rejected without touching the domain |
| settlement reference already used by another payout | rejected |

## Flow

1. The inbound adapter receives `payoutId` and `settlementReference`.
2. Translates them into `SettleMerchantPayoutCommand`.
3. `SettleMerchantPayoutUseCase` loads the `Payout` aggregate and calls
   `payout.settle(settlementReference, clock)`, which validates the `APPROVED` state and
   transitions to `SETTLED`, recording `PayoutSettled`.
4. `PayoutRepository` persists the state change. `PayoutSettled` reaches the external
   accounting service, which credits the merchant's ledger.
5. **The event cannot be lost.** Nothing downstream ever re-derives it: the payout is
   `SETTLED` in this system either way, the accounting service has no view of `payouts` to
   reconcile against, and a merchant whose credit never arrived shows up as a support
   ticket weeks later, settled by hand. A broker outage between the commit and the publish
   must not produce that outcome. *How* the publication is made to survive it is
   `messaging-architect`'s decision, from `@.claude/rules/messaging.md` § Publication
   timing — not fixed here.
6. The accounting service is a separate deployable, owned by another team. Its credit is
   irreversible once applied, so it must be idempotent on the event's own identity.

## Components

State: **NEW** to be created · **CHANGE** already exists and changes · **REUSE**
already exists and serves.

| File | Layer | Role | State | Detailed by |
|---|---|---|---|---|
| `infrastructure/rest/PayoutSettlementController.java` | infrastructure (in) | receives HTTP | NEW | `rest-api-architect` |
| `infrastructure/rest/dto/PayoutSettlementRequest.java` | infrastructure (in) | input DTO | NEW | `rest-api-architect` |
| `infrastructure/rest/dto/PayoutSettlementResponse.java` | infrastructure (in) | output DTO | NEW | `rest-api-architect` |
| `application/usecase/payout/SettleMerchantPayoutCommand.java` | application | command | NEW | `domain-modeling` |
| `application/usecase/payout/SettleMerchantPayoutUseCase.java` | application | orchestrates, opens transaction | NEW | `domain-modeling` |
| `application/port/PayoutRepository.java` | application | outbound port | REUSE | `domain-modeling` |
| `application/port/PublishPayoutSettledPort.java` | application | outbound port for the event | NEW | `domain-modeling` |
| `domain/model/Payout.java` | domain | aggregate | CHANGE — adds `settle()` | `domain-modeling` |
| `domain/model/SettlementReference.java` | domain | value object | NEW | `domain-modeling` |
| `domain/event/PayoutSettled.java` | domain | domain event | NEW | `domain-modeling` |
| `domain/exception/PayoutNotApprovedException.java` | domain | exception | NEW | `domain-modeling` |
| `domain/exception/SettlementReferenceAlreadyUsedException.java` | domain | exception | NEW | `domain-modeling` |
| `infrastructure/persistence/payout/PayoutRepositoryAdapter.java` | infrastructure (out) | implements the port | REUSE | `persistence-architect` |
| the adapter that serves `PublishPayoutSettledPort` | infrastructure (out) | carries the event out of the process | NEW | `messaging-architect` |

## Invariants

| Rule | Guaranteed by | Who violates it |
|---|---|---|
| Only an `APPROVED` payout can be settled | `Payout.settle()` guard | caller settling a `PENDING` or `REJECTED` payout |
| A settlement reference belongs to exactly one payout | uniqueness enforced at persistence, checked before the transition | two back-office operators pasting the same bank reference |
| Settling twice with the same reference is a no-op, never a second event | `Payout.settle()` idempotent guard | a retried request |
| A committed settlement always reaches the accounting service, eventually | the publication form `messaging-architect` chooses | a design where the send happens after commit with nothing durable behind it |
| `settledAt` comes from an injected `Clock` | `Payout.settle(reference, clock)` signature | `Instant.now()` inside the aggregate |

## Errors

| Scenario | Exception | Family | Expected result |
|---|---|---|---|
| payout not `APPROVED` | `PayoutNotApprovedException` | Conflict | 409 |
| settlement reference already used by another payout | `SettlementReferenceAlreadyUsedException` | Conflict | 409 |
| payout not found | (adapter-level lookup) | — | 404 |

## Expected tests

| Level | Target | Detailed by |
|---|---|---|
| unit | `Payout`: settles only from `APPROVED`, records `PayoutSettled` exactly once, no-ops on retry with the same reference | `test-architect` |
| unit | `SettlementReference`: formation rule, equality | `test-architect` |
| unit | `SettleMerchantPayoutUseCase`: nothing leaves the process before the transaction commits | `test-architect` |
| slice | controller: 200, 404, 409 for each of the two conflicts | `test-architect` |
| integration | the state change and the outgoing event commit together, or neither does — a failure injected at the publish point leaves no settled payout without a pending publication | `test-architect` |

## Out of scope for this use case

- The accounting service's own credit logic → another team's deployable, reacts to
  `PayoutSettled`.
- Payout approval → the use case upstream of this one, already settled elsewhere.
- Reversing a settlement → a compensating use case with its own event, not this one.
- Pruning, monitoring, or alerting on whatever durable mechanism `messaging-architect`
  chooses → operational concern, named in `25-mensageria.md`, not a business rule here.

## Implementation order

- [ ] 1. `domain/model/SettlementReference.java`
- [ ] 2. `domain/event/PayoutSettled.java`
- [ ] 3. `domain/exception/` (the two)
- [ ] 4. `domain/model/Payout.java` (change)
- [ ] 5. `application/usecase/payout/SettleMerchantPayoutCommand.java`
- [ ] 6. `application/usecase/payout/SettleMerchantPayoutUseCase.java`
- [ ] 7. `application/port/PublishPayoutSettledPort.java` + its adapter
- [ ] 8. `infrastructure/rest/`
- [ ] 9. tests for the levels above
- [ ] 10. `./mvnw clean verify` green
