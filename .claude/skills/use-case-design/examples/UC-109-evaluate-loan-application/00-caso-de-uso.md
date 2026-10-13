# UC-109 · Evaluate loan application

> Parent spec. Fixes the boundary and the names; **doesn't** detail any layer.
> Per-layer detail lives in this folder's partials, each with its own owner.
>
> **Example spec** — see `.claude/skills/use-case-design/examples/README.md`. Written
> to exercise `/new-feature` and `java-spring-boot-developer`; the business rule below
> is a candidate symptom for `java-patterns`, not a pattern chosen here.

| Field | Value |
|---|---|
| Identifier | `UC-109-evaluate-loan-application` |
| Date | 2026-09-09 |
| Trigger | loan application submitted, over HTTP — inbound REST adapter |
| Side effects | **1** — `INSERT` into `loan_evaluations`, recording the outcome and which check produced it |
| Storage | `LoanApplication` → relational — chosen by: request (it names the `loan_evaluations` table) |
| Search index | none |
| Transaction | opens in the use case, closes before the response |
| Idempotency | yes — `Idempotency-Key` required, resubmitting the same application returns the original evaluation |
| Concurrency | `Idempotency-Key` uniqueness is the arbiter |
| Active blueprint | `clean-architecture-single-module` |

## Trigger, payload, and response

**In**

| Field | Type | Required | Rule |
|---|---|---|---|
| `applicantId` | UUID | ✅ | must reference a known applicant |
| `requestedAmount` | decimal | ✅ | > 0 |
| `Idempotency-Key` | header | ✅ | per `@.claude/rules/api-rest.md` |

**Out**

| Situation | Result |
|---|---|
| approved | evaluation reference, approved amount |
| rejected | evaluation reference, the check that rejected it |

## Flow

1. The inbound adapter receives the request and validates the payload's shape.
2. Translates it into `EvaluateLoanApplicationCommand`.
3. The use case `EvaluateLoanApplicationUseCase` runs `LoanApplication` through a
   fixed sequence of independent checks — KYC, credit score, fraud signals, exposure
   limit — where each check can reject outright, or pass the application to the next
   one; the use case doesn't know in advance which check (if any) will be the one that
   decides, and a new check has to be insertable into the sequence without the others
   knowing it exists.
4. If every check passes, the application is approved for the requested amount.
5. `LoanEvaluationRepository` persists the outcome and which check (if any) rejected
   it.

## Components

State: **NEW** to be created · **CHANGE** already exists and changes · **REUSE**
already exists and serves.

| File | Layer | Role | State | Detailed by |
|---|---|---|---|---|
| `infrastructure/rest/LoanApplicationController.java` | infrastructure (in) | receives HTTP | NEW | `rest-api-architect` |
| `infrastructure/rest/dto/LoanApplicationRequest.java` | infrastructure (in) | input DTO | NEW | `rest-api-architect` |
| `infrastructure/rest/dto/LoanEvaluationResponse.java` | infrastructure (in) | output DTO | NEW | `rest-api-architect` |
| `application/usecase/loanapplication/EvaluateLoanApplicationCommand.java` | application | command | NEW | `domain-modeling` |
| `application/usecase/loanapplication/EvaluateLoanApplicationUseCase.java` | application | orchestrates, runs the checks in order | NEW | `domain-modeling` |
| `application/port/KycProvider.java` | application | outbound port | NEW | `domain-modeling` |
| `application/port/CreditScoreProvider.java` | application | outbound port | NEW | `domain-modeling` |
| `application/port/FraudSignalProvider.java` | application | outbound port | NEW | `domain-modeling` |
| `application/port/LoanEvaluationRepository.java` | application | outbound port | NEW | `domain-modeling` |
| `domain/model/LoanApplication.java` | domain | aggregate | NEW | `domain-modeling` |
| `domain/model/LoanEvaluation.java` | domain | value object — outcome + rejecting check, if any | NEW | `domain-modeling` |
| `domain/exception/LoanApplicationRejectedException.java` | domain | exception | NEW | `domain-modeling` |
| `infrastructure/persistence/loanapplication/LoanEvaluationRepositoryAdapter.java` | infrastructure (out) | implements the port | NEW | `persistence-architect` |
| `db/migration/V9__create_loan_evaluations.sql` | infrastructure | migration | NEW | `persistence-architect` |

## Invariants

| Rule | Guaranteed by | Who violates it |
|---|---|---|
| Checks run in a fixed order, and the first rejection stops the sequence | `EvaluateLoanApplicationUseCase` | a check that runs after a prior one already rejected |
| Every evaluation records which check (if any) produced the outcome | `LoanEvaluation` | a code path that only records approve/reject without the reason |
| An approved amount never exceeds `requestedAmount` | `LoanApplication`'s constructor | a check returning a higher amount |

## Errors

| Scenario | Exception | Family | Expected result |
|---|---|---|---|
| any check rejects | `LoanApplicationRejectedException` | Business | 200 with `rejected` outcome — rejection is a valid business result, not an error status |
| unknown applicant | `ValidationException` | Validation | 400 |

## Expected tests

| Level | Target | Detailed by |
|---|---|---|
| unit | `EvaluateLoanApplicationUseCase`: stops at the first rejecting check, runs all checks when every one passes | `test-architect` |
| unit | each check's provider port is called with the expected input | `test-architect` |
| slice | controller: 200 approved, 200 rejected, 400 | `test-architect` |
| integration | `LoanEvaluationRepositoryAdapter` persists outcome and rejecting check | `test-architect` |

## Out of scope for this use case

- Disbursing an approved loan → its own use case, reacts to the approval.
- Manual override of a rejected application → a separate, human-in-the-loop use case.
- The scoring logic inside each individual check (KYC rules, credit model) → owned by
  each provider's own module, not this boundary.

## Implementation order

- [ ] 1. `domain/model/LoanApplication.java`
- [ ] 2. `domain/model/LoanEvaluation.java`
- [ ] 3. `domain/exception/LoanApplicationRejectedException.java`
- [ ] 4. `application/usecase/loanapplication/EvaluateLoanApplicationCommand.java`
- [ ] 5. `application/port/` (three provider ports + repository)
- [ ] 6. `application/usecase/loanapplication/EvaluateLoanApplicationUseCase.java`
- [ ] 7. `db/migration/V9__create_loan_evaluations.sql`
- [ ] 8. `infrastructure/persistence/loanapplication/`
- [ ] 9. `infrastructure/rest/`
- [ ] 10. tests for the levels above
- [ ] 11. `./mvnw clean verify` green
